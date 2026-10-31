package com.vozlivre.server.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 com salt individual. O número de iterações fica guardado junto de cada hash. */
object PasswordHasher {
    const val ITERATIONS = 310_000
    private val rnd = SecureRandom()
    private const val SAFE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    private val COMMON = setOf(
        "password", "12345678", "123456789", "1234567890", "qwertyuiop", "admin", "administrador",
        "vozlivre", "senha123", "mocambique", "moçambique", "nampula", "iloveyou"
    )

    fun newSalt(): ByteArray {
        val b = ByteArray(16)
        rnd.nextBytes(b)
        return b
    }

    fun hash(secret: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** Devolve o problema da senha, ou null se for aceitável. */
    fun strengthProblem(password: String, username: String): String? {
        if (password.length < 10) return "A senha precisa de pelo menos 10 caracteres."
        val classes = listOf(
            password.any { it.isLowerCase() }, password.any { it.isUpperCase() },
            password.any { it.isDigit() }, password.any { !it.isLetterOrDigit() }
        ).count { it }
        if (classes < 3) return "Usa pelo menos três tipos: minúsculas, maiúsculas, números e símbolos."
        val lower = password.lowercase()
        if (COMMON.any { lower.contains(it) }) return "Evita palavras comuns na senha."
        if (username.length >= 3 && lower.contains(username.lowercase())) return "A senha não pode conter o nome de utilizador."
        return null
    }

    /** Senha inicial individual, sem caracteres ambíguos. */
    fun randomPassword(length: Int = 12): String {
        val sb = StringBuilder()
        // garante os três tipos pedidos pela regra de força
        sb.append("ABCDEFGHJKLMNPQRSTUVWXYZ"[rnd.nextInt(24)])
        sb.append("abcdefghijkmnpqrstuvwxyz"[rnd.nextInt(24)])
        sb.append("23456789"[rnd.nextInt(8)])
        while (sb.length < length) sb.append(SAFE_ALPHABET[rnd.nextInt(SAFE_ALPHABET.length)])
        val chars = sb.toString().toCharArray()
        for (i in chars.indices.reversed()) {
            val j = rnd.nextInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        return String(chars)
    }
}

/** Chave de recuperação de 160 bits em Base32, mostrada uma única vez. */
object RecoveryKey {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val rnd = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(20)
        rnd.nextBytes(bytes)
        return encode(bytes).chunked(4).joinToString("-")
    }

    fun normalize(input: String): String = input.uppercase().filter { it in ALPHABET }

    private fun encode(bytes: ByteArray): String {
        var buffer = 0
        var bits = 0
        val sb = StringBuilder()
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}
