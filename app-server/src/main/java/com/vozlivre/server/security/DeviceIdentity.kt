package com.vozlivre.server.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Identidade criptográfica do dispositivo administrativo: par de chaves EC P-256 no Android Keystore.
 * A chave privada não é exportável. Não usa IMEI, MAC nem Android ID.
 */
object DeviceIdentity {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "vozlivre_admin_device"

    private fun store(): KeyStore {
        val k = KeyStore.getInstance(PROVIDER)
        k.load(null)
        return k
    }

    fun exists(): Boolean = try { store().containsAlias(ALIAS) } catch (e: Exception) { false }

    /** Gera um par novo (substitui o anterior, invalidando a identidade antiga) e devolve a chave pública. */
    fun create(): ByteArray {
        val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        g.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return g.generateKeyPair().public.encoded
    }

    fun publicKey(): ByteArray? = try { store().getCertificate(ALIAS)?.publicKey?.encoded } catch (e: Exception) { null }

    private fun sign(data: ByteArray): ByteArray {
        val pk = store().getKey(ALIAS, null) as PrivateKey
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(pk)
        s.update(data)
        return s.sign()
    }

    private fun verify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(key)
        s.update(data)
        return s.verify(sig)
    }

    /** Prova que este dispositivo possui a chave registada: assina um desafio aleatório e verifica com a chave pública guardada. */
    fun proves(storedPub: ByteArray): Boolean {
        return try {
            val challenge = ByteArray(32)
            SecureRandom().nextBytes(challenge)
            verify(storedPub, challenge, sign(challenge))
        } catch (e: Exception) { false }
    }
}
