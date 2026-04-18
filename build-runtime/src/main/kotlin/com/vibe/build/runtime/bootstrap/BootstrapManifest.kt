package com.vibe.build.runtime.bootstrap

import kotlinx.serialization.Serializable

/**
 * Top-level bootstrap manifest. Fetched from GitHub Release + signed with
 * Ed25519; see [ManifestSignature].
 *
 * JSON schema version 1:
 * ```
 * {
 *   "schemaVersion": 1,
 *   "manifestVersion": "v2.0.0",
 *   "components": [ ... ]
 * }
 * ```
 */
@Serializable
data class BootstrapManifest(
    val schemaVersion: Int,
    val manifestVersion: String,
    val components: List<BootstrapComponent>,
) {
    /**
     * Look up the artifact for a given component id + ABI.
     * For ABI-independent components (e.g. `gradle-9.3.1`), artifacts is
     * expected to contain a single key `"common"`; that entry is returned
     * regardless of the requested [abi].
     */
    fun findArtifact(componentId: String, abi: Abi): ArchArtifact? {
        val component = components.firstOrNull { it.id == componentId } ?: return null
        return component.artifacts[abi.abiId]
            ?: component.artifacts["common"]
    }
}

@Serializable
data class BootstrapComponent(
    val id: String,
    val version: String,
    val artifacts: Map<String, ArchArtifact>,   // key = abi id OR "common"
)

@Serializable
data class ArchArtifact(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,       // hex-encoded lowercase
)
