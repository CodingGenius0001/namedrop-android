package com.keshav.daptap.core

/** Reads the NDEF message from a Type 4 tag (another DapTap phone) given a raw APDU transceiver. */
object Type4Reader {
    private fun ok(r: ByteArray?) = r != null && r.size >= 2 && r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()
    private fun body(r: ByteArray) = r.copyOfRange(0, r.size - 2)

    fun read(transceive: (ByteArray) -> ByteArray?): ByteArray? = runCatching {
        if (!ok(transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07) + Type4Tag.NDEF_AID + 0x00))) return null
        if (!ok(transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02) + Type4Tag.CC_FILE_ID))) return null
        val ccResp = transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x0F))
        if (!ok(ccResp) || ccResp!!.size < 17) return null
        val cc = body(ccResp)
        val mle = (((cc[3].toInt() and 0xFF) shl 8) or (cc[4].toInt() and 0xFF)).coerceIn(0x0F, 0xFF)
        val fileId = cc.copyOfRange(9, 11)
        if (!ok(transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02) + fileId))) return null
        val nl = transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x02))
        if (!ok(nl) || nl!!.size < 4) return null
        val nlen = ((nl[0].toInt() and 0xFF) shl 8) or (nl[1].toInt() and 0xFF)
        if (nlen == 0 || nlen > 32_000) return null
        val out = java.io.ByteArrayOutputStream()
        var offset = 2
        while (offset < nlen + 2) {
            val want = minOf(mle, nlen + 2 - offset)
            val r = transceive(byteArrayOf(0x00, 0xB0.toByte(), (offset ushr 8).toByte(), offset.toByte(), want.toByte()))
            if (!ok(r) || r!!.size <= 2) return null
            out.write(r, 0, r.size - 2)
            offset += r.size - 2
        }
        out.toByteArray()
    }.getOrNull()

    /** Pushes our card to a DapTap tag after reading it (see Type4Tag INS DA). */
    fun push(payload: ByteArray, transceive: (ByteArray) -> ByteArray?): Boolean = runCatching {
        val chunks = payload.toList().chunked(0xF0).map { it.toByteArray() }
        if (chunks.isEmpty() || chunks.size > 255) return false
        chunks.forEachIndexed { i, c ->
            val last = if (i == chunks.lastIndex) 0x01 else 0x00
            val r = transceive(byteArrayOf(0x80.toByte(), Type4Tag.INS_PUSH.toByte(), i.toByte(), last.toByte(), c.size.toByte()) + c)
            if (!ok(r)) return false
        }
        true
    }.getOrDefault(false)
}
