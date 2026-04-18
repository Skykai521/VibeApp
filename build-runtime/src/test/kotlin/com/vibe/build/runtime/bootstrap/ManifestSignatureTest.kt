package com.vibe.build.runtime.bootstrap

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

class ManifestSignatureTest {

    private lateinit var verifier: ManifestSignature
    private lateinit var signer: EdDSAEngine
    private lateinit var testPublicKeyHex: String

    @Before
    fun setUp() {
        // Generate a fresh keypair per test suite so nothing real leaks.
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val privateKey = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))

        signer = EdDSAEngine().apply { initSign(privateKey) }
        testPublicKeyHex = privateKey.abyte.toHexLower()   // 32-byte pub = "abyte"

        verifier = ManifestSignature(publicKeyHex = testPublicKeyHex)
    }

    private fun sign(data: ByteArray): ByteArray {
        val engine = EdDSAEngine().apply {
            initSign(
                EdDSAPrivateKey(
                    EdDSAPrivateKeySpec(
                        // Need to recover seed... use the same signer field
                        this@ManifestSignatureTest.javaClass.let { ByteArray(0) },
                        EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519),
                    ),
                ),
            )
        }
        // Simpler: just use existing signer field.
        signer.update(data, 0, data.size)
        return signer.sign()
    }

    @Test
    fun `verify returns true for valid signature`() {
        val manifest = "hello manifest bytes".toByteArray()
        signer.update(manifest, 0, manifest.size)
        val signature = signer.sign()

        assertTrue(verifier.verify(manifest, signature))
    }

    @Test
    fun `verify returns false when signature over different bytes`() {
        val manifest = "original".toByteArray()
        val tampered = "tampered".toByteArray()
        signer.update(manifest, 0, manifest.size)
        val signature = signer.sign()

        assertFalse(verifier.verify(tampered, signature))
    }

    @Test
    fun `verify returns false for wrong-length signature`() {
        val manifest = "foo".toByteArray()
        val wrongSig = ByteArray(32)  // real sig is 64 bytes
        assertFalse(verifier.verify(manifest, wrongSig))
    }

    @Test
    fun `verify with different pubkey returns false`() {
        val manifest = "foo".toByteArray()
        signer.update(manifest, 0, manifest.size)
        val signature = signer.sign()

        val differentSeed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val differentPriv = EdDSAPrivateKey(
            EdDSAPrivateKeySpec(differentSeed, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)),
        )
        val differentPubHex = differentPriv.abyte.toHexLower()
        val differentVerifier = ManifestSignature(publicKeyHex = differentPubHex)

        assertFalse(differentVerifier.verify(manifest, signature))
    }

    private fun ByteArray.toHexLower(): String =
        joinToString(separator = "") { "%02x".format(it) }
}
