package com.keshav.daptap

import com.keshav.daptap.core.Ndef
import com.keshav.daptap.core.Type4Tag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Type4TagTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun sw(r: ByteArray) = r.takeLast(2).joinToString("") { "%02X".format(it) }
    private fun data(r: ByteArray) = r.copyOfRange(0, r.size - 2)

    private val message = Ndef.encode(listOf(
        Ndef.uriRecord("https://codinggenius0001.github.io/namedrop-android/c/#" + "A".repeat(300)),
        Ndef.externalRecord(Ndef.DAPTAP_EXTERNAL_TYPE, ByteArray(3000) { 'x'.code.toByte() }),
    ))

    private class Counter : Type4Tag.Listener {
        var selected = 0; var read = 0
        override fun onReaderSelected() { selected++ }
        override fun onMessageRead() { read++ }
    }

    /** Reads the whole tag the way a reader does: CC, NLEN, then body in MLe-sized chunks. */
    private fun readAll(tag: Type4Tag, le: Int = 0, selectP2: String = "0C"): ByteArray {
        assertEquals("9000", sw(tag.process(hex("00A4040007D276000085010100"))))
        assertEquals("9000", sw(tag.process(hex("00A400${selectP2}02E103"))))
        val cc = data(tag.process(hex("00B000000F")))
        assertEquals(15, cc.size)
        val mle = ((cc[3].toInt() and 0xFF) shl 8) or (cc[4].toInt() and 0xFF)
        assertEquals("E104", "%02X%02X".format(cc[9], cc[10]))
        assertEquals("9000", sw(tag.process(hex("00A400${selectP2}02E104"))))
        val nlenResp = tag.process(hex("00B0000002"))
        val nlen = ((nlenResp[0].toInt() and 0xFF) shl 8) or (nlenResp[1].toInt() and 0xFF)
        val out = java.io.ByteArrayOutputStream()
        var offset = 2
        while (offset < nlen + 2) {
            val want = if (le == 0) minOf(mle, nlen + 2 - offset) else le
            val cmd = byteArrayOf(0x00, 0xB0.toByte(), (offset ushr 8).toByte(), offset.toByte(), want.toByte())
            val r = tag.process(cmd)
            assertEquals("9000", sw(r))
            out.write(data(r))
            offset += data(r).size
            assertTrue("reader must make progress", data(r).isNotEmpty())
        }
        return out.toByteArray()
    }

    @Test fun `iPhone-style read returns the exact NDEF message and fires callbacks once`() {
        val c = Counter()
        val tag = Type4Tag({ message }, c)
        val read = readAll(tag)
        assertArrayEquals(message, read)
        assertEquals(1, c.selected)
        assertEquals(1, c.read)
        // Reading the last chunk again in the same session must not re-fire.
        tag.process(hex("00B0000002"))
        assertEquals(1, c.read)
    }

    @Test fun `select file with P2 00 and Le 256 reads also work`() {
        val tag = Type4Tag({ message })
        assertArrayEquals(message, readAll(tag, le = 0, selectP2 = "00"))
    }

    @Test fun `small Le chunks still reassemble`() {
        val tag = Type4Tag({ message })
        assertArrayEquals(message, readAll(tag, le = 0x3B))
    }

    @Test fun `whole file in one read with Le 00 means 256 bytes`() {
        val tag = Type4Tag({ Ndef.encode(listOf(Ndef.uriRecord("https://a.b/c"))) })
        tag.process(hex("00A4040007D276000085010100"))
        tag.process(hex("00A4000C02E104"))
        val r = tag.process(hex("00B0000000"))
        assertEquals("9000", sw(r))
        val nlen = ((r[0].toInt() and 0xFF) shl 8) or (r[1].toInt() and 0xFF)
        assertEquals(nlen + 2, data(r).size)
    }

    @Test fun `unknown AID and premature file select are refused`() {
        val c = Counter()
        val tag = Type4Tag({ message }, c)
        assertEquals("6A82", sw(tag.process(hex("00A4040007A000000003101000"))))
        assertEquals("6A82", sw(tag.process(hex("00A4000C02E103"))))
        assertEquals(0, c.selected)
    }

    @Test fun `read without a selected file, bad offset, writes and junk are rejected`() {
        val tag = Type4Tag({ message })
        tag.process(hex("00A4040007D276000085010100"))
        assertEquals("6986", sw(tag.process(hex("00B000000F"))))
        tag.process(hex("00A4000C02E104"))
        assertEquals("6B00", sw(tag.process(hex("00B07FF010"))))
        assertEquals("6A86", sw(tag.process(hex("00B0FFF010"))))
        assertEquals("6982", sw(tag.process(hex("00D6000002AABB"))))
        assertEquals("6D00", sw(tag.process(hex("00CA000000"))))
        assertEquals("6700", sw(tag.process(hex("00"))))
        assertEquals("6700", sw(tag.process(null)))
        assertEquals("6700", sw(tag.process(hex("00A40400"))))
    }

    @Test fun `reset starts a fresh session with a fresh message`() {
        var n = 0
        val c = Counter()
        val tag = Type4Tag({ n++; message }, c)
        readAll(tag); tag.reset(); readAll(tag)
        assertEquals(2, n)
        assertEquals(2, c.read)
    }

    @Test fun `capability container advertises read-only and a size that fits the file`() {
        val tag = Type4Tag({ message })
        tag.process(hex("00A4040007D276000085010100"))
        tag.process(hex("00A4000C02E103"))
        val cc = data(tag.process(hex("00B000000F")))
        val max = ((cc[11].toInt() and 0xFF) shl 8) or (cc[12].toInt() and 0xFF)
        assertTrue(max >= message.size + 2)
        assertEquals(0x00, cc[13].toInt())
        assertEquals(0xFF, cc[14].toInt() and 0xFF)
        assertEquals(0x20, cc[2].toInt())
    }
}

class Type4ReaderAndEcpTest {
    @org.junit.Test fun `reader reads a DapTap tag end to end`() {
        val message = com.keshav.daptap.core.Ndef.encode(listOf(
            com.keshav.daptap.core.Ndef.externalRecord(com.keshav.daptap.core.Ndef.DAPTAP_EXTERNAL_TYPE, ByteArray(2500) { 'k'.code.toByte() })))
        val tag = com.keshav.daptap.core.Type4Tag({ message })
        val read = com.keshav.daptap.core.Type4Reader.read { tag.process(it) }
        org.junit.Assert.assertArrayEquals(message, read)
        org.junit.Assert.assertNull(com.keshav.daptap.core.Type4Reader.read { byteArrayOf(0x6A, 0x82.toByte()) })
        org.junit.Assert.assertNull(com.keshav.daptap.core.Type4Reader.read { throw java.io.IOException("tag lost") })
    }

    @org.junit.Test fun `NameDrop frame matches the documented layout and fits the annotation limit`() {
        val mac = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x69, 0x69)
        val f = com.keshav.daptap.core.AppleEcp.nameDropFrame(mac)
        org.junit.Assert.assertEquals("6a02890500010001deadbeef6969", f.joinToString("") { "%02x".format(it) })
        org.junit.Assert.assertTrue(f.size <= 16)
        org.junit.Assert.assertEquals(9, f[2].toInt() and 0x0F) // config length nibble = TCI + MAC
        val E = com.keshav.daptap.core.AppleEcp
        org.junit.Assert.assertEquals(com.keshav.daptap.core.AppleEcp.Kind.NAMEDROP, E.classify(f))
        val airdrop = byteArrayOf(0x6A, 0x02, 0x89.toByte(), 0x05, 0x00, 0x01, 0x00, 0x00, 0, 0, 0, 0, 0, 0)
        org.junit.Assert.assertEquals(com.keshav.daptap.core.AppleEcp.Kind.AIRDROP, E.classify(airdrop))
        org.junit.Assert.assertTrue(E.isIphoneSharing(airdrop))
        val transit = byteArrayOf(0x6A, 0x02, 0xC8.toByte(), 0x01, 0x00, 0x03, 0x00, 0x02, 0x79, 0, 0, 0, 0)
        org.junit.Assert.assertEquals(com.keshav.daptap.core.AppleEcp.Kind.TRANSIT, E.classify(transit))
        org.junit.Assert.assertFalse(E.isIphoneSharing(transit))
        org.junit.Assert.assertNull(E.classify(byteArrayOf(0x52)))
        org.junit.Assert.assertNull(E.classify(byteArrayOf()))
    }
}

class TwoWaySwapTest {
    @org.junit.Test fun `reader reads the tag then pushes its own card back`() {
        val theirs = com.keshav.daptap.core.Ndef.encode(listOf(com.keshav.daptap.core.Ndef.externalRecord("keshav.com:daptap", "THEIRS".toByteArray())))
        var got: ByteArray? = null
        val tag = com.keshav.daptap.core.Type4Tag({ theirs }, object : com.keshav.daptap.core.Type4Tag.Listener {
            override fun onPeerCard(payload: ByteArray) { got = payload }
        })
        val mine = ByteArray(3333) { (it % 251).toByte() }
        org.junit.Assert.assertArrayEquals(theirs, com.keshav.daptap.core.Type4Reader.read { tag.process(it) })
        org.junit.Assert.assertTrue(com.keshav.daptap.core.Type4Reader.push(mine) { tag.process(it) })
        org.junit.Assert.assertArrayEquals(mine, got)
    }

    @org.junit.Test fun `push works after the tag was reset by the system read`() {
        // Android's own tag read (the dispatch path) can leave the other phone's tag deselected.
        var got: ByteArray? = null
        val tag = com.keshav.daptap.core.Type4Tag({ byteArrayOf() }, object : com.keshav.daptap.core.Type4Tag.Listener {
            override fun onPeerCard(payload: ByteArray) { got = payload }
        })
        tag.reset()
        val mine = "MINE".toByteArray()
        org.junit.Assert.assertTrue(com.keshav.daptap.core.Type4Reader.push(mine) { tag.process(it) })
        org.junit.Assert.assertArrayEquals(mine, got)
    }

    @org.junit.Test fun `push is refused before select, out of order, or oversized`() {
        val tag = com.keshav.daptap.core.Type4Tag({ byteArrayOf() })
        fun hex(r: ByteArray) = r.takeLast(2).joinToString("") { "%02X".format(it) }
        org.junit.Assert.assertEquals("6A82", hex(tag.process(byteArrayOf(0x80.toByte(), 0xDA.toByte(), 0, 1, 1, 7))))
        tag.process(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07) + com.keshav.daptap.core.Type4Tag.NDEF_AID + 0x00)
        org.junit.Assert.assertEquals("6A86", hex(tag.process(byteArrayOf(0x80.toByte(), 0xDA.toByte(), 3, 0, 1, 7))))
        org.junit.Assert.assertFalse(com.keshav.daptap.core.Type4Reader.push(ByteArray(70_000)) { tag.process(it) })
    }
}
