package com.keshav.daptap.core

import java.util.Base64

/** vCard 3.0 writer (what iOS Contacts and Android Contacts both import cleanly) and a tolerant 2.1/3.0/4.0 reader. */
object VCard {

    data class Parsed(val card: ContactCard, val photo: ByteArray?)

    fun write(card: ContactCard, photoJpeg: ByteArray? = null): String {
        val c = card.sanitized()
        val sb = StringBuilder()
        fun line(s: String) { sb.append(fold(s)).append("\r\n") }
        line("BEGIN:VCARD")
        line("VERSION:3.0")
        val parts = c.name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val (given, family) = when {
            parts.isEmpty() -> "" to ""
            parts.size == 1 -> parts[0] to ""
            else -> parts.dropLast(1).joinToString(" ") to parts.last()
        }
        line("N:${esc(family)};${esc(given)};;;")
        line("FN:${esc(c.name.ifBlank { c.email.ifBlank { c.phone } })}")
        if (c.company.isNotBlank()) line("ORG:${esc(c.company)};")
        if (c.title.isNotBlank()) line("TITLE:${esc(c.title)}")
        if (c.phone.isNotBlank()) line("TEL;TYPE=CELL,VOICE:${esc(c.phone)}")
        if (c.email.isNotBlank()) line("EMAIL;TYPE=INTERNET:${esc(c.email)}")
        if (c.website.isNotBlank()) line("URL:${esc(normalizeUrl(c.website))}")
        if (c.instagram.isNotBlank()) {
            line("X-SOCIALPROFILE;TYPE=instagram:${esc("https://instagram.com/" + c.instagram)}")
        }
        if (photoJpeg != null && photoJpeg.isNotEmpty()) {
            line("PHOTO;ENCODING=b;TYPE=JPEG:" + Base64.getEncoder().encodeToString(photoJpeg))
        }
        line("END:VCARD")
        return sb.toString()
    }

    fun parse(text: String): Parsed? {
        val unfolded = text.replace("\r\n", "\n").replace("\r", "\n")
            .replace(Regex("\n[ \t]"), "")
        val lines = joinQuotedPrintable(unfolded.split('\n'))
        val begin = lines.indexOfFirst { it.trim().equals("BEGIN:VCARD", true) }
        if (begin < 0) return null
        var fn = ""; var n = ""; var phone = ""; var phoneIsCell = false; var email = ""
        var org = ""; var title = ""; var url = ""; var insta = ""; var photo: ByteArray? = null
        for (raw in lines.drop(begin + 1)) {
            val line = raw.trimEnd()
            if (line.equals("END:VCARD", true)) break
            val colon = indexOfUnquotedColon(line)
            if (colon <= 0) continue
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val segments = head.split(';')
            val prop = segments[0].substringAfterLast('.').uppercase()
            val params = segments.drop(1).map { it.uppercase() }
            val qp = params.any { it.contains("QUOTED-PRINTABLE") }
            fun raw(v: String) = if (qp) decodeQp(v) else v
            fun text(v: String) = unesc(raw(v))
            /** Structured values (N, ORG): split on unescaped ';' first, then unescape each part. */
            fun parts(v: String) = splitUnescaped(raw(v)).map { unesc(it) }
            when (prop) {
                "FN" -> fn = text(value)
                "N" -> n = parts(value).joinToString(";")
                "TEL" -> {
                    val cell = params.any { it.contains("CELL") || it.contains("IPHONE") || it.contains("MOBILE") }
                    if (phone.isEmpty() || (cell && !phoneIsCell)) { phone = text(value).removePrefix("tel:"); phoneIsCell = cell }
                }
                "EMAIL" -> if (email.isEmpty()) email = text(value)
                "ORG" -> if (org.isEmpty()) org = parts(value).firstOrNull { it.isNotBlank() }.orEmpty()
                "TITLE" -> if (title.isEmpty()) title = text(value)
                "URL" -> if (url.isEmpty()) url = text(value)
                "X-SOCIALPROFILE" -> if (insta.isEmpty() && (params.any { it.contains("INSTAGRAM") } || value.contains("instagram.com", true))) {
                    insta = handleFrom(text(value))
                }
                "PHOTO" -> if (photo == null) photo = decodePhoto(params, value)
            }
        }
        val name = fn.ifBlank {
            val p = n.split(';')
            listOf(p.getOrNull(3), p.getOrNull(1), p.getOrNull(2), p.getOrNull(0), p.getOrNull(4))
                .filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }
        val card = ContactCard(name, phone, email, org, title, url, insta).sanitized()
        if (card.isEmpty && org.isBlank()) return null
        return Parsed(card, photo)
    }

    /** vCard 2.1 quoted-printable values continue onto the next line after a trailing '='. */
    private fun joinQuotedPrintable(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            var line = lines[i]
            if (line.substringBefore(':').contains("QUOTED-PRINTABLE", ignoreCase = true)) {
                while (line.endsWith("=") && i + 1 < lines.size) { line = line.dropLast(1) + lines[i + 1]; i++ }
            }
            out += line
            i++
        }
        return out
    }

    private fun splitUnescaped(v: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            when {
                c == '\\' && i + 1 < v.length -> { cur.append(c).append(v[i + 1]); i += 2; continue }
                c == ';' -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun handleFrom(v: String): String {
        val x = v.substringAfter("x-user=", "").substringBefore(';').substringBefore(',')
        if (x.isNotBlank()) return x
        return v.trimEnd('/').substringAfterLast('/').removePrefix("@")
    }

    private fun decodePhoto(params: List<String>, value: String): ByteArray? = runCatching {
        val v = value.trim()
        when {
            v.startsWith("data:", true) -> Base64.getMimeDecoder().decode(v.substringAfter(","))
            v.startsWith("http", true) -> null
            params.any { it.contains("ENCODING=B") || it.contains("ENCODING=BASE64") || it == "BASE64" } ||
                v.length > 64 -> Base64.getMimeDecoder().decode(v)
            else -> null
        }
    }.getOrNull()?.takeIf { it.size in 16..(8 shl 20) }

    private fun indexOfUnquotedColon(s: String): Int {
        var quoted = false
        for (i in s.indices) {
            when (s[i]) { '"' -> quoted = !quoted; ':' -> if (!quoted) return i }
        }
        return -1
    }

    private fun decodeQp(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '=' && i + 2 < s.length) {
                val hex = s.substring(i + 1, minOf(i + 3, s.length))
                val b = hex.toIntOrNull(16)
                if (b != null && hex.length == 2) { out.write(b); i += 3; continue }
            }
            out.write(ch.code and 0xFF); i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun normalizeUrl(u: String): String = if (u.contains("://")) u else "https://$u"

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val nx = s[i + 1]) { 'n', 'N' -> sb.append('\n'); else -> sb.append(nx) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString().trim()
    }

    /** RFC 6350 line folding at 75 octets, never splitting a UTF-8 sequence. */
    private fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return line
        val sb = StringBuilder()
        var count = 0
        var first = true
        for (ch in line) {
            val len = ch.toString().toByteArray(Charsets.UTF_8).size
            val limit = if (first) 75 else 74
            if (count + len > limit) { sb.append("\r\n "); count = 0; first = false }
            sb.append(ch); count += len
        }
        return sb.toString()
    }
}
