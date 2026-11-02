package com.keshav.daptap

import com.keshav.daptap.core.Cbor
import com.keshav.daptap.core.Snap
import com.keshav.daptap.core.SnapApplet
import com.keshav.daptap.core.SnapBlobs
import com.keshav.daptap.core.SnapIdentity
import com.keshav.daptap.core.Type4Tag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NameDrop (com.apple.boop SNAP) card. Expected bytes come from namedrop-re-public's
 * scripts/build-snap-serverinfo.py run on the same fixed identity, so DapTap answers byte-for-byte what
 * that project verified against real iPhones.
 */
class SnapTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val id = SnapIdentity(
        spki = hex("3059301306072a8648ce3d020106082a8648ce3d03010703420004" + "11".repeat(64)),
        listenerUuid = hex("0f1e2d3c4b5a49788695a4b3c2d1e0ff"),
        token = hex("010203040506"),
        signature = hex("ab".repeat(64)),
    )
    private val blobs = SnapBlobs(id)

    @Test fun `blobs match the reference generator`() {
        assertEquals(SERVERINFO, blobs.serverInfo.hex())
        assertEquals(238, blobs.serverInfo.size)
        assertEquals(SI_HEAD, blobs.head.hex())
        assertEquals(SI_TAIL, blobs.tail.hex())
        assertEquals(18, blobs.tailTsOffset)
        assertEquals(CAPS, blobs.caps.hex())
        assertEquals(85, blobs.caps.size)
        assertEquals(REDIRECT, blobs.redirect.hex())
        assertEquals("0F1E2D3C-4B5A-4978-8695-A4B3C2D1E0FF", id.uuidString)
    }

    /** What an iPhone pushes: the same ServerInfo shape with its own label, timestamp and UUID. */
    private fun iphoneServerInfo(label: String, ts: ByteArray, uuid: ByteArray): ByteArray = Cbor.encode(linkedMapOf(
        0 to "1.1", 1 to label, 2 to listOf(linkedMapOf(0 to "Bonjour", 1 to 0)),
        3 to linkedMapOf(0 to ts, 1 to hex("3059301306072a8648ce3d020106082a8648ce3d03010703420004" + "22".repeat(64)),
            2 to uuid, 3 to hex("0a0b0c0d0e0f"), 4 to ByteArray(64) { 7 }, 5 to 1),
    ))

    private fun getData(p2: Int, body: ByteArray) = byteArrayOf(0x00, 0xCA.toByte(), 0x01, p2.toByte(), body.size.toByte()) + body + 0x00
    private val selectBoop = hex("00a4040009a000000858044f010000")

    @Test fun `full two-activation handshake as an iPhone runs it`() {
        val applet = SnapApplet(blobs)
        val ts = hex("01020304")
        val theirUuid = hex("aabbccddeeff00112233445566778899")

        // Activation 1: select, ServerInfo swap, capabilities push -> redirect.
        val sel = applet.dispatch(selectBoop)!!
        assertEquals(SnapApplet.Event.BOOP_SELECTED, sel.event)
        assertEquals("a503ce01019000", sel.response.hex())

        val theirs = iphoneServerInfo(Snap.LABEL_NAMEDROP, ts, theirUuid)
        assertEquals(238, theirs.size)
        val si = applet.dispatch(getData(0x03, theirs))!!
        assertEquals(SnapApplet.Event.SERVER_INFO, si.event)
        assertEquals("AABBCCDD-EEFF-0011-2233-445566778899", applet.peerUuid)
        assertEquals(Snap.LABEL_NAMEDROP, applet.peerLabel)
        // Our ServerInfo with their timestamp echoed, then 9000.
        val expected = blobs.serverInfo.copyOf().also { ts.copyInto(it, Snap.indexOf(it, SnapBlobs.PLACEHOLDER_TS)) }
        assertArrayEquals(expected + byteArrayOf(0x90.toByte(), 0x00), si.response)

        val caps = ByteArray(85) { it.toByte() }
        val redirect = applet.dispatch(getData(0x03, caps))!!
        assertEquals(SnapApplet.Event.CAPS_PUSH, redirect.event)
        assertEquals(REDIRECT + "6a88", redirect.response.hex())
        // Within the same activation a resume hands their capabilities back verbatim.
        assertArrayEquals(caps + byteArrayOf(0x90.toByte(), 0x00), applet.dispatch(getData(0x04, hex("1a2b")))!!.response)

        // Activation 2 (fresh state): select again, resume -> capabilities.
        applet.reset()
        applet.dispatch(selectBoop)
        val resume = applet.dispatch(getData(0x04, hex("02195aa5")))!!
        assertEquals(SnapApplet.Event.RESUMED, resume.event)
        assertEquals(CAPS + "9000", resume.response.hex())
    }

    @Test fun `share-sheet label is echoed, making a 248-byte answer`() {
        val applet = SnapApplet(blobs)
        applet.dispatch(selectBoop)
        val theirs = iphoneServerInfo("com.apple.airdrop.sharesheet", hex("0a0b0c0d"), ByteArray(16) { 1 })
        val r = applet.dispatch(getData(0x03, theirs))!!
        assertEquals("com.apple.airdrop.sharesheet", applet.peerLabel)
        assertEquals(248 + 2, r.response.size)
        assertTrue(r.response.copyOfRange(0, 248).hex().contains("0a0b0c0d"))
    }

    @Test fun `probe after select gets our ServerInfo`() {
        val applet = SnapApplet(blobs)
        applet.dispatch(selectBoop)
        val r = applet.dispatch(byteArrayOf(0x02))!!
        assertEquals(SnapApplet.Event.PROBE, r.event)
        assertEquals(SERVERINFO + "9000", r.response.hex())
    }

    @Test fun `nothing but a boop select is ours until the applet is selected`() {
        val applet = SnapApplet(blobs)
        assertNull(applet.dispatch(byteArrayOf(0x02)))
        assertNull(applet.dispatch(getData(0x04, hex("00"))))
        assertNull(applet.dispatch(hex("00a4040007d276000085010100")))
        applet.dispatch(selectBoop)
        // Selecting another application deselects boop.
        assertNull(applet.dispatch(hex("00a4040007d276000085010100")))
        assertNull(applet.dispatch(byteArrayOf(0x02)))
    }

    @Test fun `NDEF is refused to an unknown reader but served to a DapTap reader`() {
        var refuse = true
        val tag = Type4Tag(messageProvider = { byteArrayOf(0xD0.toByte(), 0, 0) }, refuseNdef = { daptap -> refuse && !daptap })
        val selectNdef = hex("00a4040007d276000085010100")
        assertEquals("6a82", tag.process(selectNdef).hex())
        assertEquals("6a82", tag.process(hex("00a4000c02e103")).hex())
        // DapTap's hello lets it in.
        assertEquals("9000", tag.process(hex("00a4040007f044415054415000")).hex())
        assertEquals("9000", tag.process(selectNdef).hex())
        tag.reset()
        assertEquals("6a82", tag.process(selectNdef).hex())
        refuse = false
        assertEquals("9000", tag.process(selectNdef).hex())
    }

    companion object {
        private const val SERVERINFO =
            "a40063312e310173636f6d2e6170706c652e626f6f702e534e41500281a20067426f6e6a6f7572010003a60044aabbccdd01" +
            "585b3059301306072a8648ce3d020106082a8648ce3d03010703420004111111111111111111111111111111111111111111" +
            "1111111111111111111111111111111111111111111111111111111111111111111111111111111111111102500f1e2d3c4b" +
            "5a49788695a4b3c2d1e0ff0346010203040506045840abababababababababababababababababababababababababababab" +
            "abababababababababababababababababababababababababababababababababababab0501"
        private const val SI_HEAD =
            "a40063312e3101"
        private const val SI_TAIL =
            "0281a20067426f6e6a6f7572010003a60044aabbccdd01585b3059301306072a8648ce3d020106082a8648ce3d0301070342" +
            "0004111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111" +
            "1111111111111111111111111111111102500f1e2d3c4b5a49788695a4b3c2d1e0ff0346010203040506045840ababababab" +
            "abababababababababababababababababababababababababababababababababababababababababababababababababab" +
            "ababababababababab0501"
        private const val CAPS =
            "a40063312e310173636f6d2e6170706c652e626f6f702e534e4150028003a305027252504b6e6f776e4964656e746974794b" +
            "657900781d5250537570706f7274734170706c69636174696f6e4c6162656c4b657901"
        private const val REDIRECT =
            "a2011902cf02195aa5"
    }
}
