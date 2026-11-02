package com.keshav.daptap

import com.keshav.daptap.core.Base64Url
import com.keshav.daptap.core.CellFont
import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.core.RelayMessage
import com.keshav.daptap.core.SecretBox
import com.keshav.daptap.core.VCard
import com.keshav.daptap.core.WirePayload
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The phone and the web card must agree byte for byte: payload format, AES-GCM boxes, and the vCard
 * an iPhone imports. Runs web/test/xlang.mjs under Node with vectors produced here.
 */
class CrossLanguageTest {
    private val web = File("../web").canonicalFile

    private fun node(): String? = listOf("node", "/usr/bin/node", "/usr/local/bin/node").firstOrNull { cmd ->
        runCatching { ProcessBuilder(cmd, "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    @Test fun `kotlin and web agree on payloads, encryption and vcards`() {
        val nodeBin = node()
        // CI always has Node; skip only on a machine without it.
        assumeTrue("node not installed", nodeBin != null || System.getenv("CI") != null)
        val card = ContactCard("Zoë O'Brien-Smith", "+44 20 7946 0958", "zoe@example.co.uk", "Café, Inc; R&D", "Head of \"Things\"",
            "zoe.example", "zoe_ob", 4)
        val session = Relay.newSessionForTest()
        val url = WirePayload.webUrl("https://codinggenius0001.github.io/namedrop-android/", WirePayload.of(card, session.first, session.second))
        val key = Base64Url.decode(session.second)
        val plaintext = "secret ✓ ".repeat(40).toByteArray()
        val photo = ByteArray(1800) { (it * 7).toByte() }
        val input = JSONObject()
            .put("key", session.second).put("session", session.first)
            .put("kotlinBox", SecretBox.seal(key, plaintext)).put("plaintext", Base64Url.encode(plaintext))
            .put("url", url)
            .put("card", JSONObject().put("name", card.name).put("phone", card.phone).put("email", card.email)
                .put("company", card.company).put("title", card.title).put("website", card.website)
                .put("instagram", card.instagram).put("label", card.label))
            .put("vcard", VCard.write(card))
            .put("vcardPhoto", VCard.write(card, photo))
            .put("photo", Base64Url.encode(photo))

        val proc = ProcessBuilder(nodeBin ?: "node", File(web, "test/xlang.mjs").path).redirectErrorStream(true).start()
        proc.outputStream.use { it.write(input.toString().toByteArray()) }
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("node timed out", proc.waitFor(60, TimeUnit.SECONDS))
        val result = JSONObject(out.lines().last { it.isNotBlank() })
        assertTrue(result.optString("error"), result.getBoolean("ok"))

        assertArrayEquals(plaintext, SecretBox.open(key, result.getString("jsBox")))
        val reply = RelayMessage.decode(SecretBox.open(key, result.getString("replyBox"))!!)!!
        assertEquals("card", reply.type)
        val replyCard = reply.card!!.card()
        assertEquals("Jane  Doe", replyCard.name)
        assertEquals("+1 555 0199", replyCard.phone)
        assertEquals("Café Ünïcode", replyCard.company)
        assertEquals("jane", replyCard.instagram)
    }

    @Test fun `web cell font and grounds mirror the app`() {
        val js = File(web, "c/cellfont.js").readText()
        val glyphRe = Regex("""^\s+'(\\'|.)': \[([^\]]*)]""", RegexOption.MULTILINE)
        val jsGlyphs = glyphRe.findAll(js).associate { m ->
            val ch = m.groupValues[1].let { if (it == "\\'") '\'' else it.single() }
            ch to Regex("'([.#]+)'").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
        }
        assertEquals(CellFont.glyphs.keys, jsGlyphs.keys)
        CellFont.glyphs.forEach { (ch, rows) -> assertEquals("glyph $ch", rows, jsGlyphs[ch]) }

        val app = File(web, "c/app.js").readText()
        val jsGrounds = Regex("""\['(#[0-9A-F]{6})', '(#[0-9A-F]{6})'\]""").findAll(app).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val kt = File("src/main/java/com/keshav/daptap/ui/Theme.kt").readText()
        val ktGrounds = Regex("""Ground\("\w+", Color\(0xFF([0-9A-F]{6})\), Color\(0xFF([0-9A-F]{6})\)\)""").findAll(kt)
            .map { "#" + it.groupValues[1] to "#" + it.groupValues[2] }.toList()
        assertEquals(ContactCard.LABEL_COUNT, ktGrounds.size)
        assertEquals(ktGrounds, jsGrounds)
        assertNotNull(jsGlyphs['A'])
    }
}

private fun com.keshav.daptap.relay.Relay.Companion.newSessionForTest(): Pair<String, String> =
    newSession().let { it.id to it.keyText }

private typealias Relay = com.keshav.daptap.relay.Relay
