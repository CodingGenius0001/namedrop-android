package com.keshav.daptap

import com.keshav.daptap.namedrop.Exchange
import com.keshav.daptap.namedrop.NameDropServer
import com.keshav.daptap.namedrop.SelfCert
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

class NameDropServerTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test fun `certificate parses and carries SNAP key 1 and the host`() {
        val der = SelfCert.build(keys.public.encoded, keys.private, "abc.local")
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(keys.public)
        assertTrue(cert.publicKey.encoded.contentEquals(keys.public.encoded))
        assertEquals("CN=abc.local", cert.subjectX500Principal.name)
        assertEquals("abc.local", cert.subjectAlternativeNames.single()[1])
        assertEquals(listOf("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), cert.extendedKeyUsage)
    }

    @Test fun `varints round-trip`() {
        for (v in listOf(0L, 37L, 63L, 64L, 15293L, 16383L, 16384L, 494878333L, 1L shl 40)) {
            assertEquals(v, NameDropServer.readVarint(ByteArrayInputStream(NameDropServer.varint(v))))
        }
    }

    /**
     * The full exchange against an independent QUIC/HTTP-3 stack (aioquic) acting like the iPhone. Runs when
     * DAPTAP_FAKE_IPHONE points at a Python with aioquic installed; skipped otherwise (e.g. on CI).
     */
    @Test fun `an iPhone-like client completes Hello and Exchange`() {
        val python = System.getenv("DAPTAP_FAKE_IPHONE")
        assumeTrue("set DAPTAP_FAKE_IPHONE to a python with aioquic", python != null)
        val script = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "scripts/fake-iphone-exchange.py") }.first { it.exists() }
        var received: String? = null
        val ours = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Keshav Pillarisetti\r\nEMAIL:k@example.com\r\nEND:VCARD\r\n".toByteArray()
        val exchange = Exchange("AAAABBBB-CCCC-4DDD-8EEE-FFFF00001111",
            ours = { Exchange.Card("Keshav Pillarisetti", "k@example.com", null, ours) },
            onCard = { v, _ -> received = String(v) }, log = { println(it) })
        // Use DapTap's own X25519 provider, as on Android, instead of the JDK's.
        if (System.getenv("DAPTAP_TEST_OWN_XDH") != null) java.security.Security.insertProviderAt(com.keshav.daptap.namedrop.XdhProvider(), 1)
        val server = NameDropServer(keys.public.encoded, keys.private, "aaaabbbb.local", exchange, log = { println(it) })
        assertTrue(server.start())
        try {
            val p = ProcessBuilder(python, script.path, System.getenv("DAPTAP_FAKE_IPHONE_HOST") ?: "::1", NameDropServer.PORT.toString(), keys.public.encoded.joinToString("") { "%02x".format(it) })
                .redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            println(output)
            val json = JSONObject(output.lines().last { it.startsWith("{") })
            assertTrue(json.getBoolean("cert_key_matches_snap_key1"))
            assertEquals("200", json.getString("hello_status"))
            assertEquals("AAAABBBB-CCCC-4DDD-8EEE-FFFF00001111", json.getJSONObject("hello").getJSONObject("id").getString("id"))
            assertEquals("200", json.getString("exchange_status"))
            val reply = json.getJSONObject("exchange")
            assertEquals("Keshav Pillarisetti", reply.getString("FullName"))
            assertEquals("T-1", reply.getJSONObject("TransferID").getString("id"))
            assertTrue(reply.getString("VCardData").contains("FN:Keshav Pillarisetti"))
            assertTrue(received!!.contains("FN:Jane Appleseed"))
        } finally {
            server.stop()
        }
    }
}
