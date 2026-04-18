@file:DependsOn("net.i2p.crypto:eddsa:0.3.0")

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.io.File

/**
 * Signs manifest.json with the dev Ed25519 seed at
 * ~/.vibeapp/dev-bootstrap-privseed.hex. Writes manifest.json.sig next
 * to the input file.
 *
 * Usage: kotlin sign-manifest.kts <path/to/manifest.json>
 */

fun die(msg: String): Nothing {
    System.err.println("sign-manifest: $msg")
    kotlin.system.exitProcess(2)
}

val argv = args
if (argv.size != 1) die("usage: sign-manifest.kts <manifest.json>")

val manifest = File(argv[0])
if (!manifest.isFile) die("manifest not found: ${manifest.absolutePath}")

val seedFile = File(System.getProperty("user.home"), ".vibeapp/dev-bootstrap-privseed.hex")
if (!seedFile.isFile) die("dev seed not found at $seedFile; see docs/bootstrap/dev-keypair-setup.md")

val seedHex = seedFile.readText().trim()
require(seedHex.length == 64) { "dev seed must be 64 hex chars, got ${seedHex.length}" }
val seed = ByteArray(32) { i ->
    ((Character.digit(seedHex[i * 2], 16) shl 4) or Character.digit(seedHex[i * 2 + 1], 16)).toByte()
}

val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
val sig = EdDSAEngine().apply { initSign(priv) }.run {
    update(manifest.readBytes())
    sign()
}

val sigFile = File(manifest.parentFile, "${manifest.name}.sig")
sigFile.writeBytes(sig)
println("wrote ${sigFile.absolutePath} (${sig.size} bytes)")
