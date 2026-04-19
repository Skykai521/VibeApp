package com.vibe.gradle.host

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON-line IPC between VibeApp and GradleHost.
 *
 * Wire format: each request + each event is one JSON object on one
 * line, \n-terminated. kotlinx.serialization emits compact JSON
 * (no whitespace), so the framing is unambiguous.
 *
 * Mirrored in `:build-gradle/.../IpcProtocol.kt` on the app side.
 * Changes to one side MUST be mirrored in the other — the tests in
 * both modules assert byte-equivalent serialization.
 */

@Serializable
sealed class HostRequest {
    abstract val requestId: String

    @Serializable
    @SerialName("Ping")
    data class Ping(override val requestId: String) : HostRequest()

    @Serializable
    @SerialName("RunBuild")
    data class RunBuild(
        override val requestId: String,
        val projectPath: String,
        val tasks: List<String>,
        val args: List<String> = emptyList(),
    ) : HostRequest()

    @Serializable
    @SerialName("Shutdown")
    data class Shutdown(override val requestId: String) : HostRequest()
}

@Serializable
sealed class HostEvent {
    abstract val requestId: String

    @Serializable
    @SerialName("Ready")
    data class Ready(
        override val requestId: String,
        val hostVersion: String,
        val toolingApiVersion: String,
    ) : HostEvent()

    @Serializable
    @SerialName("Pong")
    data class Pong(override val requestId: String) : HostEvent()

    @Serializable
    @SerialName("BuildStart")
    data class BuildStart(override val requestId: String, val ts: Long) : HostEvent()

    @Serializable
    @SerialName("BuildProgress")
    data class BuildProgress(
        override val requestId: String,
        val message: String,
    ) : HostEvent()

    @Serializable
    @SerialName("BuildFinish")
    data class BuildFinish(
        override val requestId: String,
        val success: Boolean,
        val durationMs: Long,
        val failureSummary: String?,
    ) : HostEvent()

    @Serializable
    @SerialName("Log")
    data class Log(
        override val requestId: String,
        val level: String,      // "LIFECYCLE" | "INFO" | "DEBUG" | "WARN" | "ERROR"
        val text: String,
    ) : HostEvent()

    @Serializable
    @SerialName("Error")
    data class Error(
        override val requestId: String,
        val exceptionClass: String,
        val message: String,
    ) : HostEvent()
}

object IpcProtocol {
    val json: Json = Json {
        encodeDefaults = false
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    fun encodeRequest(request: HostRequest): String =
        json.encodeToString(HostRequest.serializer(), request)

    fun decodeRequest(line: String): HostRequest =
        json.decodeFromString(HostRequest.serializer(), line)

    fun encodeEvent(event: HostEvent): String =
        json.encodeToString(HostEvent.serializer(), event)

    fun decodeEvent(line: String): HostEvent =
        json.decodeFromString(HostEvent.serializer(), line)
}
