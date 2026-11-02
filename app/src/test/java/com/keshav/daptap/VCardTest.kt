package com.keshav.daptap

import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.core.VCard
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VCardTest {
    private val photo = ByteArray(5000) { (it * 31).toByte() }

    @Test fun `written vcard round trips through the parser with photo`() {
        val card = ContactCard("Ana María de la Cruz", "+52 55 1234 5678", "ana@example.com", "Acme, Inc; Labs", "Lead",
            "ana.dev", "ana.cruz", 1)
        val text = VCard.write(card, photo)
        val back = VCard.parse(text)!!
        assertEquals(card.copy(label = 0, website = "https://ana.dev"), back.card)
        assertArrayEquals(photo, back.photo)
    }

    @Test fun `lines are folded at 75 octets with CRLF and survive multibyte names`() {
        val text = VCard.write(ContactCard("Ωmega ".repeat(20).trim(), email = "x@y.z"), photo)
        text.split("\r\n").forEach { assertTrue("line too long: ${it.length}", it.toByteArray().size <= 75) }
        assertTrue(text.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(text.endsWith("END:VCARD\r\n"))
        assertEquals("Ωmega ".repeat(20).trim().take(80), VCard.parse(text)!!.card.name)
    }

    @Test fun `parses an iPhone exported card`() {
        val b64 = Base64.getEncoder().encodeToString(photo)
        val text = listOf(
            "BEGIN:VCARD",
            "VERSION:3.0",
            "PRODID:-//Apple Inc.//iPhone OS 18.0//EN",
            "N:Appleseed;Johnny;;;",
            "FN:Johnny Appleseed",
            "ORG:Apple Inc.;",
            "TITLE:Engineer",
            "item1.EMAIL;type=INTERNET;type=pref:johnny@example.com",
            "item1.X-ABLabel:_\$!<Other>!\$_",
            "TEL;type=HOME;type=VOICE:(555) 111-2222",
            "TEL;type=CELL;type=VOICE;type=pref:(555) 333-4444",
            "item2.URL;type=pref:https://example.com",
            "X-SOCIALPROFILE;type=instagram;x-user=johnnyapple:http://instagram.com/johnnyapple",
            "PHOTO;ENCODING=b;TYPE=JPEG:" + b64.chunked(74).joinToString("\r\n "),
            "END:VCARD",
        ).joinToString("\r\n", postfix = "\r\n")
        val p = VCard.parse(text)!!
        assertEquals("Johnny Appleseed", p.card.name)
        assertEquals("(555) 333-4444", p.card.phone)
        assertEquals("johnny@example.com", p.card.email)
        assertEquals("Apple Inc.", p.card.company)
        assertEquals("Engineer", p.card.title)
        assertEquals("https://example.com", p.card.website)
        assertEquals("johnnyapple", p.card.instagram)
        assertArrayEquals(photo, p.photo)
    }

    @Test fun `parses vcard 2_1 quoted printable and 4_0 data uri photos`() {
        val v21 = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=4D=C3=BCller;J=C3=\r\n=BCrgen;;;\r\nTEL;CELL:123\r\nEND:VCARD\r\n"
        assertEquals("Jürgen Müller", VCard.parse(v21)!!.card.name)
        val b64 = Base64.getEncoder().encodeToString(photo)
        val v4 = "BEGIN:VCARD\nVERSION:4.0\nFN:Neo\nPHOTO:data:image/jpeg;base64,$b64\nEND:VCARD\n"
        val p = VCard.parse(v4)!!
        assertEquals("Neo", p.card.name)
        assertArrayEquals(photo, p.photo)
    }

    @Test fun `base64 padding at line end is not mistaken for a soft break`() {
        val small = ByteArray(10) { it.toByte() } // base64 ends with "=="
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Pad\r\nPHOTO;ENCODING=b;TYPE=JPEG:" +
            Base64.getEncoder().encodeToString(ByteArray(40) { 7 } + small) + "\r\nTEL:42\r\nEND:VCARD\r\n"
        val p = VCard.parse(text)!!
        assertEquals("42", p.card.phone)
        assertEquals(50, p.photo!!.size)
    }

    @Test fun `escapes are written and read back`() {
        val card = ContactCard("A, B; C\\D", company = "X;Y", title = "line1\nline2")
        val back = VCard.parse(VCard.write(card))!!.card
        assertEquals("A, B; C\\D", back.name)
        assertEquals("X;Y", back.company)
        assertEquals("line1 line2", back.title) // newlines flattened by sanitize
    }

    @Test fun `junk is rejected`() {
        assertNull(VCard.parse("hello"))
        assertNull(VCard.parse("BEGIN:VCARD\nEND:VCARD"))
        assertNull(VCard.parse(""))
    }
}
