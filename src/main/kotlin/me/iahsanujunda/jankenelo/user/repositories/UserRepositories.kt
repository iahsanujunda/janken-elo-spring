package me.iahsanujunda.jankenelo.user.repositories

import java.util.UUID
import me.iahsanujunda.jankenelo.jooq.tables.references.USERS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

@Repository
class UserRepository(private val dsl: DSLContext) {
    fun createNewUser(userId: UUID) {
        dsl.insertInto(USERS)
            .set(USERS.ID, userId)
            .onConflictDoNothing()
            .execute()
    }
}