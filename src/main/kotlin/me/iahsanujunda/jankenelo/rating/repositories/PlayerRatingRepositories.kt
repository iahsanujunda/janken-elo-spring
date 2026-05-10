package me.iahsanujunda.jankenelo.rating.repositories

import java.util.UUID
import me.iahsanujunda.jankenelo.jooq.tables.references.PLAYER_RATINGS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

@Repository
class PlayerRatingRepository(private val dsl: DSLContext) {
    fun createNewPlayerRating(userId: UUID){
        dsl.insertInto(PLAYER_RATINGS)
            .set(PLAYER_RATINGS.USER_ID, userId)
            .onConflictDoNothing()
            .execute()
    }

    fun getRatingByUserId(userId: UUID): PlayerRating {
        val record = dsl.select(
            PLAYER_RATINGS.RATING,
            PLAYER_RATINGS.GAMES_PLAYED,
            PLAYER_RATINGS.PEAK_RATING
        )
            .from(PLAYER_RATINGS)
            .where(PLAYER_RATINGS.USER_ID.eq(userId))
            .fetchOne()
            ?: error("No rating exists for $userId")

        return PlayerRating(
            userId = userId,
            rating = requireNotNull(record[PLAYER_RATINGS.RATING]),
            gamesPlayed = requireNotNull(record[PLAYER_RATINGS.GAMES_PLAYED]),
            peakRating = requireNotNull(record[PLAYER_RATINGS.PEAK_RATING])
        )
    }
}

data class PlayerRating(
    val userId: UUID,
    val rating: Int,
    val gamesPlayed: Int,
    val peakRating: Int
)