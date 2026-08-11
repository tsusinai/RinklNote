package com.example.rinklnote.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts the JWT with an AES-GCM key held in the Android Keystore.
 * The ciphertext (IV + encrypted bytes, base64) is what actually gets stored,
 * so the token is never written to disk in plaintext. Losing the Keystore key
 * (e.g. device reset) makes existing tokens undecryptable — the user simply
 * re-logs in, which is the intended behavior for a credentials-rotation change.
 */
class TokenCipher {
    companion object {
        private const val KEY_ALIAS = "rinklnote_token_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // GCM IV is random per encryption; prepend it so decryption can recover it.
        return Base64.encodeToString(cipher.iv + cipherBytes, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? {
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            if (all.size < IV_SIZE + TAG_BITS / 8) return null
            val iv = all.copyOfRange(0, IV_SIZE)
            val cipherBytes = all.copyOfRange(IV_SIZE, all.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
