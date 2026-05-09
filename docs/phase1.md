# ELO-RPS Backend: Iterations 1-7

Build plan for the ELO-teaching Rock-Paper-Scissors-with-twist backend. Stack locked from iteration 0: Spring Boot 3.5 (Kotlin), Supabase (Postgres + Auth), Flyway (manual migrations), jOOQ, Redis on Render, Spring on Render.

Game format reference: best-of-5, first-to-3, 4-phase rounds (A-secret → B-public → A-switch-bit → B-final), roles alternate every round.

---

## Iteration 1: Two-player game, hardcoded entry, no matchmaking, no rating

**Time budget**: ~1 week of evening sessions. This is the biggest iteration and where the most design risk lives.

**Goal**: Two browser tabs play one full 4-phase round end to end. No queue, no ELO, no full match logic — just one round, end to end.

### What you're proving

- WS message contract works
- State machine transitions cleanly on both player input and timer expiration
- Anti-cheat boundary holds (A's secret move never leaks to B's WS session)
- Per-game locking prevents the "input arrives at the same instant the timer fires" race
- Redis schema for live game state is workable

### Migrations

Create with `./gradlew migrationNew -Pname=matches_and_rounds`:

```sql
CREATE TABLE matches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    player_a_id     UUID NOT NULL REFERENCES users(id),
    player_b_id     UUID NOT NULL REFERENCES users(id),
    winner_id       UUID REFERENCES users(id),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at        TIMESTAMPTZ,
    rating_a_before INT,
    rating_b_before INT,
    rating_a_after  INT,
    rating_b_after  INT,
    applied_delta_a INT,
    applied_delta_b INT,
    final_score_a   INT,
    final_score_b   INT
);

CREATE INDEX idx_matches_player_a ON matches(player_a_id, started_at DESC);
CREATE INDEX idx_matches_player_b ON matches(player_b_id, started_at DESC);

CREATE TABLE match_rounds (
    match_id              UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    round_no              INT NOT NULL,
    role_holder_secret_id UUID NOT NULL REFERENCES users(id),
    move_a_initial        TEXT NOT NULL,
    move_b_public         TEXT NOT NULL,
    a_switched            BOOLEAN NOT NULL,
    move_a_final          TEXT NOT NULL,
    move_b_final          TEXT NOT NULL,
    winner_id             UUID REFERENCES users(id),
    rng_seed              BIGINT,
    PRIMARY KEY (match_id, round_no)
);
```

Note: in iteration 1 we only play one round, but the schema is set up for full matches now to avoid a migration in iteration 2.

### Module structure

```
com.tokoizinistri.elorps
├── auth/                  (existing)
├── profile/               (existing)
├── shared/                (existing)
├── game/
│   ├── domain/            # Move, Phase, GameState, RoundState
│   ├── state/             # GameSession, state machine
│   ├── redis/             # Redis schema, serialization
│   ├── timer/             # PhaseTimerScheduler
│   ├── api/               # Dev start-game endpoint
│   └── ws/                # WS handlers for move/switch
├── ws/                    # STOMP config, JWT auth on connect
└── jooq/                  (generated)
```

### Redis schema

```
game:{gameId}                   HASH
  state                         (LOBBY_INIT | PHASE_1 | PHASE_2 | PHASE_3 | PHASE_4 | REVEAL | FINISHED)
  current_round                 (1..5)
  player_a_id                   UUID
  player_b_id                   UUID
  role_holder_secret_id         UUID (whose turn for hidden role)
  phase_deadline_epoch_ms       LONG
  score_a                       INT
  score_b                       INT
  created_at                    LONG

game:{gameId}:round:{n}         HASH
  move_a_initial                (ROCK | PAPER | SCISSORS)
  move_b_public                 (ROCK | PAPER | SCISSORS)
  a_switch_decision             (SWITCH_TO_X | SWITCH_TO_Y | NO_SWITCH)
  b_switch_decision             (SWITCH_TO_X | SWITCH_TO_Y | NO_SWITCH)
  move_a_final                  (ROCK | PAPER | SCISSORS)
  move_b_final                  (ROCK | PAPER | SCISSORS)
  winner_id                     UUID or "DRAW"
  rng_seed                      LONG
```

TTL on the `game:{gameId}` keys: 1 hour. Cleaned up explicitly at match end, but TTL catches abandoned games.

### State machine

```kotlin
enum class GamePhase {
    LOBBY_INIT,
    PHASE_1_A_SECRET,    // A submits hidden initial move
    PHASE_2_B_PUBLIC,    // B submits public initial move
    PHASE_3_A_SWITCH,    // A decides switch/no-switch (only the bit is published)
    PHASE_4_B_FINAL,     // B decides switch/no-switch (B's final move is public)
    ROUND_REVEAL,        // both moves shown, winner determined
    MATCH_FINISHED,
}
```

Phase durations (configurable, but reasonable defaults):
- PHASE_1: 8 seconds
- PHASE_2: 8 seconds
- PHASE_3: 6 seconds
- PHASE_4: 6 seconds
- REVEAL: 3 seconds (animation/display, then auto-advance)

### Key design decisions

**State machine location**: in-memory per `GameSession` object, but every state transition writes the snapshot to Redis. The in-memory state is the source of truth during the game; Redis is the recovery aid for reconnect.

**Timer mechanism**: `ThreadPoolTaskScheduler` with thread pool size = expected concurrent games × 2 (start with 10). Each phase entry schedules a `phaseDeadlineExpired(gameId, phaseN)` task. Early completion (both inputs received) cancels the scheduled future.

**Concurrency**: per-game `ReentrantLock` keyed by gameId, held for the duration of any state transition. All state transitions (input arrival, timer expiration, reconnect) acquire the lock first, check current phase as a precondition, and only then advance. Lock map uses `ConcurrentHashMap`; entries removed at game end.

**Idempotency**: move submissions keyed by `(gameId, round_no, player_id)`. Resubmitting the same phase from the same player is rejected with an error message back over WS, not silently ignored.

### WebSocket message contracts

Client → server (STOMP destinations under `/app/`):

```kotlin
// /app/game/{gameId}/move
data class SubmitMoveMessage(
    val roundNo: Int,
    val phase: GamePhase,           // PHASE_1_A_SECRET or PHASE_2_B_PUBLIC
    val move: Move,                  // ROCK/PAPER/SCISSORS
)

// /app/game/{gameId}/switch
data class SubmitSwitchMessage(
    val roundNo: Int,
    val phase: GamePhase,           // PHASE_3_A_SWITCH or PHASE_4_B_FINAL
    val decision: SwitchDecision,    // NO_SWITCH | SWITCH_TO_<MOVE>
)
```

Server → client (broadcast and user-specific):

```kotlin
// /user/queue/game/{gameId}/state — sent to each player on phase advance
data class GameStateMessage(
    val gameId: UUID,
    val currentRound: Int,
    val phase: GamePhase,
    val phaseDeadlineEpochMs: Long,
    val scoreA: Int,
    val scoreB: Int,
    val yourRole: PlayerRole,        // SECRET_HOLDER (A) or PUBLIC_MOVER (B), this round
    val publicMoves: PublicMovesView, // what's safe to reveal at this point in the round
)

// PublicMovesView contains only what's been made public so far this round.
// In PHASE_3_A_SWITCH, B's public move is included. A's secret move is NOT.
// In PHASE_4_B_FINAL, A's switch BIT (true/false) is included. A's actual final move is NOT.
// In ROUND_REVEAL, everything is included.

// /topic/game/{gameId}/round/{n}/reveal — broadcast to both players
data class RoundRevealMessage(
    val roundNo: Int,
    val moveAInitial: Move,
    val moveBPublic: Move,
    val aSwitched: Boolean,
    val moveAFinal: Move,
    val moveBFinal: Move,
    val winnerId: UUID?,             // null = draw
    val scoreA: Int,
    val scoreB: Int,
)

// /user/queue/game/{gameId}/match-finished
data class MatchFinishedMessage(
    val gameId: UUID,
    val winnerId: UUID?,
    val finalScoreA: Int,
    val finalScoreB: Int,
)
```

**Anti-cheat reminder**: A's secret move (PHASE_1) and A's switch decision (PHASE_3) are server-side only until the reveal tick. Use `/user/queue/...` user-specific destinations for any private confirmations to A; never broadcast on `/topic/...` until reveal.

### Default moves on timeout

Decided policy:
- PHASE_1 (A secret): random move with `SecureRandom`-seeded RNG, seed persisted in `match_rounds.rng_seed`
- PHASE_2 (B public): random move, same RNG
- PHASE_3 (A switch): default to NO_SWITCH
- PHASE_4 (B final): default to NO_SWITCH

### Dev endpoint

`POST /api/dev/start-game` (only available with `dev` profile):

```kotlin
data class StartGameRequest(val playerAId: UUID, val playerBId: UUID)
data class StartGameResponse(val gameId: UUID)
```

Creates Redis state, schedules first phase timer, broadcasts `GameStateMessage` for PHASE_1 to both players via their user-specific destinations.

For iteration 1, only run for **one round** then transition straight to MATCH_FINISHED. Match logic comes in iteration 2.

### Ship test

1. Open two browser tabs (or curl + a simple WS client) logged in as different users
2. Hit `/api/dev/start-game` with both user IDs
3. Both tabs receive `GameStateMessage` for PHASE_1
4. Player A submits a move via `/app/game/{id}/move`
5. State advances to PHASE_2; both tabs receive new `GameStateMessage` (with B's role active)
6. Player B submits a move
7. PHASE_3 starts; A's tab gets the public B-move in `GameStateMessage`, A submits switch decision
8. PHASE_4 starts; B sees only A's switch bit, submits switch decision
9. ROUND_REVEAL fires; both tabs receive `RoundRevealMessage` with full round data
10. MATCH_FINISHED follows; both tabs receive `MatchFinishedMessage`
11. Verify Redis state is cleaned up
12. Verify a `matches` row exists with one `match_rounds` child (in iteration 1 we'll persist even one-round games, makes iteration 2 a smaller jump)

Test the timeout case: don't submit a move, watch the timer fire and a default move get picked.

Test concurrency: have one player submit at the same instant the timer expires. Inspect logs to confirm one path won and the other was rejected gracefully.

### Definition of done

- [ ] WS gateway with STOMP and JWT auth on CONNECT
- [ ] Redis schema for game state, round state
- [ ] State machine for one round across all 4 phases
- [ ] Server-authoritative phase timer with cancellation on early completion
- [ ] Per-game locking, idempotent move submission
- [ ] User-specific destinations used for any private state (no leak via topics)
- [ ] Default moves on timeout with persisted RNG seed
- [ ] Dev endpoint to start a game between two named users
- [ ] One-round game persisted to `matches` + `match_rounds` at completion
- [ ] Two-tab manual test passes end to end
- [ ] Timeout test passes
- [ ] Concurrent-input test passes (no double-advance)

---

## Iteration 2: Full match — Bo5, first to 3, role alternation

**Time budget**: 2-3 days.

**Goal**: Same hardcoded `/api/dev/start-game` entry plays a full match with proper round counting, role swapping, and first-to-3 termination.

### What you're proving

- State machine composition (match wraps round) works
- Variable match length (3, 4, or 5 rounds) handled correctly
- Postgres write at match end is transactionally clean

### Migrations

None — schema from iteration 1 already supports multi-round matches.

### Changes

**Match state machine** wraps round state machine:

```kotlin
class MatchSession(
    val gameId: UUID,
    val playerAId: UUID,
    val playerBId: UUID,
) {
    var currentRound: Int = 1
    var scoreA: Int = 0
    var scoreB: Int = 0
    var roleAssignmentForCurrentRound: RoleAssignment = ...
    val completedRounds: MutableList<RoundResult> = mutableListOf()

    fun onRoundComplete(result: RoundResult) {
        completedRounds.add(result)
        when (result.winnerId) {
            playerAId -> scoreA++
            playerBId -> scoreB++
        }
        if (scoreA >= 3 || scoreB >= 3) {
            transitionToMatchFinished()
        } else {
            currentRound++
            swapRoles()
            startNextRound()
        }
    }
}
```

**Role assignment**:
- Coin flip at match start determines who is A (secret holder) in round 1
- Round 2: roles swap
- Round 3: swap again (back to round-1 assignment)
- And so on

Persist the role assignment on the match record and per round.

**First-to-3 termination**: check after every round. Match length is 3 (3-0), 4 (3-1), or 5 (3-2).

**Match end persistence** (single transaction):

```kotlin
@Transactional
fun persistMatch(session: MatchSession) {
    val matchId = dsl.insertInto(MATCHES)
        .set(MATCHES.ID, session.gameId)
        .set(MATCHES.PLAYER_A_ID, session.playerAId)
        .set(MATCHES.PLAYER_B_ID, session.playerBId)
        .set(MATCHES.WINNER_ID, session.winnerId())
        .set(MATCHES.STARTED_AT, session.startedAt)
        .set(MATCHES.ENDED_AT, OffsetDateTime.now())
        .set(MATCHES.FINAL_SCORE_A, session.scoreA)
        .set(MATCHES.FINAL_SCORE_B, session.scoreB)
        .returning(MATCHES.ID)
        .fetchOne()!!
        .get(MATCHES.ID)

    session.completedRounds.forEachIndexed { idx, round ->
        dsl.insertInto(MATCH_ROUNDS)
            .set(MATCH_ROUNDS.MATCH_ID, matchId)
            .set(MATCH_ROUNDS.ROUND_NO, idx + 1)
            .set(MATCH_ROUNDS.ROLE_HOLDER_SECRET_ID, round.roleHolderSecretId)
            .set(MATCH_ROUNDS.MOVE_A_INITIAL, round.moveAInitial.name)
            .set(MATCH_ROUNDS.MOVE_B_PUBLIC, round.moveBPublic.name)
            .set(MATCH_ROUNDS.A_SWITCHED, round.aSwitched)
            .set(MATCH_ROUNDS.MOVE_A_FINAL, round.moveAFinal.name)
            .set(MATCH_ROUNDS.MOVE_B_FINAL, round.moveBFinal.name)
            .set(MATCH_ROUNDS.WINNER_ID, round.winnerId)
            .set(MATCH_ROUNDS.RNG_SEED, round.rngSeed)
            .execute()
    }
}
```

### Read endpoint

```kotlin
@GetMapping("/api/match/{id}")
fun getMatch(@PathVariable id: UUID): MatchDetailResponse
```

Auth check: only participants can read. Return match summary + all rounds.

### Ship test

Play a full Bo5 with two tabs:
- 3-0 sweep — verify match ends after round 3
- 3-1 — verify match ends after round 4
- 3-2 — verify match ends after round 5

For each, verify Postgres rows: 1 `matches` row, N `match_rounds` rows with correct role assignments per round, all in a single transaction.

### Definition of done

- [ ] Match state machine wraps round state machine
- [ ] Score tracking and first-to-3 termination
- [ ] Roles alternate every round
- [ ] Coin flip on match start visible in match record (which player started as A)
- [ ] Variable match length (3/4/5 rounds) all work correctly
- [ ] `@Transactional` match-end persistence: matches + all match_rounds in one transaction
- [ ] `GET /api/match/{id}` returns full match data, auth-restricted to participants
- [ ] Manual test: 3-0, 3-1, 3-2 all complete cleanly with correct DB state

---

## Iteration 3: ELO + rating updates

**Time budget**: 1-2 days.

**Goal**: Match end updates ratings.

### What you're proving

- Rating math is correct (unit tests for known cases)
- Transaction integrity: match save + rating update either both happen or neither does

### Migrations

`./gradlew migrationNew -Pname=rating_history`:

```sql
CREATE TABLE rating_history (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id),
    match_id        UUID NOT NULL REFERENCES matches(id),
    rating_before   INT NOT NULL,
    rating_after    INT NOT NULL,
    delta           INT NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rating_history_user_recorded ON rating_history(user_id, recorded_at DESC);
```

### Rating service

Pure function, no side effects:

```kotlin
package com.tokoizinistri.elorps.rating

@Service
class RatingService {
    private val K = 32
    private val SCALE = 400.0

    fun calculate(
        ratingA: Int,
        ratingB: Int,
        winnerScoreA: Double,    // 1.0 if A won, 0.0 if B won, 0.5 for draw
    ): RatingCalcResult {
        val expectedA = 1.0 / (1.0 + Math.pow(10.0, (ratingB - ratingA) / SCALE))
        val expectedB = 1.0 - expectedA
        val winnerScoreB = 1.0 - winnerScoreA

        val deltaA = (K * (winnerScoreA - expectedA)).roundToInt()
        val deltaB = (K * (winnerScoreB - expectedB)).roundToInt()

        return RatingCalcResult(
            classicDeltaA = deltaA,
            classicDeltaB = deltaB,
            appliedDeltaA = deltaA,    // for now, applied == classic
            appliedDeltaB = deltaB,
            expectedScoreA = expectedA,
            actualScoreA = winnerScoreA,
            kFactor = K,
        )
    }
}

data class RatingCalcResult(
    val classicDeltaA: Int,
    val classicDeltaB: Int,
    val appliedDeltaA: Int,
    val appliedDeltaB: Int,
    val expectedScoreA: Double,
    val actualScoreA: Double,
    val kFactor: Int,
)
```

### Unit tests for known cases

```kotlin
@Test
fun `equal ratings, A wins, K=32, delta is +16 -16`() {
    val result = service.calculate(1500, 1500, winnerScoreA = 1.0)
    assertEquals(16, result.classicDeltaA)
    assertEquals(-16, result.classicDeltaB)
}

@Test
fun `200-point favorite winning gets smaller delta`() {
    val result = service.calculate(1700, 1500, winnerScoreA = 1.0)
    // expected ~0.76, delta ~ 32 * 0.24 ~ 8
    assertTrue(result.classicDeltaA in 7..9)
}

@Test
fun `200-point underdog winning gets larger delta`() {
    val result = service.calculate(1500, 1700, winnerScoreA = 1.0)
    assertTrue(result.classicDeltaA in 23..25)
}
```

### Match-end flow update

Inside the existing `@Transactional persistMatch`:

1. Read current ratings (with `SELECT FOR UPDATE` to prevent races, even though contention is unlikely now)
2. Call `RatingService.calculate(...)`
3. Insert match row (with rating_before/after/delta columns populated)
4. Insert match_rounds rows
5. Update `player_ratings` for both players (rating, games_played++, peak_rating if exceeded)
6. Insert `rating_history` rows for both players
7. Return rating result for the WS broadcast

```kotlin
@Transactional
fun finalizeMatch(session: MatchSession): MatchFinishedResult {
    val ratingA = dsl.select(PLAYER_RATINGS.RATING)
        .from(PLAYER_RATINGS)
        .where(PLAYER_RATINGS.USER_ID.eq(session.playerAId))
        .forUpdate()
        .fetchOne()!![PLAYER_RATINGS.RATING]
    val ratingB = // same for B

    val winnerScoreA = when (session.winnerId()) {
        session.playerAId -> 1.0
        session.playerBId -> 0.0
        else -> 0.5  // shouldn't happen in Bo5 first-to-3, but safe default
    }

    val ratingResult = ratingService.calculate(ratingA, ratingB, winnerScoreA)

    // ... insert match with rating_before/after/delta populated
    // ... insert match_rounds
    // ... update player_ratings
    // ... insert rating_history

    return MatchFinishedResult(
        gameId = session.gameId,
        winnerId = session.winnerId(),
        finalScoreA = session.scoreA,
        finalScoreB = session.scoreB,
        ratingResult = ratingResult,
        ratingABefore = ratingA,
        ratingAAfter = ratingA + ratingResult.appliedDeltaA,
        ratingBBefore = ratingB,
        ratingBAfter = ratingB + ratingResult.appliedDeltaB,
    )
}
```

### Match-end broadcast

`MatchFinishedMessage` now includes both ratings before/after and the delta. Even though `appliedDelta == classicDelta` for now, plumb both through — iteration 6 will diverge them.

### Ship test

Play a match, watch your rating change. Play several more, verify:
- Equal-rated win/loss is roughly ±16
- Higher-rated player winning gains less than 16
- Lower-rated player winning gains more than 16
- Loser's loss equals winner's gain (zero-sum)
- `rating_history` rows accumulate correctly
- `peak_rating` updates on new highs only

### Definition of done

- [ ] `RatingService` with classic ELO calc, K=32
- [ ] Unit tests for ≥5 known cases
- [ ] Rating update happens inside the same transaction as match save
- [ ] `SELECT FOR UPDATE` on player_ratings during match finalize
- [ ] `rating_history` populated for both players
- [ ] `peak_rating` updated correctly
- [ ] Match-end broadcast includes rating data
- [ ] `/api/profile/me` reflects updated rating after match
- [ ] Manual test: play 5+ matches, verify rating arithmetic by hand

**At the end of iteration 3 you have a playable game.** Everything from here scales it to more than two hand-provisioned users.

---

## Iteration 4: Matchmaking

**Time budget**: 3-4 days.

**Goal**: Players queue up and get auto-paired by ELO band.

### What you're proving

- Queue contention handled correctly (no double-pairing)
- Widening tolerance band finds matches at low player counts
- Clean handoff from queue → game

### Migrations

None.

### Redis schema

```
mm:queue                        ZSET
  member: user_id
  score:  rating

mm:player:{userId}              HASH  (TTL: 5 minutes)
  queued_at_epoch_ms            LONG
  current_radius                INT
  rating_at_queue               INT
```

### Matchmaking worker

```kotlin
package com.tokoizinistri.elorps.matchmaking

@Component
class MatchmakingWorker(
    private val redis: StringRedisTemplate,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 500)
    fun tick() {
        val queuedPlayers = redis.opsForZSet()
            .rangeWithScores("mm:queue", 0, -1)
            ?.toList()
            ?: return

        if (queuedPlayers.size < 2) return

        val pairedThisTick = mutableSetOf<String>()

        for (player in queuedPlayers) {
            if (player.value!! in pairedThisTick) continue

            val playerData = redis.opsForHash<String, String>()
                .entries("mm:player:${player.value}")
            val queuedAt = playerData["queued_at_epoch_ms"]?.toLong() ?: continue
            val radius = currentRadius(queuedAt)

            val candidate = findCandidate(player, radius, pairedThisTick)
                ?: continue

            // Atomic pair-and-remove via Lua
            val paired = pairAtomic(player.value!!, candidate.value!!)
            if (paired) {
                pairedThisTick.add(player.value!!)
                pairedThisTick.add(candidate.value!!)

                // Cleanup player hashes
                redis.delete("mm:player:${player.value}")
                redis.delete("mm:player:${candidate.value}")

                eventPublisher.publishEvent(
                    MatchPaired(
                        playerAId = UUID.fromString(player.value!!),
                        playerBId = UUID.fromString(candidate.value!!),
                        ratingA = player.score!!.toInt(),
                        ratingB = candidate.score!!.toInt(),
                    )
                )
            }
        }
    }

    private fun currentRadius(queuedAtMs: Long): Int {
        val waitedSeconds = (System.currentTimeMillis() - queuedAtMs) / 1000
        val baseRadius = 50
        val widenPerSecond = 25
        val maxRadius = 400
        return minOf(baseRadius + (waitedSeconds * widenPerSecond).toInt(), maxRadius)
    }

    private fun findCandidate(
        player: ZSetOperations.TypedTuple<String>,
        radius: Int,
        excluded: Set<String>,
    ): ZSetOperations.TypedTuple<String>? {
        val rating = player.score!!
        val candidates = redis.opsForZSet()
            .rangeByScoreWithScores("mm:queue", rating - radius, rating + radius)
            ?: return null

        return candidates
            .filter { it.value != player.value && it.value !in excluded }
            .minByOrNull { kotlin.math.abs(it.score!! - rating) }
    }

    private fun pairAtomic(playerA: String, playerB: String): Boolean {
        val script = """
            local removedA = redis.call('ZREM', KEYS[1], ARGV[1])
            local removedB = redis.call('ZREM', KEYS[1], ARGV[2])
            if removedA == 1 and removedB == 1 then
                return 1
            else
                if removedA == 1 then
                    -- shouldn't happen but be safe; need to know rating to re-add
                    -- safer: don't roll back, the race-loser will re-queue if they want
                end
                if removedB == 1 then
                    -- same
                end
                return 0
            end
        """.trimIndent()

        val redisScript = DefaultRedisScript(script, Long::class.java)
        val result = redis.execute(
            redisScript,
            listOf("mm:queue"),
            playerA, playerB
        )
        return result == 1L
    }
}

data class MatchPaired(
    val playerAId: UUID,
    val playerBId: UUID,
    val ratingA: Int,
    val ratingB: Int,
)
```

The Lua script handles the case where someone leaves the queue between the worker's read and write — if either ZREM returns 0, the pairing is stale.

### Game module listens to MatchPaired

```kotlin
@Component
class MatchPairedHandler(private val gameSessionService: GameSessionService) {
    @EventListener
    fun on(event: MatchPaired) {
        gameSessionService.startGame(event.playerAId, event.playerBId)
    }
}
```

This replaces the dev endpoint's role for triggering games. Keep the dev endpoint behind the `dev` profile for testing.

### REST endpoints

```kotlin
@PostMapping("/api/queue/join")
fun join(): QueueStatusResponse {
    val userId = AuthenticatedUser.currentUserId()
    val rating = profileService.getRating(userId)

    redis.opsForZSet().add("mm:queue", userId.toString(), rating.toDouble())
    redis.opsForHash<String, String>().putAll(
        "mm:player:$userId",
        mapOf(
            "queued_at_epoch_ms" to System.currentTimeMillis().toString(),
            "rating_at_queue" to rating.toString(),
        )
    )
    redis.expire("mm:player:$userId", Duration.ofMinutes(5))

    return QueueStatusResponse(status = "QUEUED", rating = rating)
}

@PostMapping("/api/queue/leave")
fun leave(): QueueStatusResponse {
    val userId = AuthenticatedUser.currentUserId()
    redis.opsForZSet().remove("mm:queue", userId.toString())
    redis.delete("mm:player:$userId")
    return QueueStatusResponse(status = "LEFT", rating = null)
}
```

### WS topics

```
/user/queue/lobby                       — queue status updates
/user/queue/lobby/match-found            — sent when paired, includes gameId
```

The matchmaking worker emits a status broadcast every few ticks while a player is queued ("searching... ±150 rating, waited 6s").

### Ship test

1. Three friends open tabs, all hit `/api/queue/join`
2. Two get paired (closest rating), one stays queued
3. The two play their match, winner gets +ELO
4. After their match, all three queue again, the previously-waiting one gets paired

Test queue widening: have one player queue at rating 1200, another at rating 1500. They shouldn't pair immediately. After ~12 seconds of widening, they pair.

Test contention: two pairs of users queue simultaneously. Verify all four get paired into two games, no double-pairing.

### Definition of done

- [ ] Redis ZSET queue, per-player hash with TTL
- [ ] `MatchmakingWorker` with `@Scheduled(fixedDelay = 500)`
- [ ] Widening band: ±50 → ±400 over time
- [ ] Lua script for atomic pair-and-remove
- [ ] `MatchPaired` event triggers game creation
- [ ] `/api/queue/join` and `/api/queue/leave` endpoints
- [ ] `/user/queue/lobby` WS topic with status updates
- [ ] Multi-player manual test passes
- [ ] After match, both players are released; re-queue works

---

## Iteration 5: Reconnection + grace period

**Time budget**: 2-3 days.

**Goal**: A wifi blip doesn't lose you the match.

### What you're proving

- Redis state is rich enough to rebuild the client view on reconnect
- Timer pause/resume is implemented cleanly
- Disconnect grace period prevents accidental forfeits without enabling griefing

### Migrations

None.

### Reconnection flow

Client detects WS disconnect → reconnects (with the JWT) → sends:

```kotlin
// /app/game/{gameId}/resume
data class ResumeMessage(val gameId: UUID)
```

Server response:

```kotlin
@MessageMapping("/game/{gameId}/resume")
fun resume(
    @DestinationVariable gameId: UUID,
    msg: ResumeMessage,
    principal: Principal,
) {
    val userId = UUID.fromString(principal.name)
    val session = gameSessionService.findActive(gameId)
        ?: run {
            // Game ended already, send terminal state
            sendUserDestination(userId, "/queue/game/$gameId/state", GameEndedMessage(...))
            return
        }

    require(userId == session.playerAId || userId == session.playerBId)

    // Resume: send full state snapshot
    val snapshot = session.snapshotFor(userId)
    sendUserDestination(userId, "/queue/game/$gameId/state", snapshot)

    // Resume timer if it was paused for this player's disconnect
    session.onPlayerReconnect(userId)
}
```

`session.snapshotFor(userId)` produces a `GameStateMessage` with everything that's safe for that user to know at this point — including their own moves so far and the public moves from the opponent, but NOT the opponent's hidden state.

### Disconnect detection

Spring WS `SessionDisconnectEvent` listener:

```kotlin
@Component
class GameDisconnectListener(private val gameSessionService: GameSessionService) {
    @EventListener
    fun onDisconnect(event: SessionDisconnectEvent) {
        val userId = extractUserId(event) ?: return
        gameSessionService.onPlayerDisconnect(userId)
    }
}
```

`gameSessionService.onPlayerDisconnect(userId)`:

1. Find any active game involving the user
2. If found and game is mid-round (not REVEAL), pause the phase timer:
    - Cancel the current `ScheduledFuture`
    - Compute remaining ms = deadline - now
    - Stash `remaining_ms_on_pause` and `paused_for_user_id` in Redis game state
    - Schedule a "grace expiry" task for `now + 30 seconds` that, if not cancelled, forfeits the round for the disconnected user
3. Broadcast `OpponentDisconnectedMessage` to the other player so they see "opponent disconnected, 30s grace"

### Reconnect mid-grace

`session.onPlayerReconnect(userId)`:

1. If game is paused for this user:
    - Cancel the grace expiry task
    - Resume the phase timer with `remaining_ms_on_pause`
    - Clear `paused_for_user_id` from Redis
    - Broadcast `OpponentReconnectedMessage` to the other player

### Grace expiry

If the 30-second grace task runs without being cancelled:

1. Forfeit the current round for the disconnected user (default to a losing-by-default move, or just record it as a forfeit round)
2. Advance to the next round
3. If forfeited user still hasn't reconnected, repeat at next phase boundary

After 5 minutes of total disconnection: forfeit the whole match.

### Both players disconnect

If both players disconnect simultaneously, freeze the game (cancel timers, but keep Redis state). After 10 minutes of dual-disconnect, forfeit the match (no winner, no rating change — or by chronologically-last-disconnected, your call).

### Ship test

1. Play a match, force-close one tab mid-PHASE_2
2. Verify other tab shows "opponent disconnected, grace period"
3. Reopen the tab within 30s, log in, send RESUME
4. Verify game state restores with correct phase, deadline, your moves so far
5. Verify timer resumes with correct remaining ms

Then:

6. Force-close one tab and don't reopen for >30s
7. Verify forfeit round, match continues
8. Tab reconnects later in next round, plays normally

Edge case: what if PHASE_3 (A's switch decision) is in progress and A disconnects? On reconnect, A should see B's public move and have remaining time to make the switch decision.

### Definition of done

- [ ] WS disconnect detection
- [ ] 30-second grace period with timer pause
- [ ] Opponent receives "disconnected" / "reconnected" notifications
- [ ] Resume message restores full state for the reconnecting user
- [ ] Hidden state stays hidden across reconnect (no leakage)
- [ ] Round forfeit on grace expiry, match continues
- [ ] Match forfeit on prolonged dual-disconnect
- [ ] Manual test: disconnect mid-round, reconnect, resume seamlessly

---

## Iteration 6: Score-based ELO + teaching surface

**Time budget**: 2 days.

**Goal**: Make this a teaching tool, not just a game.

### What you're proving

- Teaching narrative lands when a real player sees it
- Persisted match data is rich enough for post-match analysis

### Migrations

`./gradlew migrationNew -Pname=match_analytics`:

```sql
ALTER TABLE matches
    ADD COLUMN expected_score_a NUMERIC(5,4),
    ADD COLUMN actual_score_a NUMERIC(5,4),
    ADD COLUMN k_factor INT;
```

These are the bits we want to surface in the teaching screen.

### Updated rating service

Pick the variant:

**Option A: score-based ELO** (winner's actual score = round_wins / total_rounds_played)

```kotlin
fun calculate(
    ratingA: Int,
    ratingB: Int,
    roundsWonA: Int,
    roundsWonB: Int,
): RatingCalcResult {
    val totalRounds = roundsWonA + roundsWonB
    val actualScoreA = roundsWonA.toDouble() / totalRounds
    val expectedA = 1.0 / (1.0 + Math.pow(10.0, (ratingB - ratingA) / SCALE))

    val classicScoreA = if (roundsWonA > roundsWonB) 1.0 else 0.0
    val classicDeltaA = (K * (classicScoreA - expectedA)).roundToInt()
    val appliedDeltaA = (K * (actualScoreA - expectedA)).roundToInt()

    return RatingCalcResult(
        classicDeltaA = classicDeltaA,
        classicDeltaB = -classicDeltaA,
        appliedDeltaA = appliedDeltaA,
        appliedDeltaB = -appliedDeltaA,
        expectedScoreA = expectedA,
        actualScoreA = actualScoreA,
        kFactor = K,
    )
}
```

**Option B: MOV multiplier**

```kotlin
val roundDiff = kotlin.math.abs(roundsWonA - roundsWonB)
val movMultiplier = 1.0 + Math.log(roundDiff.toDouble())
val classicScoreA = if (roundsWonA > roundsWonB) 1.0 else 0.0
val appliedDeltaA = (K * movMultiplier * (classicScoreA - expectedA)).roundToInt()
```

Recommendation: start with Option A (score-based). Easier to explain, no new parameters. Switch to B later if "decisive wins should reward more than classic" feels more right.

### Match-end teaching data

In addition to the existing match summary, compute per-match analytics on the rounds. The simplest version is computed on read (not persisted):

```kotlin
data class MatchTeachingDataResponse(
    val ratingDelta: RatingDelta,
    val behaviorAnalysis: BehaviorAnalysis,
)

data class RatingDelta(
    val ratingBefore: Int,
    val ratingAfter: Int,
    val classicDelta: Int,
    val appliedDelta: Int,
    val expectedScore: Double,
    val actualScore: Double,
)

data class BehaviorAnalysis(
    // For rounds where the player held the secret role (A):
    val secretRoleRounds: Int,
    val switchedWhenWinning: Int,    // out of secretRoleRounds where initial > B's public
    val switchedWhenLosing: Int,
    val switchedWhenTied: Int,

    // Optional — what the opponent could have inferred from your switch pattern
    val predictabilityScore: Double, // 0.0 = perfectly mixed, 1.0 = fully deterministic
)
```

Add `GET /api/match/{id}/teaching` returning this. The frontend renders the post-match teaching screen.

### Match-end broadcast update

`MatchFinishedMessage` now includes both `classicDelta` and `appliedDelta`. Frontend can show "Classic ELO would have given +12, score-based gave +9 — here's why."

### Ship test

Play a match, hit `/api/match/{id}/teaching`, verify the analytics make sense:
- Round counts match what you played
- Switch counts reflect your actual decisions
- Rating delta math: classic vs applied differ as expected for non-3-0 matches

Read the response narratively: does it tell a useful story about your play?

### Definition of done

- [ ] `RatingService` returns both classic and applied deltas
- [ ] Score-based (or MOV-multiplier) calc applied as the actual delta
- [ ] `matches` table stores expected/actual score and K factor
- [ ] `/api/match/{id}/teaching` returns analytics
- [ ] Match-end broadcast includes both deltas
- [ ] Manual test: play several matches, verify teaching data is informative

---

## Iteration 7: Polish for real users

**Time budget**: open-ended. This is the iteration that decides whether the project is ready for invites or just personal use.

### Areas to address

**Default-move policy refinements**:
- Random move on PHASE_1/PHASE_2 timeout uses `SecureRandom` with seed persisted to `match_rounds.rng_seed`
- Audit log of timeouts so you can see "user X timed out 4 times this week" (potential disengagement signal)
- Consider whether to reduce K-factor temporarily for users with high timeout rates

**Leaderboard**:
```kotlin
@GetMapping("/api/leaderboard")
fun leaderboard(
    @RequestParam(defaultValue = "50") limit: Int,
    @RequestParam(defaultValue = "0") offset: Int,
): LeaderboardResponse
```

Cache with Caffeine for 60s — this query hits the indexed `idx_player_ratings_rating_desc` so it's fast, but reads spike when people refresh after their matches.

**Recent matches list on profile**:
```kotlin
@GetMapping("/api/profile/me/matches")
fun myMatches(@RequestParam(defaultValue = "20") limit: Int): List<MatchSummary>
```

**Rate limiting on `/api/queue/join`**:
- Bucket4j with `5 requests per minute per user`
- Prevent thrash: a player rapidly join/leave/join/leave is mostly harmless but generates noise

**Error surfaces**:
- Redis down: queue writes fail gracefully, return 503; live games might survive briefly via in-memory state
- Supabase down: REST endpoints return 503; live games complete from in-memory state but match-end persistence will fail (queue match results in Redis for retry)
- Both players disconnect: covered in iteration 5
- Rating service throws (shouldn't but defensive): broadcast match result without rating, log error, manual reconciliation possible from `match_rounds`

**Metrics** (Micrometer + Render's metrics):
- Queue depth (gauge)
- Average wait time before pairing (histogram)
- Average match duration (histogram)
- Round outcome distribution (counter by winner)
- Timeout rate per phase (counter)
- Rating distribution (histogram, for understanding the player base)

**Observability**:
- Structured JSON logging via Logstash encoder
- Correlation IDs on each match flowing through all logs
- Slow-query logging on jOOQ for anything >100ms

**Render scaling**:
- Upgrade to Starter plan ($7/mo) — eliminates cold starts
- Consider regional deploy if you have international friends testing
- Set CPU/memory alerts in Render dashboard

**Frontend deployment**:
- Cloudflare Pages with API proxy rules pointing at Render
- Or Render Static Site with proper CORS config on the backend
- HTTPS-only, HSTS header
- WS uses WSS (secure WebSocket) automatically when frontend is HTTPS

**Production secrets management**:
- Audit env vars: nothing sensitive in code, all in Render env vars
- Rotate Supabase JWT secret if it's been in CI logs
- Consider HashiCorp Vault or similar if scope expands beyond this project

**Backups**:
- Supabase free tier doesn't auto-backup; upgrade or set up your own pg_dump cron
- Worth doing before you have data you'd cry about losing — even for a teaching tool, losing all rating history is sad

**Anti-cheat hardening** (if real users):
- Rate-limit move submissions (one per phase per game per player)
- Validate that submitted move types match the current phase
- Reject moves submitted after the phase deadline (clock skew tolerance: 500ms)
- Log unusual patterns (player always submits at exactly N ms before deadline → might be scripted)

**Onboarding flow**:
- First-time user gets a tutorial match against a deterministic AI
- Explanation of the 4-phase structure before first real match
- Glossary of ELO terms accessible from anywhere

### Definition of done

This iteration ends when the project is deployed publicly and a friend you haven't directly walked through the system can play a match and read the post-match screen and understand what happened. That's the bar.

---

## Cross-cutting reminders

- Every iteration ends with a working app you can hand to a friend
- Every iteration has a manual test you actually run
- Migrations stay manual; never re-enable auto-migration
- Render auto-deploy stays off; CI controls the migrate-then-deploy sequence
- ArchUnit rules grow as modules appear; isolation prevents the WS layer from reaching into game internals
- jOOQ regenerates from migrated schema; commit nothing under `build/generated-src/`
- The post-match teaching surface is the unique value of this project — protect time for iteration 6

## Sizing total

- Iterations 1-3: ~2 weeks of evening sessions → playable game
- Iterations 4-5: ~1 week → scalable to multi-user with reconnection
- Iteration 6: ~2 days → teaching surface
- Iteration 7: ongoing

Roughly 3-4 weeks of part-time work to "playable by friends," plus polish.