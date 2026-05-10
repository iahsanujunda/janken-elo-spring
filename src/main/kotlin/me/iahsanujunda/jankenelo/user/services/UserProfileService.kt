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

    fun getUserProfile(userId: UUID): ProfileResponse {
        ensureProvisioned(userId)
        val rating = playerRatingRepositories.getRatingByUserId(userId)
        return ProfileResponse(
            userId = userId,
            rating = rating.rating,
            gamesPlayed = rating.gamesPlayed,
            peakRating = rating.peakRating,
        )
    }
}

data class ProfileResponse(
    val userId: UUID,
    val rating: Int,
    val gamesPlayed: Int,
    val peakRating: Int
)