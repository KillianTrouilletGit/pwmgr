package com.pwmgr.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val ALGO = "AES/GCM/NoPadding"
private const val TAG_BITS = 128 // 16 bytes

internal actual fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(ALGO)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
    if (aad.isNotEmpty()) cipher.updateAAD(aad)
    return cipher.doFinal(plaintext)
}

internal actual fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(ALGO)
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
    if (aad.isNotEmpty()) cipher.updateAAD(aad)
    return try {
        cipher.doFinal(ciphertext)
    } catch (e: AEADBadTagException) {
        throw AeadAuthenticationException(e)
    } catch (e: javax.crypto.BadPaddingException) {
        // Some JDKs surface auth failure as BadPaddingException rather than AEADBadTagException.
        throw AeadAuthenticationException(e)
    }
}
