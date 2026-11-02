package com.keshav.daptap.core

import java.io.ByteArrayOutputStream

/*
 * Apple's NameDrop handshake over NFC (the `com.apple.boop` applet, "SNAP"), as reverse-engineered and
 * verified against real iPhones (iOS 26.6) by github.com/enkai-liu/namedrop-re-public (MIT licence,
 * Copyright (c) 2026 namedrop-re contributors). The applet logic below is a Kotlin port of that project's SnapApplet.java and
 * scripts/build-snap-serverinfo.py; see THIRD_PARTY.md.
 *
 * After an iPhone hears our NameDrop ECP frame it selects the boop applet and the two sides swap a CBOR
 * "ServerInfo": a P-256 public key (later the TLS key of the contact exchange) and a listener UUID (the
 * Bonjour name `<UUID>._asquic._udp` the iPhone then looks for). No Apple ID or Apple certificate is
 * involved: field 4 is a signature no peer can check at NFC time, so it only has to be well formed.
 *
 * Everything here is pure Kotlin so it runs in unit tests, and every answer is prebuilt: the HCE service
 * has a ~38 ms frame-waiting time per APDU.
 */

/** Minimal definite-length CBOR encoder: just the shapes SNAP uses. */
object Cbor {
    private fun head(out: ByteArrayOutputStream, major: Int, v: Long) {
        val m = major shl 5
        when {
            v < 24 -> out.write(m or v.toInt())
            v < 0x100 -> { out.write(m or 24); out.write(v.toInt()) }
            v < 0x10000 -> { out.write(m or 25); out.write((v ushr 8).toInt()); out.write(v.toInt() and 0xFF) }
            else -> { out.write(m or 26); for (s in 24 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt()) }
        }
    }

    fun encode(o: Any?): ByteArray = ByteArrayOutputStream().also { write(it, o) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, o: Any?) {
        when (o) {
            null -> out.write(0xF6)
            is Boolean -> out.write(if (o) 0xF5 else 0xF4)
            is Int -> write(out, o.toLong())
            is Long -> if (o >= 0) head(out, 0, o) else head(out, 1, -1 - o)
            is ByteArray -> { head(out, 2, o.size.toLong()); out.write(o) }
            is String -> o.toByteArray(Charsets.UTF_8).let { head(out, 3, it.size.toLong()); out.write(it) }
            is List<*> -> { head(out, 4, o.size.toLong()); o.forEach { write(out, it) } }
            // LinkedHashMap keeps insertion order, which is the order Apple's encoder uses.
            is Map<*, *> -> { head(out, 5, o.size.toLong()); o.forEach { (k, v) -> write(out, k); write(out, v) } }
            else -> error("unsupported CBOR value ${o::class}")
        }
    }
}

/** The per-install NameDrop identity. Only the public half ever crosses the air. */
class SnapIdentity(
    /** DER SubjectPublicKeyInfo of an EC P-256 key: always 91 bytes. */
    val spki: ByteArray,
    /** The Bonjour listener UUID, 16 raw bytes. */
    val listenerUuid: ByteArray,
    val token: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(spki.size == 91) { "P-256 SPKI must be 91 bytes" }
        require(listenerUuid.size == 16 && token.size == 6 && signature.size == 64)
    }

    /** `XXXXXXXX-XXXX-…` upper case, the `_asquic._udp` instance name. */
    val uuidString: String get() = Snap.uuidString(listenerUuid)
}

/** The prebuilt answers, built once per identity. */
class SnapBlobs(id: SnapIdentity, sessionId: Int = DEFAULT_SESSION) {
    /** Our ServerInfo with Apple's own label, timestamp still a placeholder (238 bytes). */
    val serverInfo: ByteArray = Cbor.encode(linkedMapOf(
        0 to "1.1",
        1 to Snap.LABEL_NAMEDROP,
        2 to listOf(linkedMapOf(0 to "Bonjour", 1 to 0)),
        3 to linkedMapOf(0 to PLACEHOLDER_TS, 1 to id.spki, 2 to id.listenerUuid, 3 to id.token, 4 to id.signature, 5 to 1),
    ))

    /** The ServerInfo up to and including key 1's tag, so the reader's own label can be spliced in. */
    val head: ByteArray = byteArrayOf(0xA4.toByte(), 0x00) + Cbor.encode("1.1") + 0x01

    /** Everything after the label. */
    val tail: ByteArray

    /** Where the 4-byte timestamp sits inside [tail]. */
    val tailTsOffset: Int

    /** The capabilities blob a real card hands back on resume when it kept none from the reader (85 bytes). */
    val caps: ByteArray = Cbor.encode(linkedMapOf(
        0 to "1.1",
        1 to Snap.LABEL_NAMEDROP,
        2 to emptyList<Any>(),
        3 to linkedMapOf(5 to 2, "RPKnownIdentityKey" to 0, "RPSupportsApplicationLabelKey" to 1),
    ))

    /** Answer to the capabilities push, sent with SW 6A88: {1: 719, 2: session}. */
    val redirect: ByteArray = Cbor.encode(linkedMapOf(1 to 719, 2 to sessionId))

    init {
        val label = Cbor.encode(Snap.LABEL_NAMEDROP)
        check(serverInfo.copyOfRange(0, head.size).contentEquals(head)) { "ServerInfo head drifted" }
        tail = serverInfo.copyOfRange(head.size + label.size, serverInfo.size)
        tailTsOffset = Snap.indexOf(tail, PLACEHOLDER_TS)
        check(tailTsOffset >= 0) { "timestamp placeholder missing" }
    }

    companion object {
        val PLACEHOLDER_TS = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        const val DEFAULT_SESSION = 0x5AA5
    }
}

/**
 * The `com.apple.boop.SNAP` card applet. One instance per HCE activation sequence; [reset] between
 * activations (the two-activation handshake survives it, see the resume branch).
 */
class SnapApplet(private val blobs: SnapBlobs) {
    class Result(val response: ByteArray, val event: Event, val detail: String)

    enum class Event { BOOP_SELECTED, PROBE, SERVER_INFO, CAPS_PUSH, RESUMED, UNEXPECTED }

    /** The boop applet was selected in this activation: only then do GET DATA and probes belong to us. */
    var selected = false
        private set
    private var capsFromReader: ByteArray? = null
    private var peerTs: ByteArray? = null
    var peerUuid: String? = null
        private set
    var peerLabel: String? = null
        private set

    fun reset() {
        selected = false
        capsFromReader = null
        peerTs = null
        peerUuid = null
        peerLabel = null
    }

    /** Returns null when the APDU is not for this applet. */
    fun dispatch(apdu: ByteArray): Result? {
        if (Snap.isBoopSelect(apdu)) {
            selected = true
            return Result(SELECT_RESP.clone(), Event.BOOP_SELECTED, "SELECT com.apple.boop")
        }
        // Selecting any other application deselects ours.
        if (apdu.size >= 3 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte() && apdu[2] == 0x04.toByte()) {
            selected = false
            return null
        }
        if (!selected) return null
        // iOS sends a degenerate 1-3 byte I-block ~200 ms after our SELECT answer when it has nothing to
        // push: a real card answers with its ServerInfo unprompted.
        if (apdu.isNotEmpty() && apdu.size < 4) {
            return Result(splice(null) + SW_OK, Event.PROBE, "probe (${apdu.size} B) -> our ServerInfo")
        }
        if (apdu.size < 4) return null
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        if (ins != 0xCA) return null
        val lc = if (apdu.size > 4) minOf(apdu[4].toInt() and 0xFF, apdu.size - 5) else 0
        val body = if (lc > 0) apdu.copyOfRange(5, 5 + lc) else ByteArray(0)
        return when {
            p1 == 0x01 && p2 == 0x03 && lc >= SERVERINFO_MIN_LC -> {
                scrape(body)
                val si = splice(body)
                Result(si + SW_OK, Event.SERVER_INFO, "iPhone ServerInfo ($lc B, ${peerLabel ?: "default label"})")
            }
            p1 == 0x01 && p2 == 0x03 -> {
                if (lc > 0) capsFromReader = body
                Result(blobs.redirect + SW_REDIRECT, Event.CAPS_PUSH, "capabilities ($lc B) -> redirect 6A88")
            }
            p1 == 0x01 && p2 == 0x04 -> {
                // A real card hands the reader's own capabilities back verbatim.
                val caps = capsFromReader ?: blobs.caps
                Result(caps + SW_OK, Event.RESUMED, "iPhone resumed the session (${caps.size} B)")
            }
            else -> Result(SW_FILE_NOT_FOUND.clone(), Event.UNEXPECTED, "GET DATA %02X %02X Lc=%d".format(p1, p2, lc))
        }
    }

    /** Lifts the iPhone's timestamp and listener UUID out of its ServerInfo, checking the sub-tags first. */
    private fun scrape(body: ByteArray): Boolean {
        val a = Snap.indexOf(body, SI_ANCHOR)
        if (a < 0 || a + SI_OFF_TOK + 6 > body.size) return false
        if (body[a + 7] != 0x01.toByte() || body[a + 8] != 0x58.toByte() || body[a + 9] != 0x5B.toByte()) return false
        if (body[a + 101] != 0x02.toByte() || body[a + 102] != 0x50.toByte()) return false
        peerTs = body.copyOfRange(a + SI_OFF_TS, a + SI_OFF_TS + 4)
        peerUuid = Snap.uuidString(body.copyOfRange(a + SI_OFF_UUID, a + SI_OFF_UUID + 16))
        return true
    }

    /**
     * head + the reader's key-1 label + tail, with the reader's timestamp echoed. The label is
     * `com.apple.boop.SNAP` for NameDrop and `com.apple.airdrop.sharesheet` from a share sheet; a real
     * card echoes whichever the reader offered.
     */
    private fun splice(body: ByteArray?): ByteArray {
        val hl = blobs.head.size
        var label: ByteArray? = null
        if (body != null && body.size > hl && body.copyOfRange(0, hl).contentEquals(blobs.head)) {
            val t = body[hl].toInt() and 0xFF
            val len = when {
                t in 0x60..0x77 -> 1 + (t and 0x1F)
                t == 0x78 && body.size > hl + 1 -> 2 + (body[hl + 1].toInt() and 0xFF)
                else -> 0
            }
            if (len > 0 && hl + len <= body.size && len <= MAX_LABEL) label = body.copyOfRange(hl, hl + len)
        }
        if (label == null) {
            label = Cbor.encode(Snap.LABEL_NAMEDROP)
            peerLabel = null
        } else {
            val skip = if ((label[0].toInt() and 0xFF) == 0x78) 2 else 1
            peerLabel = String(label, skip, label.size - skip, Charsets.US_ASCII)
        }
        val out = blobs.head + label + blobs.tail
        peerTs?.copyInto(out, hl + label.size + blobs.tailTsOffset)
        return out
    }

    companion object {
        val SELECT_RESP = byteArrayOf(0xA5.toByte(), 0x03, 0xCE.toByte(), 0x01, 0x01, 0x90.toByte(), 0x00)
        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        /** A redirect, not a failure: the reader comes back on a fresh activation. */
        val SW_REDIRECT = byteArrayOf(0x6A, 0x88.toByte())
        private val SI_ANCHOR = byteArrayOf(0xA6.toByte(), 0x00, 0x44)
        private const val SI_OFF_TS = 3
        private const val SI_OFF_UUID = 103
        private const val SI_OFF_TOK = 121
        private const val MAX_LABEL = 64
        private const val SERVERINFO_MIN_LC = 150
    }
}

object Snap {
    const val LABEL_NAMEDROP = "com.apple.boop.SNAP"
    /** Apple's registered RID: every com.apple.boop AID starts with it. */
    val BOOP_AID_PREFIX = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x08, 0x58)

    fun isBoopSelect(apdu: ByteArray): Boolean {
        if (apdu.size < 5 + BOOP_AID_PREFIX.size) return false
        if (apdu[0] != 0x00.toByte() || apdu[1] != 0xA4.toByte() || apdu[2] != 0x04.toByte()) return false
        return BOOP_AID_PREFIX.indices.all { apdu[5 + it] == BOOP_AID_PREFIX[it] }
    }

    fun uuidString(u: ByteArray): String = buildString(36) {
        u.forEachIndexed { i, b -> if (i == 4 || i == 6 || i == 8 || i == 10) append('-'); append("%02X".format(b)) }
    }

    fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
