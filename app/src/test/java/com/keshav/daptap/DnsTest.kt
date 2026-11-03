package com.keshav.daptap

import com.keshav.daptap.diag.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsTest {
    @Test fun `asquic announcement parses back to the records an iPhone publishes`() {
        val ll = java.net.InetAddress.getByName("fe80::1234:5678:9abc:def0").address
        val b = Dns.asquicResponse("1e177d15-b5b2-48c8-ba98-a22b02e56b53", ll, 60192)
        val m = Dns.parse(b, b.size)
        assertTrue(m.response)
        assertEquals(listOf(
            "_asquic._udp.local/PTR=1E177D15-B5B2-48C8-BA98-A22B02E56B53._asquic._udp.local",
            "1E177D15-B5B2-48C8-BA98-A22B02E56B53._asquic._udp.local/SRV=1e177d15-b5b2-48c8-ba98-a22b02e56b53.local:60192",
            "1E177D15-B5B2-48C8-BA98-A22B02E56B53._asquic._udp.local/TXT",
            "1e177d15-b5b2-48c8-ba98-a22b02e56b53.local/AAAA=fe80:0:0:0:1234:5678:9abc:def0",
        ), m.answers)
    }
}

class ExchangeNameTest {
    @Test fun `device names give the owner's name`() {
        org.junit.Assert.assertEquals("Jash", com.keshav.daptap.namedrop.Exchange.ownerOf("Jash’s iPhone"))
        org.junit.Assert.assertEquals("Mary Ann", com.keshav.daptap.namedrop.Exchange.ownerOf("Mary Ann's iPhone (2)"))
        org.junit.Assert.assertEquals("Keshav", com.keshav.daptap.namedrop.Exchange.ownerOf("Keshav’s iPad"))
        org.junit.Assert.assertEquals("Lucía", com.keshav.daptap.namedrop.Exchange.ownerOf("iPhone de Lucía"))
        org.junit.Assert.assertNull(com.keshav.daptap.namedrop.Exchange.ownerOf("iPhone"))
        org.junit.Assert.assertNull(com.keshav.daptap.namedrop.Exchange.ownerOf(null))
    }

    @Test fun `exchange falls back to the device name when the card has none`() {
        var got: String? = null
        val ex = com.keshav.daptap.namedrop.Exchange("ID", { com.keshav.daptap.namedrop.Exchange.Card("Me", null, "1", "BEGIN:VCARD\r\nEND:VCARD\r\n".toByteArray()) },
            { _, name -> got = name }, {})
        fun pl(d: com.dd.plist.NSDictionary) = com.dd.plist.BinaryPropertyListWriter.writeToArray(d)
        ex.handle("POST", "/Ask", pl(com.dd.plist.NSDictionary().apply { put("SenderComputerName", "Jash’s iPhone") }))
        ex.handle("POST", "/Exchange", pl(com.dd.plist.NSDictionary().apply { put("FullName", ""); put("VCardData", com.dd.plist.NSData("BEGIN:VCARD\r\nTEL:1\r\nEND:VCARD\r\n".toByteArray())) }))
        org.junit.Assert.assertEquals("Jash", got)
    }
}
