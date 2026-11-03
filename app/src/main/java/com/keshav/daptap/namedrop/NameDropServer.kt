package com.keshav.daptap.namedrop

import tech.kwik.core.QuicConnection
import tech.kwik.core.QuicStream
import tech.kwik.core.log.BaseLogger
import tech.kwik.core.server.ApplicationProtocolConnection
import tech.kwik.core.server.ApplicationProtocolConnectionFactory
import tech.kwik.core.server.ServerConnectionConfig
import tech.kwik.core.server.ServerConnector
import tech.kwik.qpack.Decoder
import tech.kwik.qpack.Encoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.AbstractMap
import java.util.concurrent.Executors

/**
 * The receiving end of NameDrop after the tap: QUIC v1 + TLS 1.3 + HTTP/3 on [PORT], which the iPhone reaches
 * over the phone's Apple link once it resolves `<UUID>._asquic._udp`. QUIC and TLS come from kwik; HTTP/3 is
 * the minimum the exchange needs (flupke's server can't run on Android: it needs java.net.http).
 */
class NameDropServer(
    private val spki: ByteArray,
    private val privateKey: PrivateKey,
    private val hostname: String,
    private val exchange: Exchange,
    private val log: (String) -> Unit,
) {
    private var connector: ServerConnector? = null
    private var socket: DatagramSocket? = null
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "daptap-namedrop-h3").apply { isDaemon = true } }

    @Synchronized
    fun start(): Boolean {
        if (connector != null) return true
        return runCatching {
            // iPhones key the TLS handshake with X25519; Android's provider can't take it the way kwik asks.
            log("NameDrop server: X25519 ${XdhProvider.installIfNeeded()}")
            val certDer = SelfCert.build(spki, privateKey, hostname)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
            val pw = "daptap".toCharArray()
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("namedrop", privateKey, pw, arrayOf(cert)) }
            val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
            val config = ServerConnectionConfig.builder()
                .maxIdleTimeoutInSeconds(30)
                .maxOpenPeerInitiatedBidirectionalStreams(16)
                .maxOpenPeerInitiatedUnidirectionalStreams(4)
                .maxConnectionBufferSize(8L shl 20)
                .maxBidirectionalStreamBufferSize(4L shl 20)
                .maxUnidirectionalStreamBufferSize(64L shl 10)
                .build()
            val c = ServerConnector.builder()
                .withPort(PORT) // required by the builder even when a socket is given
                .withSocket(s)
                .withKeyStore(ks, "namedrop", pw, "secp256r1")
                .withSupportedVersion(QuicConnection.QuicVersion.V1)
                .withConfiguration(config)
                .withLogger(KwikLog(log))
                .build()
            c.registerApplicationProtocol("h3", Factory())
            c.start()
            socket = s
            connector = c
            log("NameDrop server listening on UDP $PORT")
            true
        }.getOrElse { log("NameDrop server failed to start: ${it.javaClass.simpleName}: ${it.message}"); stop(); false }
    }

    @Synchronized
    fun stop() {
        runCatching { connector?.close() }
        runCatching { socket?.close() }
        connector = null
        socket = null
    }

    private inner class Factory : ApplicationProtocolConnectionFactory {
        override fun createConnection(protocol: String, quicConnection: QuicConnection): ApplicationProtocolConnection {
            log("NameDrop: iPhone connected (TLS done, $protocol)")
            return H3(quicConnection)
        }
        override fun maxConcurrentPeerInitiatedUnidirectionalStreams() = 4
        override fun maxTotalPeerInitiatedUnidirectionalStreams() = 16L
        override fun maxConcurrentPeerInitiatedBidirectionalStreams() = 16
    }

    /** One HTTP/3 connection: our control stream out, the iPhone's control/QPACK streams drained, requests answered. */
    private inner class H3(private val quic: QuicConnection) : ApplicationProtocolConnection {
        private val decoder = Decoder.newBuilder().build()
        private val encoder = Encoder.newBuilder().build()

        init {
            workers.execute {
                runCatching {
                    // Control stream: type 0x00, then an empty SETTINGS frame (no QPACK dynamic table). Never closed.
                    val control = quic.createStream(false)
                    control.outputStream.write(byteArrayOf(0x00, 0x04, 0x00))
                    control.outputStream.flush()
                }.onFailure { log("NameDrop h3: control stream failed (${it.message})") }
            }
        }

        override fun acceptPeerInitiatedStream(stream: QuicStream) {
            workers.execute {
                if (stream.isUnidirectional) drain(stream.inputStream) else runCatching { request(stream) }
                    .onFailure { log("NameDrop h3: request failed (${it.javaClass.simpleName}: ${it.message})") }
            }
        }

        private fun drain(input: InputStream) {
            val buf = ByteArray(4096)
            runCatching { while (input.read(buf) >= 0) Unit }
        }

        private fun request(stream: QuicStream) {
            val input = stream.inputStream
            var headers: List<Map.Entry<String, String>> = emptyList()
            val body = ByteArrayOutputStream()
            while (true) {
                val type = readVarint(input) ?: break
                val len = readVarint(input) ?: break
                val payload = readFully(input, len.toInt())
                when (type) {
                    0x01L -> headers = decoder.decodeStream(ByteArrayInputStream(payload))
                    0x00L -> body.write(payload)
                    else -> Unit // reserved / unknown frame types are ignored (RFC 9114 9)
                }
            }
            val method = headers.firstOrNull { it.key == ":method" }?.value
            val path = headers.firstOrNull { it.key == ":path" }?.value
            val resp = exchange.handle(method, path, body.toByteArray())
            val out: OutputStream = stream.outputStream
            val block = encoder.compressHeaders(listOf<Map.Entry<String, String>>(
                AbstractMap.SimpleEntry(":status", resp.status.toString()),
                AbstractMap.SimpleEntry("content-type", "application/octet-stream"),
            )).let { bb ->
                // The encoder returns its buffer with the position at the end (limit = size); read from 0.
                ByteArray(bb.limit()).also { bb.position(0); bb.get(it) }
            }
            out.write(frame(0x01, block))
            if (resp.body.isNotEmpty()) out.write(frame(0x00, resp.body))
            out.close()
            log("NameDrop exchange: answered $path with ${resp.status} (${resp.body.size} B)")
            if (resp.closeAfter) workers.execute {
                // Close cleanly once the answer is out, so the iPhone retires the session and the next tap works.
                Thread.sleep(1_000)
                runCatching { quic.close() }
                log("NameDrop: exchange finished, connection closed")
            }
        }
    }

    private class KwikLog(private val out: (String) -> Unit) : BaseLogger() {
        init { logWarning(true); logInfo(false); if (System.getProperty("daptap.kwikdebug") != null || System.getenv("DAPTAP_KWIK_DEBUG") != null) { logDebug(true); logPackets(true) } }
        override fun log(message: String) { out("kwik: $message".take(300)) }
        override fun log(message: String, ex: Throwable) { out("kwik: $message (${ex.javaClass.simpleName}: ${ex.message})".take(300)) }
        override fun logWithHexDump(message: String, data: ByteArray, length: Int) = log(message)
        override fun logWithHexDump(message: String, data: ByteBuffer, offset: Int, length: Int) = log(message)
    }

    companion object {
        /** The port a real iPhone advertised for `_asquic._udp`, and the one our mDNS record names. */
        const val PORT = 60192

        fun frame(type: Int, payload: ByteArray): ByteArray = varint(type.toLong()) + varint(payload.size.toLong()) + payload

        fun varint(v: Long): ByteArray = when {
            v < 0x40 -> byteArrayOf(v.toByte())
            v < 0x4000 -> byteArrayOf((0x40 or (v ushr 8).toInt()).toByte(), v.toByte())
            v < 0x40000000 -> ByteArray(4) { i -> (v ushr (24 - 8 * i)).toByte() }.also { it[0] = (it[0].toInt() or 0x80).toByte() }
            else -> ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }.also { it[0] = (it[0].toInt() or 0xC0).toByte() }
        }

        fun readVarint(input: InputStream): Long? {
            val first = input.read()
            if (first < 0) return null
            val len = 1 shl (first ushr 6)
            var v = (first and 0x3F).toLong()
            repeat(len - 1) { val b = input.read(); if (b < 0) return null; v = (v shl 8) or b.toLong() }
            return v
        }

        private fun readFully(input: InputStream, n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) { val r = input.read(out, off, n - off); if (r < 0) error("stream ended mid-frame"); off += r }
            return out
        }
    }
}
