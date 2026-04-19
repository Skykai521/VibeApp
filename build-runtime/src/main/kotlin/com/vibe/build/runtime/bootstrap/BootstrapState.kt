package com.vibe.build.runtime.bootstrap

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bootstrap state machine (design doc §3.4).
 * Every state carries the info needed to recover on app restart:
 * - `Downloading` remembers which component + byte progress
 * - `Verifying`/`Unpacking`/`Installing` remember which component
 * - `Failed`/`Corrupted` carry a human-readable reason for UI display
 */
@Serializable
sealed class BootstrapState {

    @Serializable
    object NotInstalled : BootstrapState()

    @Serializable
    data class Downloading(
        val componentId: String,
        val bytesRead: Long,
        val totalBytes: Long,
    ) : BootstrapState() {
        val percent: Int
            get() = if (totalBytes <= 0) 0 else ((bytesRead * 100) / totalBytes).toInt()
    }

    @Serializable
    data class Verifying(val componentId: String) : BootstrapState()

    @Serializable
    data class Unpacking(val componentId: String) : BootstrapState()

    @Serializable
    data class Installing(val componentId: String) : BootstrapState()

    @Serializable
    data class Ready(val manifestVersion: String) : BootstrapState()

    @Serializable
    data class Failed(val reason: String) : BootstrapState()

    @Serializable
    data class Corrupted(val reason: String) : BootstrapState()
}

/**
 * JSON encoding for [BootstrapState]. Exposed separately so tests can
 * check roundtrip without touching DataStore.
 */
object BootstrapStateJson {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encode(state: BootstrapState): String = json.encodeToString(BootstrapState.serializer(), state)

    /**
     * Returns [BootstrapState.NotInstalled] on any decode failure — a
     * corrupt state store should NOT brick the app, it should just
     * force a re-bootstrap.
     */
    fun decode(serialized: String): BootstrapState {
        if (serialized.isBlank()) return BootstrapState.NotInstalled
        return try {
            json.decodeFromString(BootstrapState.serializer(), serialized)
        } catch (_: Exception) {
            BootstrapState.NotInstalled
        }
    }
}
