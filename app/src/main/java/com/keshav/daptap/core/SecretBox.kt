package com.keshav.daptap.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM sealing shared with the web page (WebCrypto). Wire format:
 * base64url( iv[12] || ciphertext || tag[16] ), AAD = "daptap1".
 */
object SecretBox {
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val AAD = "daptap1".toByteArray()
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun seal(key: ByteArray, plaintext: ByteArray): String {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return Base64Url.encode(iv + cipher.doFinal(plaintext))
    }

    /** Returns null for anything that isn't a valid box for this key (tampered, wrong key, junk). */
    fun open(key: ByteArray, box: String): ByteArray? = runCatching {
        val raw = Base64Url.decode(box)
        if (raw.size < IV_LEN + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN))
        cipher.updateAAD(AAD)
        cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN)
    }.getOrNull()
}
