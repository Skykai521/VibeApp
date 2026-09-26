package com.vibe.app.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class NetworkClient @Inject constructor(
    private val httpEngine: HttpClientEngine
) {

    private val client by lazy {
        HttpClient(httpEngine) {
            expectSuccess = false

            install(ContentNegotiation) {
                json(json)
            }

            install(SSE)

            install(HttpTimeout) {
                // Fail fast on unreachable endpoints.
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                // SSE liveness: if no bytes arrive for this long, the stream is dead.
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                // No overall request timeout — streaming responses legitimately run for many minutes.
                requestTimeoutMillis = null
            }

            install(DefaultRequest) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
            }
        }
    }

    operator fun invoke(): HttpClient = client

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SOCKET_TIMEOUT_MS = 120_000L

        // Default JSON config (used for most APIs)
        val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            allowSpecialFloatingPointValues = true
            useArrayPolymorphism = false
            encodeDefaults = true
            explicitNulls = false
        }

        // OpenAI-specific JSON config with "type" discriminator for MessageContent
        val openAIJson = Json {
            isLenient = true
            ignoreUnknownKeys = true
            allowSpecialFloatingPointValues = true
            useArrayPolymorphism = false
            classDiscriminator = "type"
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
