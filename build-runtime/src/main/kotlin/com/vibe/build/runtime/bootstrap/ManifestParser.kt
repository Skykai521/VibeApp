package com.vibe.build.runtime.bootstrap

import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses the bootstrap manifest JSON into a [BootstrapManifest].
 * Throws [ManifestException] for any malformed or version-incompatible input.
 */
@Singleton
open class ManifestParser @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = false     // strict: reject unknown top-level fields
        isLenient = false
        prettyPrint = false
    }

    open fun parse(bytes: ByteArray): BootstrapManifest {
        val manifest = try {
            json.decodeFromString(BootstrapManifest.serializer(), String(bytes, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw ManifestException("Manifest JSON is malformed: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ManifestException("Manifest JSON validation failed: ${e.message}", e)
        }

        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw ManifestException(
                "Unsupported manifest schemaVersion: ${manifest.schemaVersion} " +
                "(expected $SUPPORTED_SCHEMA_VERSION)",
            )
        }
        return manifest
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}
