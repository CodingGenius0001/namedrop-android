package com.keshav.daptap.namedrop

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.dd.plist.UID
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * An iPhone can NameDrop a card whose N/FN are empty and carry the name only on its Contact Poster
 * (`X-WALLPAPER`: a keyed archive of CNWallpaper whose `_posterArchiveData` is a zipped PosterKit archive of
 * more property lists). [inspect] digs through all of it for a name and returns a full outline of the poster
 * (keys, entry names, short strings; never image bytes) for the testing log.
 */
object PosterName {
    data class Result(val name: String?, val description: String)

    private const val DUMP_MAX = 40_000

    fun inspect(vcard: String): Result {
        val blobs = properties(vcard).filter { (k, _) -> k.startsWith("X-WALLPAPER") }
        if (blobs.isEmpty()) return Result(null, "no poster")
        val w = Walker()
        for ((key, value) in blobs) {
            val bytes = runCatching { Base64.getMimeDecoder().decode(value.trim()) }.getOrNull()
            if (bytes == null) { w.line(0, "$key: not base64"); continue }
            w.line(0, "$key ${bytes.size} B")
            w.bytes(bytes, 1, key)
        }
        return Result(w.best(), w.out.toString().take(DUMP_MAX))
    }

    /** Scores a (key path, string) pair as a name: higher is likelier. 0 = not a name. */
    internal fun score(path: String, key: String, value: String): Int {
        if (!looksLikeName(value)) return 0
        val k = key.lowercase()
        val p = path.lowercase()
        return when {
            k in setOf("givenname", "firstname", "familyname", "lastname", "middlename") -> 0 // assembled separately
            k.contains("displayname") || k.contains("fullname") || k.contains("nametext") || k.contains("titlestring") -> 9
            k == "name" && (p.contains("contact") || p.contains("title")) -> 8
            k.contains("nickname") || k.contains("contactname") || k.contains("personname") -> 7
            k.contains("title") && p.contains("text") && !k.contains("style") && !k.contains("font") -> 6
            k == "string" || k == "ns.string" || k == "nsstring" -> if (p.contains("title") || p.contains("name")) 5 else 3
            else -> 0
        }
    }

    private class Walker {
        val out = StringBuilder()
        private var given: String? = null
        private var family: String? = null
        private var bestScore = 0
        private var bestName: String? = null

        fun best(): String? = listOfNotNull(given, family).joinToString(" ").ifBlank { null } ?: bestName

        fun line(depth: Int, s: String) {
            if (out.length < DUMP_MAX) out.append("  ".repeat(depth)).append(s).append('\n')
        }

        fun consider(path: String, key: String, value: String) {
            val v = value.trim()
            if (!looksLikeName(v)) return
            when (key.lowercase()) {
                "givenname", "firstname" -> { if (given == null) given = v; return }
                "familyname", "lastname" -> { if (family == null) family = v; return }
            }
            val s = score(path, key, v)
            if (s > bestScore) { bestScore = s; bestName = v }
        }

        fun bytes(b: ByteArray, depth: Int, path: String) {
            if (depth > 8) { line(depth, "…"); return }
            when {
                isPlist(b) -> runCatching { PropertyListParser.parse(b) }
                    .onSuccess { plist(it, depth, path) }
                    .onFailure { line(depth, "plist? ${it.javaClass.simpleName}") }
                b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() -> zip(b, depth, path)
                isText(b) -> line(depth, "text(${b.size}): " + String(b, Charsets.UTF_8).replace('\n', ' ').take(300))
                else -> line(depth, "${kind(b)}(${b.size})")
            }
        }

        private fun zip(b: ByteArray, depth: Int, path: String) = runCatching {
            ZipInputStream(ByteArrayInputStream(b)).use { z ->
                var n = 0
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val data = z.readBytes()
                    line(depth, "zip ${e.name} (${data.size} B)")
                    if (data.size < 8_000_000) bytes(data, depth + 1, "$path/${e.name}")
                    if (++n >= 80) { line(depth, "zip: more entries…"); break }
                }
            }
        }.onFailure { line(depth, "zip? ${it.javaClass.simpleName}") }

        private fun plist(root: NSObject, depth: Int, path: String) {
            val objects = (root as? NSDictionary)?.get("\$objects") as? NSArray
            if (objects != null) archive(objects, depth, path) else value(root, depth, path, "")
        }

        /** A plain plist, printed as a tree with string values. */
        private fun value(o: NSObject?, depth: Int, path: String, key: String) {
            val label = if (key.isEmpty()) "" else "$key: "
            when (o) {
                is NSDictionary -> {
                    line(depth, "$label{")
                    o.allKeys().sorted().forEach { k -> value(o.get(k), depth + 1, "$path.$k", k) }
                    line(depth, "}")
                }
                is NSArray -> {
                    line(depth, "$label[${o.count()}]")
                    o.array.take(40).forEachIndexed { i, x -> value(x, depth + 1, "$path[$i]", key) }
                }
                is NSString -> { consider(path, key, o.content); line(depth, label + str(o.content)) }
                is NSData -> {
                    val b = o.bytes()
                    if (b.size > 16 && (isPlist(b) || isZip(b))) { line(depth, "${label}data(${b.size}):"); bytes(b, depth + 1, "$path.$key") }
                    else line(depth, "${label}${kind(b)}(${b.size})")
                }
                is UID -> line(depth, "${label}@${uid(o)}")
                null -> line(depth, "${label}null")
                else -> line(depth, label + o.toString().take(60))
            }
        }

        /** A keyed archive: every object with its index; references shown as @n and resolved for names. */
        private fun archive(objects: NSArray, depth: Int, path: String) {
            val arr = objects.array
            fun className(d: NSDictionary): String? =
                ((d.get("\$class") as? UID)?.let { arr.getOrNull(uid(it)) } as? NSDictionary)?.get("\$classname")?.let { (it as? NSString)?.content }
            line(depth, "archive (${arr.size} objects)")
            arr.forEachIndexed { i, obj ->
                when (obj) {
                    is NSDictionary -> {
                        if (obj.containsKey("\$classname")) return@forEachIndexed
                        val cls = className(obj) ?: "?"
                        line(depth + 1, "#$i $cls {")
                        for (k in obj.allKeys().sorted()) {
                            if (k == "\$class") continue
                            val v = obj.get(k)
                            val target = (v as? UID)?.let { arr.getOrNull(uid(it)) }
                            val shown = when {
                                target is NSString -> { consider("$path.$cls", k, target.content); "@${uid(v as UID)}=" + str(target.content) }
                                v is UID -> "@${uid(v)}"
                                v is NSString -> { consider("$path.$cls", k, v.content); str(v.content) }
                                v is NSData -> "${kind(v.bytes())}(${v.length()})"
                                v is NSArray -> "[" + v.array.take(12).joinToString(",") { (it as? UID)?.let { u -> "@${uid(u)}" } ?: it.toString().take(20) } + "]"
                                else -> v.toString().take(60)
                            }
                            line(depth + 2, "$k: $shown")
                        }
                        line(depth + 1, "}")
                    }
                    is NSString -> if (obj.content != "\$null") line(depth + 1, "#$i " + str(obj.content))
                    is NSData -> {
                        val b = obj.bytes()
                        line(depth + 1, "#$i ${kind(b)}(${b.size})")
                        if (b.size > 16 && (isPlist(b) || isZip(b))) bytes(b, depth + 2, "$path#$i")
                    }
                    else -> {}
                }
            }
        }

        private fun str(s: String) = if (s.length <= 120) "\"" + s.replace('\n', ' ') + "\"" else "str(${s.length})"
    }

    /** Not a file name, identifier or URL: letters, spaces and the odd apostrophe or hyphen. */
    internal fun looksLikeName(s: String) =
        s.length in 1..60 && s.any(Char::isLetter) && s.none { it == '.' || it == '/' || it == ':' || it == '_' || it == '$' || it == '{' } &&
            !Regex("^[0-9A-Fa-f-]{16,}$").matches(s)

    private fun uid(u: UID): Int = u.bytes.fold(0) { v, x -> (v shl 8) or (x.toInt() and 0xff) }

    private fun isPlist(b: ByteArray) = (b.size >= 6 && String(b, 0, 6, Charsets.US_ASCII) == "bplist") ||
        (b.size >= 5 && String(b, 0, 5, Charsets.US_ASCII) == "<?xml")
    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
    private fun isText(b: ByteArray) = b.size in 1..20_000 && b.take(200).all { it >= 9 || it < 0 }.let { ok ->
        ok && runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)) }.isSuccess }

    private fun kind(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpeg"
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b.size >= 12 && String(b, 4, 4, Charsets.US_ASCII) == "ftyp" -> "heic"
        else -> "bytes"
    }

    /** Unfolded vCard properties as (name with params, value). */
    private fun properties(vcard: String): List<Pair<String, String>> =
        vcard.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split('\n')
            .filter { it.contains(':') }
            .map { it.substringBefore(':').uppercase() to it.substringAfter(':') }
}
