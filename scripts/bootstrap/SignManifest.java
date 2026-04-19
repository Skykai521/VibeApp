/*
 * Ed25519 signer for the bootstrap manifest.
 *
 * Uses JDK 15+'s built-in Ed25519 (no external dependencies, no kotlin
 * CLI). Invoked as a single-file source program (JEP 330):
 *
 *     java scripts/bootstrap/SignManifest.java <path/to/manifest.json>
 *
 * Reads the 32-byte raw seed (hex-encoded, 64 chars) from
 * ~/.vibeapp/dev-bootstrap-privseed.hex and writes a 64-byte raw
 * signature to <manifest>.sig. Signature format matches
 * net.i2p.crypto:eddsa's output (RFC 8032 Ed25519), which is what
 * ManifestSignature.verify() on device consumes.
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;

public final class SignManifest {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: java SignManifest.java <path/to/manifest.json>");
            System.exit(2);
        }

        Path manifest = Paths.get(args[0]);
        if (!Files.isRegularFile(manifest)) {
            die("manifest not found: " + manifest.toAbsolutePath());
        }

        Path seedFile = Paths.get(
            System.getProperty("user.home"),
            ".vibeapp",
            "dev-bootstrap-privseed.hex"
        );
        if (!Files.isRegularFile(seedFile)) {
            die("dev seed not found at " + seedFile
                + "; see docs/bootstrap/dev-keypair-setup.md");
        }

        String seedHex = Files.readString(seedFile).trim();
        if (seedHex.length() != 64) {
            die("dev seed must be 64 hex chars, got " + seedHex.length());
        }
        byte[] seed = hexDecode(seedHex);

        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        NamedParameterSpec paramSpec = NamedParameterSpec.ED25519;
        EdECPrivateKeySpec privateSpec = new EdECPrivateKeySpec(paramSpec, seed);
        EdECPrivateKey privateKey = (EdECPrivateKey) keyFactory.generatePrivate(privateSpec);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(Files.readAllBytes(manifest));
        byte[] sig = signer.sign();

        Path sigFile = manifest.resolveSibling(manifest.getFileName() + ".sig");
        Files.write(sigFile, sig);
        System.out.println("wrote " + sigFile + " (" + sig.length + " bytes)");
    }

    private static byte[] hexDecode(String s) {
        if ((s.length() & 1) != 0) die("hex string must have even length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) die("non-hex char at position " + (i * 2));
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static void die(String msg) {
        System.err.println("sign-manifest: " + msg);
        System.exit(2);
    }

    private SignManifest() {}
}
