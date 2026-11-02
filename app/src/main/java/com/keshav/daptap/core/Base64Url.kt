package com.keshav.daptap.core

import java.util.Base64

object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))
    fun decodeOrNull(text: String): ByteArray? = runCatching { decode(text) }.getOrNull()
}
