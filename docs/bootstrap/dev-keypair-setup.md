# Dev Ed25519 Keypair for Bootstrap Manifest Signing

The bootstrap subsystem verifies a signed manifest file before trusting
any on-device download. The public key is embedded in the APK
(`BuildRuntimeModule.BOOTSTRAP_PUBKEY_HEX`); the private key is
**never** in the repo.

## One-time key ceremony (dev cycle)

1. Run a throwaway generator test to produce `seed` and `pubkey`:
   ```kotlin
   // build-runtime/src/test/kotlin/.../GenerateDevKeypairRunOnce.kt
   // (Create locally, run, delete. Never commit.)
   val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
   val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
   val privateKey = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
   println("pub=${privateKey.abyte.joinToString("") { "%02x".format(it) }}")
   println("seed=${seed.joinToString("") { "%02x".format(it) }}")
   ```
2. Save the seed (private key) to `~/.vibeapp/dev-bootstrap-privseed.hex`
   with `chmod 600`. Do NOT add it to the repo.
3. Paste the public key hex into
   `app/src/main/kotlin/com/vibe/app/di/BuildRuntimeModule.kt` as
   `BOOTSTRAP_PUBKEY_HEX`.
4. Delete the throwaway generator test.

## Signing a test manifest

To sign a synthetic manifest for the Phase 1c end-to-end instrumented
test or for local developer use, construct an `EdDSAPrivateKey` from the
seed and sign raw manifest JSON bytes:

```kotlin
val seed = File(System.getProperty("user.home"), ".vibeapp/dev-bootstrap-privseed.hex")
    .readText().trim().hexToByteArray()
val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
val engine = EdDSAEngine().apply { initSign(priv) }
engine.update(manifestBytes)
val sig = engine.sign()   // 64 bytes
```

The instrumented test in Task 4 follows this pattern.

## Production (Phase 1d / release ceremony)

v2.0 release will:
- Generate a NEW keypair on an air-gapped machine for production.
- Keep that private key in the release engineer's hardware security module.
- Update `BOOTSTRAP_PUBKEY_HEX` to the production public key.
- Build signed manifests offline and upload to GitHub Release.

The dev key is for internal testing only and MUST NOT be used for
production artifact signing.
