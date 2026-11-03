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
