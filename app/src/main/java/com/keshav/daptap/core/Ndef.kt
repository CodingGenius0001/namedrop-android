package com.keshav.daptap.core

import java.io.ByteArrayOutputStream

/** Minimal NDEF encoder/decoder (NFC Forum NDEF 1.0), independent of android.nfc so it is unit-testable. */
object Ndef {
    const val TNF_WELL_KNOWN = 0x01
    const val TNF_MIME = 0x02
    const val TNF_EXTERNAL = 0x04

    /** External type carrying a DapTap card between Android phones. iOS ignores it, so it never pops a banner. */
    const val DAPTAP_EXTERNAL_TYPE = "keshav.com:daptap"

    /** Android's Contacts app imports this record type straight off an NFC tag. */
    const val VCARD_MIME = "text/vcard"
    private val VCARD_MIMES = setOf("text/vcard", "text/x-vcard")

    data class Record(val tnf: Int, val type: ByteArray, val payload: ByteArray, val id: ByteArray = ByteArray(0)) {
        val typeString: String get() = String(type, Charsets.US_ASCII)
        override fun equals(other: Any?) = other is Record && tnf == other.tnf &&
            type.contentEquals(other.type) && payload.contentEquals(other.payload) && id.contentEquals(other.id)
        override fun hashCode() = (tnf * 31 + type.contentHashCode()) * 31 + payload.contentHashCode()
    }

    // NFC Forum URI RTD abbreviation table (index = code). Only the ones we may emit or meet matter,
    // but the whole table is listed so decoding is correct for any tag.
    private val URI_PREFIXES = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://",
        "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:",
        "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
        "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    fun uriRecord(uri: String): Record {
        var code = 0
        var best = ""
        URI_PREFIXES.forEachIndexed { i, p -> if (p.isNotEmpty() && uri.startsWith(p) && p.length > best.length) { code = i; best = p } }
        val rest = uri.substring(best.length).toByteArray(Charsets.UTF_8)
        return Record(TNF_WELL_KNOWN, byteArrayOf('U'.code.toByte()), byteArrayOf(code.toByte()) + rest)
    }

    fun externalRecord(type: String, payload: ByteArray) =
        Record(TNF_EXTERNAL, type.lowercase().toByteArray(Charsets.US_ASCII), payload)

    fun mimeRecord(mime: String, payload: ByteArray) = Record(TNF_MIME, mime.toByteArray(Charsets.US_ASCII), payload)

    /** The first vCard in a message, as a phone's Contacts app would import it. */
    fun findVCard(records: List<Record>): VCard.Parsed? = records
        .firstOrNull { it.tnf == TNF_MIME && it.typeString.lowercase() in VCARD_MIMES }
        ?.let { VCard.parse(String(it.payload, Charsets.UTF_8)) }

    fun decodeUri(record: Record): String? {
        if (record.tnf != TNF_WELL_KNOWN || record.typeString != "U" || record.payload.isEmpty()) return null
        val code = record.payload[0].toInt() and 0xFF
        val prefix = URI_PREFIXES.getOrNull(code) ?: ""
        return prefix + String(record.payload, 1, record.payload.size - 1, Charsets.UTF_8)
    }

    fun encode(records: List<Record>): ByteArray {
        require(records.isNotEmpty())
        val out = ByteArrayOutputStream()
        records.forEachIndexed { i, r ->
            val short = r.payload.size < 256
            var header = r.tnf and 0x07
            if (i == 0) header = header or 0x80 // MB
            if (i == records.lastIndex) header = header or 0x40 // ME
            if (short) header = header or 0x10 // SR
            if (r.id.isNotEmpty()) header = header or 0x08 // IL
            out.write(header)
            out.write(r.type.size)
            if (short) out.write(r.payload.size) else {
                val n = r.payload.size
                out.write(n ushr 24 and 0xFF); out.write(n ushr 16 and 0xFF); out.write(n ushr 8 and 0xFF); out.write(n and 0xFF)
            }
            if (r.id.isNotEmpty()) out.write(r.id.size)
            out.write(r.type)
            if (r.id.isNotEmpty()) out.write(r.id)
            out.write(r.payload)
        }
        return out.toByteArray()
    }

    /** Parses an NDEF message. Returns null on malformed input instead of throwing. Chunked records are rejected. */
    fun decode(bytes: ByteArray): List<Record>? {
        val records = mutableListOf<Record>()
        var i = 0
        fun u8(): Int { if (i >= bytes.size) throw IndexOutOfBoundsException(); return bytes[i++].toInt() and 0xFF }
        return try {
            while (i < bytes.size) {
                val header = u8()
                if (header and 0x20 != 0) return null // CF: chunking unsupported
                val typeLen = u8()
                val payloadLen = if (header and 0x10 != 0) u8() else
                    (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
                if (payloadLen < 0 || payloadLen > 1 shl 20) return null
                val idLen = if (header and 0x08 != 0) u8() else 0
                if (i + typeLen + idLen + payloadLen > bytes.size) return null
                val type = bytes.copyOfRange(i, i + typeLen); i += typeLen
                val id = bytes.copyOfRange(i, i + idLen); i += idLen
                val payload = bytes.copyOfRange(i, i + payloadLen); i += payloadLen
                records += Record(header and 0x07, type, payload, id)
                if (header and 0x40 != 0) break
            }
            records.takeIf { it.isNotEmpty() }
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    /**
     * Pulls a DapTap payload out of any NDEF message: our external record first, then a web-card URL.
     * The photo travels once, in the vCard record, so it is attached from there when the payload has none.
     */
    fun findPayload(records: List<Record>): WirePayload? {
        val payload = records.firstOrNull { it.tnf == TNF_EXTERNAL && it.typeString.equals(DAPTAP_EXTERNAL_TYPE, true) }
            ?.let { WirePayload.decode(String(it.payload, Charsets.US_ASCII)) }
            ?: records.firstNotNullOfOrNull { decodeUri(it) }?.let { WirePayload.fromUrl(it) }
            ?: return null
        if (payload.photo.isNotEmpty()) return payload
        val photo = findVCard(records)?.photo ?: return payload
        return payload.copy(photo = Base64Url.encode(photo))
    }
}
