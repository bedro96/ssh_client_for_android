package com.bedro96.sshclient;

import com.jcraft.jsch.JSch;

/**
 * SSH public-key authentication configuration helper.
 *
 * <p>Pure Java (no Android dependencies) so it can be unit-tested on a plain
 * JVM. It exists to make Ed25519 identity keys ({@code id_ed25519}) usable on
 * Android.
 *
 * <p><b>Why this is needed.</b> The bundled {@code com.github.mwiede:jsch}
 * ships working Ed25519 crypto only in its multi-release classes under
 * {@code META-INF/versions/15/} (the JDK 15+ native EdDSA provider). Android's
 * dex packaging ignores {@code META-INF/versions/}, so on-device jsch falls
 * back to {@code com.jcraft.jsch.jce.SignatureEd25519}, whose constructor
 * throws {@code UnsupportedOperationException("SignatureEd25519 requires
 * Java15+.")}. The visible symptom is {@code JSchException: Auth fail for
 * methods 'publickey'} even for a valid key that works with the OpenSSH CLI.
 *
 * <p>jsch also bundles a Bouncy Castle implementation
 * ({@code com.jcraft.jsch.bc.*}) that uses BouncyCastle's low-level crypto API
 * directly (no JCA provider registration). Pointing jsch at those classes,
 * with a BouncyCastle provider jar on the classpath, makes Ed25519 work on
 * Android.
 */
final class SshKeyAuth {

    static final String CFG_SIG_ED25519 = "signature.ssh-ed25519";
    static final String CFG_SIG_ED448 = "signature.ssh-ed448";
    static final String CFG_KEYPAIRGEN_EDDSA = "keypairgen.eddsa";
    static final String CFG_KEYPAIRGEN_FROMPRIVATE_EDDSA = "keypairgen_fromprivate.eddsa";

    static final String BC_SIG_ED25519 = "com.jcraft.jsch.bc.SignatureEd25519";
    static final String BC_SIG_ED448 = "com.jcraft.jsch.bc.SignatureEd448";
    static final String BC_KEYPAIRGEN_EDDSA = "com.jcraft.jsch.bc.KeyPairGenEdDSA";

    private SshKeyAuth() { }

    /**
     * Registers the Bouncy Castle EdDSA implementations as the jsch defaults so
     * Ed25519/Ed448 identity keys work on Android. Safe to call more than once
     * (jsch config is global and idempotent for these keys).
     */
    static void configureEdDSAForAndroid() {
        JSch.setConfig(CFG_SIG_ED25519, BC_SIG_ED25519);
        JSch.setConfig(CFG_SIG_ED448, BC_SIG_ED448);
        JSch.setConfig(CFG_KEYPAIRGEN_EDDSA, BC_KEYPAIRGEN_EDDSA);
        JSch.setConfig(CFG_KEYPAIRGEN_FROMPRIVATE_EDDSA, BC_KEYPAIRGEN_EDDSA);
    }
}
