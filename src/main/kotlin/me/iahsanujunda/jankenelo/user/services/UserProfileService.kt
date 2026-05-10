package me.iahsanujunda.jankenelo.user.services

import java.util.UUID
import me.iahsanujunda.jankenelo.rating.repositories.PlayerRatingRepository
import me.iahsanujunda.jankenelo.user.repositories.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserProfileService(
    private val userRepository: UserRepository,
    private val playerRatingRepositories: PlayerRatingRepository
) {
    @Transactional
    fun ensureProvisioned(userId: UUID) {
        userRepository.createNewUser(userId)
        playerRatingRepositories.createNewPlayerRating(userId)
    }
}