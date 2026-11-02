package com.keshav.daptap

import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.core.RelayMessage
import com.keshav.daptap.core.SecretBox
import com.keshav.daptap.core.WirePayload
import com.keshav.daptap.relay.Relay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTest {
    private val card = ContactCard("Taylor Swift", "+1 555 0100", label = 3)

    @Test fun `publish posts ciphertext only to the session topic`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val relay = Relay(server.url("/").toString())
            val s = Relay.newSession()
            assertTrue(relay.publish(s, RelayMessage("card", WirePayload.of(card), from = "me")))
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/" + s.topic, req.path)
            val body = req.body.readUtf8()
            assertTrue(!body.contains("Taylor"))
            val plain = SecretBox.open(s.key, body)!!
            assertEquals(card, RelayMessage.decode(plain)!!.card!!.card())
        }
    }

    @Test fun `subscribe decrypts the stream, skipping junk, foreign boxes and repeats`() = runBlocking {
        MockWebServer().use { server ->
            val s = Relay.newSession()
            val good = SecretBox.seal(s.key, RelayMessage("card", WirePayload.of(card)).encode())
            val foreign = SecretBox.seal(SecretBox.newKey(), RelayMessage("card", WirePayload.of(card)).encode())
            val stream = listOf(
                """{"id":"o","event":"open","topic":"${s.topic}"}""",
                "garbage line",
                """{"id":"1","event":"message","message":"$foreign"}""",
                """{"id":"2","event":"keepalive"}""",
                """{"id":"3","event":"message","message":"$good"}""",
                """{"id":"3","event":"message","message":"$good"}""",
            ).joinToString("\n", postfix = "\n")
            server.enqueue(MockResponse().setBody(stream))
            val relay = Relay(server.url("/").toString())
            val got = withTimeout(5_000) { relay.subscribe(s).first() }
            assertEquals("Taylor Swift", got.card!!.name)
            val req = server.takeRequest()
            assertEquals("/" + s.topic + "/json?since=all", req.path)
        }
    }

    @Test fun `subscribe reconnects after a dropped connection`() = runBlocking {
        MockWebServer().use { server ->
            val s = Relay.newSession()
            val m1 = SecretBox.seal(s.key, RelayMessage("photo", photo = "AA").encode())
            val m2 = SecretBox.seal(s.key, RelayMessage("card", WirePayload.of(card)).encode())
            server.enqueue(MockResponse().setResponseCode(502))
            server.enqueue(MockResponse().setBody("""{"id":"a","event":"message","message":"$m1"}""" + "\n"))
            server.enqueue(MockResponse().setBody("""{"id":"a","event":"message","message":"$m1"}""" + "\n" +
                """{"id":"b","event":"message","message":"$m2"}""" + "\n"))
            val relay = Relay(server.url("/").toString())
            val got = withTimeout(15_000) { relay.subscribe(s).take(2).toList() }
            assertEquals(listOf("photo", "card"), got.map { it.type })
        }
    }

    @Test fun `session parsing validates id and key`() {
        val s = Relay.newSession()
        assertNotNull(Relay.sessionOf(s.id, s.keyText))
        assertNull(Relay.sessionOf("short", s.keyText))
        assertNull(Relay.sessionOf(s.id, "AAAA"))
        assertNull(Relay.sessionOf("bad/../id/xxxxxxxxxxxxx", s.keyText))
    }
}
