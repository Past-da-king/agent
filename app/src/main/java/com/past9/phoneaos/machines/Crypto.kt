package com.past9.phoneaos.machines

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.SecureRandom
import java.security.Security
import java.util.Base64

object Crypto {
    @Volatile private var installed = false
    /** Android ships a cut-down "BC" provider; SSH needs the full one (curve25519, ed25519). */
    @Synchronized fun install() {
        if (installed) return
        Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1); installed = true
    }

    /** A fresh ed25519 key: (OpenSSH private key, one-line public key for authorized_keys). */
    fun newKey(comment: String = "agent-phone"): Pair<String, String> {
        val gen = Ed25519KeyPairGenerator(); gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val body = Base64.getEncoder().encodeToString(OpenSSHPrivateKeyUtil.encodePrivateKey(kp.private)).chunked(70).joinToString("\n")
        val priv = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
        val pub = "ssh-ed25519 " + Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(kp.public)) + " $comment"
        return priv to pub
    }
}
