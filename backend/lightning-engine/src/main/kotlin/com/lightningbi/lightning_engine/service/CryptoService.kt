package com.lightningbi.lightning_engine.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
class CryptoService(
    @Value("\${lightningbi.security.source-encryption-key}") private val base64Key: String
) {
    private val secretKey = SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES")
    private val gcmTagLength = 128
    private val ivLength = 12

    fun encrypt(plainText: String): String {
        val iv = ByteArray(ivLength).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(gcmTagLength, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(cipherText: String): String {
        val combined = Base64.getDecoder().decode(cipherText)
        val iv = combined.copyOfRange(0, ivLength)
        val encrypted = combined.copyOfRange(ivLength, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(gcmTagLength, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}