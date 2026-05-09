package me.iahsanujunda.jankenelo.security.services

import java.util.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
@ConditionalOnProperty(name = ["supabase.service-key"], matchIfMissing = false)
@Suppress("SpringJavaInjectionPointsAutowiringInspection")
class SupabaseAuthService (
    @Value("\${supabase.url}") supabaseUrl: String,
    @Value("\${supabase.service-key}") serviceKey: String,
    restClientBuilder: RestClient.Builder,
) {
    private val restClient: RestClient = restClientBuilder
        .baseUrl(supabaseUrl)
        .defaultHeader("Authorization", "Bearer $serviceKey")
        .defaultHeader("apiKey", serviceKey)
        .build()

    @Suppress("UNCHECKED_CAST")
    fun getUserById(userId: UUID): Map<String, Any>? {
        return try {
            restClient.get()
                .uri("/auth/v1/admin/users/{userId}", userId)
                .retrieve()
                .onStatus({ it.isError }) { _, _ -> }
                .body(Map::class.java) as Map<String, Any>?
        } catch (_: Exception) {
            null
        }
    }
}