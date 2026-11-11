package com.past9.phoneaos.people

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the AES key comes from: the Android Keystore on the phone, a fixed key in a test. */
interface PersonKey { fun key(): SecretKey }

/**
 * The device's own key, made once and never exportable: AES-256-GCM in the Android Keystore (whose bytes cannot be
 * read out, only used by this app). Deleting the app's data deletes the key with it, so a stolen copy of the database
 * on its own is unreadable.
 */
object KeystorePersonKey : PersonKey {
    private const val ALIAS = "phoneaos-people-v1"
    override fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }
}

/**
 * One layer for the people data: everything about a person is written through [seal] and read back through [open],
 * AES-256-GCM with a fresh random IV each time, the IV in front of the ciphertext and a version tag in front of both.
 * There is no other way in and no plain copy: the tables hold only what this returns.
 */
class PersonCipher(private val keys: PersonKey = KeystorePersonKey) {
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // A new IV on every seal, which is what makes GCM safe to reuse the same key.
        return PREFIX + Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + body, Base64.NO_WRAP)
    }

    fun open(blob: String): String {
        require(blob.startsWith(PREFIX)) { "not a sealed row" }
        val raw = Base64.decode(blob.substring(PREFIX.length), Base64.NO_WRAP)
        require(raw.size > 2) { "sealed row is too short" }
        val ivLen = raw[0].toInt()
        require(ivLen in 12..16 && raw.size > 1 + ivLen) { "sealed row has no usable IV" }
        val iv = raw.copyOfRange(1, 1 + ivLen)
        val body = raw.copyOfRange(1 + ivLen, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        /** The phone's cipher, one key for the whole app. */
        val keystore = PersonCipher(KeystorePersonKey)
    }
}
