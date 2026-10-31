package com.vozlivre.server.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chaves guardadas no Android Keystore: uma AES-256-GCM para cifrar o estado administrativo
 * em repouso e uma HMAC-SHA256 para selar a cadeia de auditoria. As chaves não saem do Keystore.
 */
object KeystoreCrypto {
    private const val PROVIDER = "AndroidKeyStore"
    private const val AES_ALIAS = "vozlivre_server_secrets"
    private const val HMAC_ALIAS = "vozlivre_server_audit"

    private fun store(): KeyStore {
        val k = KeyStore.getInstance(PROVIDER)
        k.load(null)
        return k
    }

    private fun aesKey(): SecretKey {
        val existing = store().getKey(AES_ALIAS, null)
        if (existing is SecretKey) return existing
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        g.init(
            KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    private fun hmacKey(): SecretKey {
        val existing = store().getKey(HMAC_ALIAS, null)
        if (existing is SecretKey) return existing
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        g.init(KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return g.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, aesKey())
        val iv = c.iv
        val ct = c.doFinal(plain)
        return iv + ct
    }

    fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    fun hmac(data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(hmacKey())
        return m.doFinal(data)
    }
}
