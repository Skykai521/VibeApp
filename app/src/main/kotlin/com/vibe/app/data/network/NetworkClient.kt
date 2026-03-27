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
                requestTimeoutMillis = TIMEOUT.toLong()
            }

            install(DefaultRequest) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
            }
        }
    }

    operator fun invoke(): HttpClient = client

    companion object {
        private const val TIMEOUT = 1_000 * 60 * 5

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
