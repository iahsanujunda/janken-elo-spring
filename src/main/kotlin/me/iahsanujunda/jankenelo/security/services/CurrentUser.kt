package me.iahsanujunda.jankenelo.security.services

import java.util.UUID
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.core.context.SecurityContextHolder

class CurrentUser {
    fun currentUserId(): UUID {
        val auth = SecurityContextHolder.getContext().authentication
            ?: error("No authentication found")
        val jwt = auth.principal as? Jwt
            ?: error("Principal not a jwt")
        return UUID.fromString(jwt.subject)
    }
}