package com.bedro96.sshclient;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.bc.SignatureEd25519;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Offline JVM test for Ed25519 identity-key support on Android.
 *
 * <p>No JUnit is available in this Gradle-less project, so this is a plain
 * {@code main} test that throws (non-zero exit) on the first failed assertion
 * and prints {@code ALL TESTS PASSED} on success.
 *
 * <p>Run via {@code ./run-tests.sh}. It deliberately does NOT rely on the
 * {@code META-INF/versions/15/} multi-release classes, so it exercises exactly
 * the Bouncy Castle code path that Android uses.
 */
public final class SshKeyAuthTest {

    public static void main(String[] args) throws Exception {
        testConfigRoutesEdDSAToBouncyCastle();
        testBcEd25519SignVerifyRoundTrip();
        System.out.println("ALL TESTS PASSED");
    }

    /** The helper must register the Bouncy Castle EdDSA classes as jsch defaults. */
    private static void testConfigRoutesEdDSAToBouncyCastle() {
        SshKeyAuth.configureEdDSAForAndroid();
        assertEquals(SshKeyAuth.BC_SIG_ED25519,
                JSch.getConfig(SshKeyAuth.CFG_SIG_ED25519), "signature.ssh-ed25519");
        assertEquals(SshKeyAuth.BC_SIG_ED448,
                JSch.getConfig(SshKeyAuth.CFG_SIG_ED448), "signature.ssh-ed448");
        assertEquals(SshKeyAuth.BC_KEYPAIRGEN_EDDSA,
                JSch.getConfig(SshKeyAuth.CFG_KEYPAIRGEN_EDDSA), "keypairgen.eddsa");
        assertEquals(SshKeyAuth.BC_KEYPAIRGEN_EDDSA,
                JSch.getConfig(SshKeyAuth.CFG_KEYPAIRGEN_FROMPRIVATE_EDDSA),
                "keypairgen_fromprivate.eddsa");
        System.out.println("[pass] EdDSA config routes to Bouncy Castle");
    }

    /**
     * Sign with the Bouncy Castle jsch Ed25519 signer using a raw private key —
     * the exact code path the app uses after importing an identity file
     * ({@code setPrvKey} wraps raw 32-byte Ed25519 parameters). The resulting
     * signature is verified independently with BouncyCastle to prove jsch's
     * {@code bc} signer, backed by the bundled BouncyCastle provider, produces
     * cryptographically valid Ed25519 signatures. This is the crypto that fails
     * on Android without a bundled BouncyCastle provider — it must succeed here.
     */
    private static void testBcEd25519SignVerifyRoundTrip() throws Exception {
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(new SecureRandom());
        Ed25519PublicKeyParameters pubParams = priv.generatePublicKey();
        byte[] prv = priv.getEncoded();
        assertTrue(prv != null && prv.length == 32, "raw private key is 32 bytes");

        byte[] message = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

        SignatureEd25519 signer = new SignatureEd25519();
        signer.init();
        signer.setPrvKey(prv);
        signer.update(message);
        byte[] sig = signer.sign();
        assertTrue(sig != null && sig.length == 64, "Ed25519 signature is 64 bytes");

        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, pubParams);
        verifier.update(message, 0, message.length);
        assertTrue(verifier.verifySignature(sig), "jsch bc signature verifies");

        byte[] tampered = "tampered message".getBytes(StandardCharsets.UTF_8);
        Ed25519Signer tamperCheck = new Ed25519Signer();
        tamperCheck.init(false, pubParams);
        tamperCheck.update(tampered, 0, tampered.length);
        assertTrue(!tamperCheck.verifySignature(sig), "tampered message must fail verification");

        System.out.println("[pass] Bouncy Castle Ed25519 sign/verify round-trip");
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    "FAILED " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError("FAILED " + what);
        }
    }
}
