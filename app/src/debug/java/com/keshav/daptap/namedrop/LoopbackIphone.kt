package com.keshav.daptap.namedrop

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import tech.kwik.core.QuicClientConnection
import tech.kwik.qpack.Decoder
import tech.kwik.qpack.Encoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration
import java.util.AbstractMap

/**
 * Debug only: an "iPhone" inside the app that runs the NameDrop exchange against our own server over loopback,
 * so the whole Android stack (QUIC, TLS with X25519, HTTP/3, plists) is exercised on a device.
 */
object LoopbackIphone {
    fun run(log: (String) -> Unit, nameless: Boolean = false): String = runCatching {
        val conn = QuicClientConnection.newBuilder()
            .uri(URI.create("https://localhost:${NameDropServer.PORT}"))
            .applicationProtocol("h3")
            .noServerCertificateCheck()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        conn.connect()
        // Our control stream (empty SETTINGS), as an HTTP/3 client must send.
        conn.createStream(false).outputStream.apply { write(byteArrayOf(0x00, 0x04, 0x00)); flush() }
        val hello = post(conn, "/Hello", NSDictionary().apply { put("id", NSDictionary().apply { put("id", "LOOPBACK") }); put("featureFlags", 3) })
        // nameless: what a real iPhone sent on 2026-11-02 (empty N, no FN, a Contact Poster).
        val poster = java.util.Base64.getEncoder().encodeToString(BinaryPropertyListWriter.writeToArray(NSDictionary().apply { put("version", 2) }))
        val vcard = if (nameless) "BEGIN:VCARD\r\nVERSION:3.0\r\nN:;;;;\r\nTEL:+1 408 555 0100\r\nX-WALLPAPER:$poster\r\nEND:VCARD\r\n"
            else "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Appleseed;Jane;;;\r\nFN:Jane Appleseed\r\nTEL:+1 408 555 0100\r\nEND:VCARD\r\n"
        val exchange = post(conn, "/Exchange", NSDictionary().apply {
            put("TransferID", NSDictionary().apply { put("id", "T-1") })
            put("FullName", if (nameless) "" else "Jane Appleseed")
            put("VCardData", NSData(vcard.toByteArray()))
        })
        conn.close()
        val reply = (exchange.second?.get("VCardData") as? NSData)?.bytes()?.let { String(it) }?.lines()?.firstOrNull { it.startsWith("FN:") }
        "hello ${hello.first} id=${(hello.second?.get("id") as? NSDictionary)?.get("id")}; exchange ${exchange.first} got $reply"
    }.getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }.also(log)

    private fun post(conn: QuicClientConnection, path: String, body: NSDictionary): Pair<String?, NSDictionary?> {
        val stream = conn.createStream(true)
        val headers = Encoder.newBuilder().build().compressHeaders(listOf<Map.Entry<String, String>>(
            AbstractMap.SimpleEntry(":method", "POST"), AbstractMap.SimpleEntry(":scheme", "https"), AbstractMap.SimpleEntry(":path", path),
        )).let { ByteArray(it.limit()).also { b -> it.position(0); it.get(b) } }
        val payload = BinaryPropertyListWriter.writeToArray(body)
        stream.outputStream.apply { write(NameDropServer.frame(0x01, headers)); write(NameDropServer.frame(0x00, payload)); close() }
        val input = stream.inputStream
        var status: String? = null
        val data = ByteArrayOutputStream()
        while (true) {
            val type = NameDropServer.readVarint(input) ?: break
            val len = NameDropServer.readVarint(input)!!.toInt()
            val bytes = input.readNBytes(len)
            if (type == 1L) status = Decoder.newBuilder().build().decodeStream(ByteArrayInputStream(bytes)).firstOrNull { it.key == ":status" }?.value
            if (type == 0L) data.write(bytes)
        }
        return status to (if (data.size() > 0) PropertyListParser.parse(data.toByteArray()) as? NSDictionary else null)
    }
}
