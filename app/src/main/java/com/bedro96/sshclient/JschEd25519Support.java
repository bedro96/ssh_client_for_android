package com.bedro96.sshclient;

import com.jcraft.jsch.Identity;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;

import java.util.Vector;

final class JschEd25519Support {

    private JschEd25519Support() { }

    static void configureJsch() {
        JSch.setConfig("signature.ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519");
        JSch.setConfig("signature.ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448");
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA");
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA");
    }

    static Identity addIdentity(JSch jsch, String identityPath, String passphrase) throws JSchException {
        configureJsch();
        if (passphrase != null && !passphrase.isEmpty()) {
            jsch.addIdentity(identityPath, passphrase);
        } else {
            jsch.addIdentity(identityPath);
        }
        Vector<Identity> identities = jsch.getIdentityRepository().getIdentities();
        return identities.isEmpty() ? null : identities.lastElement();
    }

    static boolean isEncrypted(Identity identity) {
        return identity != null && identity.isEncrypted();
    }
}
