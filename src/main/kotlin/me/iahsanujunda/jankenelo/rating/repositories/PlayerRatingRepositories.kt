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
}