package com.pwmgr.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal actual fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key, "HmacSHA1"))
    return mac.doFinal(data)
}
