package com.vozlivre.core

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM com chave derivada de uma senha partilhada (PBKDF2-SHA256).
 * Formato de cada mensagem: [nonce:12][texto cifrado + etiqueta:16].
 * Dois telemóveis só se entendem se tiverem a mesma senha.
 */
object Secure {
    private const val DEFAULT_PASSWORD = "vozlivre"
    private val rnd = SecureRandom()
    @Volatile private var key: SecretKeySpec? = null

    fun setPassword(password: String) {
        val p = password.ifBlank { DEFAULT_PASSWORD }
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(p.toCharArray(), "vozlivre-v1".toByteArray(Charsets.UTF_8), 20000, 256)
        key = SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    fun encrypt(data: ByteArray, len: Int): ByteArray {
        val k = key ?: throw IllegalStateException("Senha não definida")
        val nonce = ByteArray(12)
        rnd.nextBytes(nonce)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce))
        val ct = c.doFinal(data, 0, len)
        val out = ByteArray(12 + ct.size)
        System.arraycopy(nonce, 0, out, 0, 12)
        System.arraycopy(ct, 0, out, 12, ct.size)
        return out
    }

    /** Devolve null se a senha for diferente ou a mensagem tiver sido alterada. */
    fun decrypt(data: ByteArray): ByteArray? {
        val k = key ?: return null
        if (data.size < 12 + 16) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, data, 0, 12))
            c.doFinal(data, 12, data.size - 12)
        } catch (e: Exception) { null }
    }

    fun encryptText(text: String): String {
        val b = text.toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(encrypt(b, b.size), Base64.NO_WRAP)
    }

    fun decryptText(text: String): String? {
        val raw = try { Base64.decode(text, Base64.NO_WRAP) } catch (e: Exception) { return null }
        val plain = decrypt(raw) ?: return null
        return String(plain, Charsets.UTF_8)
    }
}
