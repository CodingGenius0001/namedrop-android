package com.keshav.daptap.namedrop

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * An iPhone can NameDrop a card whose N/FN are empty and carry the name only on its Contact Poster
 * (`X-WALLPAPER`, a base64 property list that may wrap a zipped poster archive). This digs through it for
 * name fields. [find] returns the name if one turns up; [describe] lists the structure (keys, entry names,
 * short strings) for the testing log, so the next tap shows where iOS keeps it if [find] misses.
 */
object PosterName {
    private val GIVEN = setOf("givenname", "firstname", "first")
    private val FAMILY = setOf("familyname", "lastname", "last")
    private val WHOLE = setOf("displayname", "fullname", "name", "nickname", "contactname", "nametext")

    data class Result(val name: String?, val description: String)

    fun inspect(vcard: String): Result {
        val blobs = properties(vcard).filter { (k, _) -> k.startsWith("X-WALLPAPER") || k.startsWith("X-WATCH-WALLPAPER") }
        if (blobs.isEmpty()) return Result(null, "no poster")
        val notes = StringBuilder()
        var found: String? = null
        for ((key, value) in blobs) {
            val bytes = runCatching { Base64.getMimeDecoder().decode(value.trim()) }.getOrNull()
            if (bytes == null) { notes.append("$key: not base64; "); continue }
            val names = Names()
            notes.append("$key ${bytes.size} B: ").append(walkBytes(bytes, names, 0)).append("; ")
            if (found == null) found = names.best()
        }
        return Result(found, notes.toString().take(1500))
    }

    private class Names {
        var given: String? = null; var family: String? = null; var whole: String? = null
        fun best(): String? = listOfNotNull(given, family).joinToString(" ").ifBlank { null } ?: whole
    }

    private fun walkBytes(b: ByteArray, names: Names, depth: Int): String {
        if (depth > 4) return "…"
        return when {
            b.size >= 6 && String(b, 0, 6, Charsets.US_ASCII) == "bplist" || b.firstOrNull() == '<'.code.toByte() ->
                runCatching { walk(PropertyListParser.parse(b), names, depth, 3) }.getOrElse { "plist? (${it.javaClass.simpleName})" }
            b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() -> zip(b, names, depth)
            else -> "${kind(b)}(${b.size})"
        }
    }

    private fun zip(b: ByteArray, names: Names, depth: Int): String = runCatching {
        val out = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val data = z.readBytes()
                val inner = if (data.size < 4_000_000) walkBytes(data, names, depth + 1) else "big"
                out += "${e.name.takeLast(60)}=${if (inner.length > 200) inner.take(200) + "…" else inner}"
                if (out.size >= 30) break
            }
        }
        "zip[" + out.joinToString(", ") + "]"
    }.getOrElse { "zip? (${it.javaClass.simpleName})" }

    /** Walks a plist; keyed archives are flattened through their `$objects`. */
    private fun walk(o: NSObject?, names: Names, depth: Int, budget: Int, key: String? = null): String = when (o) {
        is NSDictionary -> {
            for (k in o.allKeys()) {
                val v = o.get(k)
                val lk = k.lowercase()
                if (v is NSString) {
                    val s = v.content.trim()
                    if (looksLikeName(s)) when (lk) {
                        in GIVEN -> names.given = names.given ?: s
                        in FAMILY -> names.family = names.family ?: s
                        in WHOLE -> names.whole = names.whole ?: s
                    }
                }
            }
            val objects = o.get("\$objects") as? NSArray
            if (objects != null) "archive{" + archive(objects, names, depth) + "}"
            else if (budget <= 0) "{${o.count()}}"
            else o.allKeys().sorted().joinToString(", ", "{", "}") { k -> "$k: ${walk(o.get(k), names, depth, budget - 1, k)}" }
        }
        is NSArray -> if (budget <= 0 || o.count() == 0) "[${o.count()}]" else
            o.array.take(8).joinToString(", ", "[", if (o.count() > 8) ", …]" else "]") { walk(it, names, depth, budget - 1) }
        is NSString -> "str(${o.content.length})"
        is NSData -> o.bytes().let { b -> if (b.size > 16) walkBytes(b, names, depth + 1) else "data(${b.size})" }
        null -> "null"
        else -> o.javaClass.simpleName.removePrefix("NS").lowercase()
    }

    /** A keyed archive: class names, dictionary keys and nested data, plus short strings (they may be the name). */
    private fun archive(objects: NSArray, names: Names, depth: Int): String {
        val classes = LinkedHashSet<String>(); val keys = LinkedHashSet<String>(); val strings = ArrayList<String>(); val nested = ArrayList<String>()
        for (obj in objects.array) when (obj) {
            is NSDictionary -> {
                (obj.get("\$classname") as? NSString)?.let { classes += it.content }
                obj.allKeys().filter { !it.startsWith("$") }.forEach { keys += it }
                // In an archive a name field points at a string elsewhere in $objects through a UID.
                for (k in obj.allKeys()) {
                    val lk = k.lowercase()
                    if (lk !in GIVEN && lk !in FAMILY && lk !in WHOLE) continue
                    val target = (obj.get(k) as? com.dd.plist.UID)?.let { uidIndex(it) }?.let { objects.array.getOrNull(it) }
                    val s = (target as? NSString)?.content?.trim()?.takeIf(::looksLikeName) ?: continue
                    when (lk) {
                        in GIVEN -> names.given = names.given ?: s
                        in FAMILY -> names.family = names.family ?: s
                        else -> names.whole = names.whole ?: s
                    }
                }
            }
            is NSString -> if (obj.content.length in 1..40 && obj.content != "\$null") strings += obj.content
            is NSData -> if (obj.length() > 16) nested += walkBytes(obj.bytes(), names, depth + 1).take(300)
            else -> {}
        }
        return "classes=${classes.take(15)} keys=${keys.take(40)} strings=${strings.take(25)}" +
            if (nested.isNotEmpty()) " data=${nested.take(4)}" else ""
    }

    /** Not a file name, identifier or URL: letters, spaces and the odd apostrophe or hyphen. */
    private fun looksLikeName(s: String) =
        s.length in 1..60 && s.any(Char::isLetter) && s.none { it == '.' || it == '/' || it == ':' || it == '_' || it == '$' }

    private fun uidIndex(u: com.dd.plist.UID): Int? {
        val b = u.bytes
        var v = 0
        for (x in b) v = (v shl 8) or (x.toInt() and 0xff)
        return v
    }

    private fun kind(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpeg"
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b.size >= 12 && String(b, 4, 4, Charsets.US_ASCII) == "ftyp" -> "heic"
        b.size >= 2 && b[0] == '{'.code.toByte() -> "json"
        else -> "bytes"
    }

    /** Unfolded vCard properties as (name with params, value). */
    private fun properties(vcard: String): List<Pair<String, String>> =
        vcard.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split('\n')
            .filter { it.contains(':') }
            .map { it.substringBefore(':').uppercase() to it.substringAfter(':') }
}
