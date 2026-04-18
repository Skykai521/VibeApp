package com.vibe.build.runtime.bootstrap

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import javax.inject.Inject

/**
 * Verifies Ed25519 detached signatures over the raw manifest bytes.
 *
 * [publicKeyHex] is the 32-byte Ed25519 public key, hex-encoded lowercase.
 * In production this is injected from `BuildConfig.BOOTSTRAP_PUBKEY_HEX`
 * (set by the VibeApp Gradle build). Tests pass their own key.
 */
class ManifestSignature @Inject constructor(
    private val publicKeyHex: String,
) {
    private val publicKey: EdDSAPublicKey

    init {
        require(publicKeyHex.length == 64) {
            "Ed25519 pubkey must be 32 bytes (64 hex chars), got ${publicKeyHex.length}"
        }
        val raw = publicKeyHex.hexToByteArrayLower()
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        publicKey = EdDSAPublicKey(EdDSAPublicKeySpec(raw, spec))
    }

    /**
     * Verifies that [signature] is a valid Ed25519 signature over [manifestBytes],
     * using the injected public key.
     *
     * Returns `false` (rather than throwing) for any verification failure
     * including malformed signatures — callers should not leak signature
     * failure details.
     */
    fun verify(manifestBytes: ByteArray, signature: ByteArray): Boolean {
        return try {
            val engine = EdDSAEngine().apply { initVerify(publicKey) }
            engine.update(manifestBytes, 0, manifestBytes.size)
            engine.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun String.hexToByteArrayLower(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { i ->
            val hi = Character.digit(this[i * 2], 16)
            val lo = Character.digit(this[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "non-hex char at position ${i * 2}" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
