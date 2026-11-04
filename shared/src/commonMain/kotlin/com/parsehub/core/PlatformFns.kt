package com.parsehub.core

/** 平台桥接（android / desktop 各有一份实现）：加密、时间、URL 解码 */
internal expect fun sha512(data: ByteArray): ByteArray
internal expect fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray?
internal expect fun b64Decode(s: String): ByteArray?
internal expect fun nowMillis(): Long
internal expect fun urlDecode(s: String): String
