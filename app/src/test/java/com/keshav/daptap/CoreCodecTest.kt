package com.keshav.daptap

import com.keshav.daptap.core.Base64Url
import com.keshav.daptap.core.CellFont
import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.core.Ndef
import com.keshav.daptap.core.RelayMessage
import com.keshav.daptap.core.SecretBox
import com.keshav.daptap.core.WirePayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoreCodecTest {
    private val card = ContactCard("Keshav Pillarisetti", "+1 951 555 0142", "keshav@example.com", "UC Riverside", "CS student",
        "keshav.dev", "codinggenius0001", 2)

    @Test fun `ndef uri records compress https prefix and round trip`() {
        val url = "https://codinggenius0001.github.io/namedrop-android/c/#abc"
        val r = Ndef.uriRecord(url)
        assertEquals(4, r.payload[0].toInt())
        val decoded = Ndef.decode(Ndef.encode(listOf(r)))!!
        assertEquals(url, Ndef.decodeUri(decoded.single()))
        assertEquals("tel:+15551234", Ndef.decodeUri(Ndef.uriRecord("tel:+15551234")))
        assertEquals("https://www.x.y", Ndef.decodeUri(Ndef.uriRecord("https://www.x.y")))
    }

    @Test fun `ndef long and short records round trip with flags`() {
        val records = listOf(
            Ndef.uriRecord("https://a.b"),
            Ndef.externalRecord("keshav.com:daptap", ByteArray(700) { it.toByte() }),
            Ndef.Record(Ndef.TNF_MIME, "text/plain".toByteArray(), "hi".toByteArray(), id = "x".toByteArray()),
        )
        val bytes = Ndef.encode(records)
        assertEquals(0x80 or 0x10 or 0x01, bytes[0].toInt() and 0xFF) // MB | SR | well-known
        val back = Ndef.decode(bytes)!!
        assertEquals(records, back)
    }

    @Test fun `ndef decoder never throws on garbage`() {
        val rnd = Random(42)
        repeat(5000) {
            val junk = ByteArray(rnd.nextInt(0, 64)) { rnd.nextInt().toByte() }
            Ndef.decode(junk) // must not throw
        }
        assertNull(Ndef.decode(ByteArray(0)))
        assertNull(Ndef.decode(byteArrayOf(0xD1.toByte(), 0x01, 0x7F, 'U'.code.toByte()))) // truncated payload
    }

    @Test fun `payload is found in the external record or in a web-card url`() {
        val p = WirePayload.of(card, "sessionid0123456789AB", Base64Url.encode(ByteArray(32) { 1 }), byteArrayOf(1, 2, 3))
        val ext = Ndef.externalRecord(Ndef.DAPTAP_EXTERNAL_TYPE, p.encode().toByteArray())
        val fromExt = Ndef.findPayload(listOf(Ndef.uriRecord("https://example.com"), ext))!!
        assertEquals(card.sanitized(), fromExt.card())
        assertArrayEquals(byteArrayOf(1, 2, 3), fromExt.photoBytes())

        val url = WirePayload.webUrl("https://codinggenius0001.github.io/namedrop-android/", p.copy(photo = ""))
        assertTrue(url.startsWith("https://codinggenius0001.github.io/namedrop-android/c/#"))
        val fromUrl = Ndef.findPayload(listOf(Ndef.uriRecord(url)))!!
        assertEquals("Keshav Pillarisetti", fromUrl.name)
        assertEquals(p.session, fromUrl.session)
        assertNull(Ndef.findPayload(listOf(Ndef.uriRecord("https://example.com/#notbase64!!"))))
    }

    @Test fun `wire payload tolerates unknown keys and hostile values`() {
        val json = """{"v":1,"n":"${"A".repeat(500)}","p":"1\u0000\u0007","zz":"future","l":99}"""
        val p = WirePayload.decode(Base64Url.encode(json.toByteArray()))!!
        val c = p.card()
        assertEquals(80, c.name.length)
        assertEquals("1", c.phone)
        assertEquals(ContactCard.LABEL_COUNT - 1, c.label)
        assertNull(WirePayload.decode("%%%"))
        assertNull(WirePayload.decode(Base64Url.encode("not json".toByteArray())))
    }

    @Test fun `secret box seals, opens, and rejects tampering or wrong keys`() {
        val key = SecretBox.newKey()
        val msg = "hello DapTap ✓".toByteArray()
        val box = SecretBox.seal(key, msg)
        assertArrayEquals(msg, SecretBox.open(key, box))
        assertFalse(box == SecretBox.seal(key, msg)) // random IV
        val raw = Base64Url.decode(box); raw[raw.size - 1] = (raw.last() + 1).toByte()
        assertNull(SecretBox.open(key, Base64Url.encode(raw)))
        assertNull(SecretBox.open(SecretBox.newKey(), box))
        assertNull(SecretBox.open(key, "short"))
    }

    @Test fun `relay message round trip`() {
        val m = RelayMessage("card", WirePayload.of(card), from = "abc")
        val back = RelayMessage.decode(m.encode())!!
        assertEquals(card.sanitized(), back.card!!.card())
        assertEquals("abc", back.from)
        assertNull(RelayMessage.decode("{".toByteArray()))
    }

    @Test fun `contact card helpers`() {
        assertEquals("KP", card.initials)
        assertEquals("Keshav", card.firstName)
        assertEquals("CS student · UC Riverside", card.roleLine)
        assertEquals("C", ContactCard("cher").initials)
        assertEquals("?", ContactCard().initials)
        assertEquals("handle", ContactCard(instagram = "@handle").sanitized().instagram)
    }

    @Test fun `cell font glyphs are well formed and lay out on one line`() {
        CellFont.glyphs.forEach { (ch, rows) ->
            assertEquals("glyph $ch rows", CellFont.ROWS, rows.size)
            assertTrue("glyph $ch width", rows.all { it.length == rows[0].length })
            assertTrue("glyph $ch chars", rows.all { r -> r.all { it == '#' || it == '.' } })
        }
        ('A'..'Z').forEach { assertNotNull("missing $it", CellFont.glyphs[it]) }
        ('0'..'9').forEach { assertNotNull("missing $it", CellFont.glyphs[it]) }
        val l = CellFont.layout("Keshav")
        assertEquals(6 * 5 + 5, l.columns)
        assertTrue(l.cells.all { (c, r) -> c in 0 until l.columns && r in 0 until CellFont.ROWS })
        assertEquals("JOSE", CellFont.normalize("José"))
        assertEquals("O'NEIL", CellFont.normalize("O’Neil"))
        assertTrue(CellFont.canRender("Zoë"))
        assertFalse(CellFont.canRender("李"))
        assertFalse(CellFont.canRender(""))
    }
}
