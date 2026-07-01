package com.bedro96.sshclient;

import com.jcraft.jsch.Identity;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.bc.SignatureEd25519;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class JschEd25519RegressionTest {

    private static final byte[] PAYLOAD = "android-ed25519-regression".getBytes(StandardCharsets.UTF_8);

    private JschEd25519RegressionTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: <unencrypted-key> <encrypted-key> <passphrase>");
        }
        assertBcOverridesAreConfigured();
        assertConfiguredBcCanSignAndVerify(args[0]);
        assertEncryptedKeyNeedsPassphrase(args[1]);
        assertWrongPassphraseLeavesKeyEncrypted(args[1]);
        assertCorrectPassphraseDecryptsKey(args[1], args[2]);
    }

    private static void assertBcOverridesAreConfigured() {
        JschEd25519Support.configureJsch();
        if (!"com.jcraft.jsch.bc.SignatureEd25519".equals(JSch.getConfig("signature.ssh-ed25519"))) {
            throw new AssertionError("Expected BC Ed25519 signature override to be configured");
        }
        if (!"com.jcraft.jsch.bc.KeyPairGenEdDSA".equals(JSch.getConfig("keypairgen_fromprivate.eddsa"))) {
            throw new AssertionError("Expected BC EdDSA keypair parser override to be configured");
        }
    }

    private static void assertConfiguredBcCanSignAndVerify(String identityPath) throws Exception {
        JSch jsch = new JSch();
        Identity identity = JschEd25519Support.addIdentity(jsch, identityPath, null);
        if (JschEd25519Support.isEncrypted(identity)) {
            throw new AssertionError("Unencrypted Ed25519 key should not remain encrypted");
        }
        byte[] signature = identity.getSignature(PAYLOAD);
        if (signature == null || signature.length == 0) {
            throw new AssertionError("Expected a BC-backed Ed25519 signature");
        }
        SignatureEd25519 verifier = new SignatureEd25519();
        verifier.init();
        verifier.setPubKey(extractPublicKey(identity.getPublicKeyBlob()));
        verifier.update(PAYLOAD);
        if (!verifier.verify(signature)) {
            throw new AssertionError("BC-backed Ed25519 signature did not verify");
        }
    }

    private static void assertEncryptedKeyNeedsPassphrase(String identityPath) throws Exception {
        JSch jsch = new JSch();
        Identity identity = JschEd25519Support.addIdentity(jsch, identityPath, null);
        if (!JschEd25519Support.isEncrypted(identity)) {
            throw new AssertionError("Encrypted Ed25519 key should require a passphrase");
        }
    }

    private static void assertWrongPassphraseLeavesKeyEncrypted(String identityPath) throws Exception {
        JSch jsch = new JSch();
        Identity identity = JschEd25519Support.addIdentity(jsch, identityPath, "wrong-passphrase");
        if (!JschEd25519Support.isEncrypted(identity)) {
            throw new AssertionError("Wrong passphrase should not decrypt the identity key");
        }
    }

    private static void assertCorrectPassphraseDecryptsKey(String identityPath, String passphrase)
            throws Exception {
        JSch jsch = new JSch();
        Identity identity = JschEd25519Support.addIdentity(jsch, identityPath, passphrase);
        if (JschEd25519Support.isEncrypted(identity)) {
            throw new AssertionError("Correct passphrase should decrypt the identity key");
        }
    }

    private static byte[] extractPublicKey(byte[] publicKeyBlob) {
        int algorithmLength = readUint32(publicKeyBlob, 0);
        String algorithm = new String(publicKeyBlob, 4, algorithmLength, StandardCharsets.UTF_8);
        if (!"ssh-ed25519".equals(algorithm)) {
            throw new AssertionError("Unexpected public key algorithm: " + algorithm);
        }
        int keyLengthOffset = 4 + algorithmLength;
        int keyLength = readUint32(publicKeyBlob, keyLengthOffset);
        return Arrays.copyOfRange(publicKeyBlob, keyLengthOffset + 4, keyLengthOffset + 4 + keyLength);
    }

    private static int readUint32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }
}
