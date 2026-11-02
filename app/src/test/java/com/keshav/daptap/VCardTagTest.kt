package com.keshav.daptap

import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.core.Ndef
import com.keshav.daptap.core.VCard
import com.keshav.daptap.core.WirePayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The tag layout phones without DapTap depend on: a plain vCard first, DapTap's record after it. */
class VCardTagTest {
    private val card = ContactCard("Keshav Pillarisetti", "+1 951 555 0142", "k@example.com", "UC Riverside", "CS student", label = 3)
    private val photo = ByteArray(1800) { (it * 7).toByte() }

    private fun message(): List<Ndef.Record> {
        val lean = WirePayload.of(card, "sess", "key")
        val bytes = Ndef.encode(listOf(
            Ndef.mimeRecord(Ndef.VCARD_MIME, VCard.write(card, photo).toByteArray(Charsets.UTF_8)),
            Ndef.externalRecord(Ndef.DAPTAP_EXTERNAL_TYPE, lean.encode().toByteArray(Charsets.US_ASCII)),
        ))
        return Ndef.decode(bytes)!!
    }

    @Test fun `first record is a text-vcard mime record so Contacts apps import it`() {
        val first = message().first()
        assertEquals(Ndef.TNF_MIME, first.tnf)
        assertEquals("text/vcard", first.typeString)
        val parsed = VCard.parse(String(first.payload, Charsets.UTF_8))!!
        assertEquals(card.name, parsed.card.name)
        assertEquals(card.phone, parsed.card.phone)
        assertArrayEquals(photo, parsed.photo)
    }

    @Test fun `a DapTap reader gets the session from its record and the photo from the vcard`() {
        val payload = Ndef.findPayload(message())!!
        assertEquals("sess", payload.session)
        assertEquals("key", payload.key)
        assertEquals(card.name, payload.card().name)
        assertArrayEquals(photo, payload.photoBytes())
    }

    @Test fun `a plain contact tag with no DapTap record still yields a card`() {
        val records = message().take(1)
        assertNull(Ndef.findPayload(records))
        assertNotNull(Ndef.findVCard(records))
        assertEquals("UC Riverside", Ndef.findVCard(records)!!.card.company)
    }
}
