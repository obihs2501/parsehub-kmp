package com.parsehub.core

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual fun sha512(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-512").digest(data)

internal actual fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
    if (data.isEmpty() || data.size % 16 != 0) return null
    return try {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        c.doFinal(data)
    } catch (_: Exception) {
        null
    }
}

internal actual fun b64Decode(s: String): ByteArray? =
    try {
        Base64.getDecoder().decode(s)
    } catch (_: Exception) {
        null
    }

internal actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun urlDecode(s: String): String =
    try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

internal actual fun md5Hex(data: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(data).joinToString("") { b ->
        val v = b.toInt() and 0xFF
        val h = v.toString(16)
        if (h.length == 1) "0$h" else h
    }
