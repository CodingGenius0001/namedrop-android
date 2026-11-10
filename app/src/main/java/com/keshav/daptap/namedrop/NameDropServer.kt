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
import java.net.DatagramPacket
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
    /** A socket that can use the Apple link (see the applink package) and how it does; else we bind our own. */
    private val linkSocket: () -> Pair<DatagramSocket, String>? = { null },
    /** An iPhone finished the QUIC/TLS handshake with us. */
    /** Returns the swap this connection belongs to, handed back in [onFinished]. */
    private val onConnected: () -> Int = { 0 },
    /** The swap is over from our side: our /Exchange reply was delivered (or given up on) and the connection closed. */
    private val onFinished: (why: String, swap: Int) -> Unit = { _, _ -> },
    /** A request's headers arrived (its body may still be on the way), with its path. */
    private val onRequest: (path: String) -> Unit = {},
    /** Testing mode: log kwik's loss recovery and per-connection stats. */
    private val verbose: () -> Boolean = { false },
) {
    private var connector: ServerConnector? = null
    private var socket: DatagramSocket? = null
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "daptap-namedrop-h3").apply { isDaemon = true } }

    @Synchronized
    fun start(): Boolean {
        if (connector != null) return true
        return runCatching {
            // kwik waits up to 30 s for connections to close on shutdown; a half-open one (an iPhone that walked
            // away) would hold that long. One second is plenty here.
            tech.kwik.core.server.impl.ServerConnectorImpl.DEFAULT_CLOSE_TIMEOUT_IN_SECONDS = 1
            // iPhones key the TLS handshake with X25519; Android's provider can't take it the way kwik asks.
            val xdh = XdhProvider.installIfNeeded()
            if (!xdhLogged) { xdhLogged = true; log("NameDrop server: X25519 $xdh") }
            val certDer = SelfCert.build(spki, privateKey, hostname)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
            val pw = "daptap".toCharArray()
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("namedrop", privateKey, pw, arrayOf(cert)) }
            val link = runCatching { linkSocket() }.onFailure { log("NameDrop server: no socket for the iPhone link (${it.message})") }.getOrNull()
            val s = link?.first ?: PlainSocket(log).apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
            runCatching { s.receiveBufferSize = 4 shl 20 }
            val config = ServerConnectionConfig.builder()
                .maxIdleTimeoutInSeconds(30)
                .maxOpenPeerInitiatedBidirectionalStreams(16)
                .maxOpenPeerInitiatedUnidirectionalStreams(4)
                // An iPhone's card with its Contact Poster runs to several MB: let it all come without waiting for credit.
                .maxConnectionBufferSize(32L shl 20)
                .maxBidirectionalStreamBufferSize(16L shl 20)
                .maxUnidirectionalStreamBufferSize(64L shl 10)
                .build()
            val c = ServerConnector.builder()
                .withPort(PORT) // required by the builder even when a socket is given
                .withSocket(s)
                .withKeyStore(ks, "namedrop", pw, "secp256r1")
                .withSupportedVersion(QuicConnection.QuicVersion.V1)
                .withConfiguration(config)
                .withLogger(KwikLog(log, verbose()))
                .build()
            c.registerApplicationProtocol("h3", Factory())
            c.start()
            socket = s
            connector = c
            log("NameDrop server listening on UDP $PORT (${link?.second ?: "no iPhone link"})")
            warmUp(certDer)
            true
        }.getOrElse { log("NameDrop server failed to start: ${it.javaClass.simpleName}: ${it.message}"); stop(); false }
    }

    fun restart() { if (connector != null) { stop(); start() } }

    /** Stops the server. Closing waits (briefly) for connections to wind down, so call it off the main thread. */
    fun stop() {
        val c: ServerConnector?; val sock: DatagramSocket?
        synchronized(this) { c = connector; sock = socket; connector = null; socket = null }
        runCatching { c?.close() }
        runCatching { sock?.close() }
    }

    /**
     * The first iPhone after a cold start paid for loading and JIT-ing the TLS and crypto code inside its handshake
     * (0.2-0.5 s, which also inflates the iPhone's retransmit timer). Run that code once now, off to the side.
     */
    private fun warmUp(certDer: ByteArray) {
        if (warmed) return
        warmed = true
        workers.execute {
            runCatching {
                val t0 = System.nanoTime()
                val xdh = java.security.KeyPairGenerator.getInstance("XDH")
                xdh.initialize(java.security.spec.NamedParameterSpec.X25519)
                val a = xdh.generateKeyPair(); val b = xdh.generateKeyPair()
                javax.crypto.KeyAgreement.getInstance("XDH").apply { init(a.private); doPhase(b.public, true); generateSecret() }
                java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey); update(certDer); sign() }
                val key = javax.crypto.spec.SecretKeySpec(ByteArray(16), "AES")
                javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, ByteArray(12))); doFinal(ByteArray(32))
                }
                javax.crypto.Cipher.getInstance("AES/ECB/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key); doFinal(ByteArray(16)) }
                javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(ByteArray(32), "HmacSHA256")); doFinal(ByteArray(32)) }
                java.security.MessageDigest.getInstance("SHA-256").digest(certDer)
                Encoder.newBuilder().build().compressHeaders(listOf<Map.Entry<String, String>>(AbstractMap.SimpleEntry(":status", "200")))
                com.dd.plist.PropertyListParser.parse(com.dd.plist.BinaryPropertyListWriter.writeToArray(com.dd.plist.NSDictionary().apply { put("a", 1) }))
                log("NameDrop server: crypto warmed up in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }.onFailure { log("NameDrop server: warm-up skipped (${it.javaClass.simpleName}: ${it.message})") }
        }
    }

    private inner class Factory : ApplicationProtocolConnectionFactory {
        override fun createConnection(protocol: String, quicConnection: QuicConnection): ApplicationProtocolConnection {
            log("NameDrop: iPhone connected (TLS done, $protocol)")
            val swap = runCatching { onConnected() }.getOrDefault(0)
            return H3(quicConnection, swap).also { live += it }
        }
        override fun maxConcurrentPeerInitiatedUnidirectionalStreams() = 4
        override fun maxTotalPeerInitiatedUnidirectionalStreams() = 16L
        override fun maxConcurrentPeerInitiatedBidirectionalStreams() = 16
    }

    /** One HTTP/3 connection: our control stream out, the iPhone's control/QPACK streams drained, requests answered. */
    /** Connections still open, so a swap that's given up on can close them (see [closeConnections]). */
    private val live = java.util.concurrent.CopyOnWriteArraySet<H3>()

    /** Closes every open connection quietly (their swap is over). */
    fun closeConnections() { live.forEach { it.closeQuietly() } }

    private inner class H3(private val quic: QuicConnection, private val swap: Int) : ApplicationProtocolConnection {
        private val decoder = Decoder.newBuilder().build()
        private val encoder = Encoder.newBuilder().build()

        @Volatile private var finished = false
        /** We're closing after /Exchange; the disconnect that follows is ours. */
        @Volatile private var closing = false
        /** /Exchange was answered: a disconnect now just means the swap is done. */
        @Volatile private var answeredExchange = false

        init {
            runCatching {
                quic.setConnectionListener(object : tech.kwik.core.ConnectionListener {
                    override fun disconnected(e: tech.kwik.core.ConnectionTerminatedEvent) {
                        live -= this@H3
                        if (closing) return
                        if (verbose() || !finished) log("NameDrop: connection ended (${e.closeReason()}${if (e.closedByPeer()) ", by the iPhone" else ""}) · ${stats()}")
                        // The iPhone gave up or cancelled before /Exchange: the swap is over too.
                        finish(if (answeredExchange) "exchange finished" else "the iPhone ended the connection")
                    }
                })
            }
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

        /**
         * The iPhone's one-way streams (control, QPACK) carry nothing the exchange needs, but in testing mode what
         * arrives on them is logged: a GOAWAY or a late SETTINGS is the kind of thing a stalled request hides.
         */
        private fun drain(input: InputStream) {
            val buf = ByteArray(4096)
            var total = 0L
            var lines = 0
            runCatching {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0 && verbose() && lines++ < MAX_STREAM_LINES) {
                        val hex = buf.take(minOf(n, 48)).joinToString("") { "%02X".format(it) }
                        log("NameDrop h3: one-way stream ${if (total == 0L) "opened, type ${buf[0].toInt() and 0xFF}" else "at $total B"}: $n B $hex${if (n > 48) "…" else ""}")
                    }
                    total += n
                }
            }
            if (verbose()) log("NameDrop h3: one-way stream ended after $total B")
        }

        private fun request(stream: QuicStream) {
            val progress = Progress()
            val input = Counting(stream.inputStream, progress)
            var headers: List<Map.Entry<String, String>> = emptyList()
            val body = ByteArrayOutputStream()
            try {
                while (true) {
                    val type = readVarint(input) ?: break
                    val len = readVarint(input) ?: break
                    if (len > MAX_FRAME) error("frame of $len bytes")
                    // Noted before the payload is read: a frame that was announced and then stalled shows as such.
                    progress.frames++
                    progress.frame = "type $type, $len B"
                    if (verbose() && type != 0x01L && progress.frames <= MAX_FRAME_LINES) log("NameDrop exchange: ${progress.path} frame ${progress.frame}")
                    val payload = readFully(input, len.toInt())
                    when (type) {
                        0x01L -> {
                            headers = decoder.decodeStream(ByteArrayInputStream(payload))
                            val path = headers.firstOrNull { it.key == ":path" }?.value ?: "?"
                            progress.path = path
                            runCatching { onRequest(path) }
                            // When the request started (vs. when it was all in): splits the iPhone's prep from the upload.
                            // The headers say whether the iPhone already knows its body's size (content-length), which
                            // it can't if it is still waiting for Share or Receive Only.
                            if (verbose()) {
                                log("NameDrop exchange: $path started (${headers.joinToString(", ") { "${it.key}=${it.value.take(80)}" }})")
                                workers.execute { watch(progress) }
                            }
                        }
                        0x00L -> body.write(payload)
                        else -> Unit // reserved / unknown frame types are ignored (RFC 9114 9)
                    }
                }
            } finally {
                progress.done = true
            }
            val method = headers.firstOrNull { it.key == ":method" }?.value
            val path = headers.firstOrNull { it.key == ":path" }?.value
            val resp = runCatching { exchange.handle(method, path, body.toByteArray()) }
                .getOrElse { log("NameDrop exchange: $path failed (${it.javaClass.simpleName}: ${it.message}), answered 500"); Exchange.Response(500, ByteArray(0)) }
            val sentBefore = runCatching { quic.stats.bytesSent() }.getOrDefault(0L)
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
            if (resp.closeAfter) { answeredExchange = true; workers.execute { closeWhenDelivered(sentBefore, resp.body.size) } }
        }

        /** Testing mode: while a request's body hasn't arrived, say every few seconds how far it got. */
        private fun watch(p: Progress) {
            var lines = 0
            while (!p.done && !finished && lines < MAX_WAIT_LINES) {
                runCatching { Thread.sleep(WAIT_LOG_MS) }
                if (p.done || finished) return
                lines++
                val idle = (System.nanoTime() - p.lastAt) / 1_000_000
                log("NameDrop exchange: ${p.path} still open after ${(System.nanoTime() - p.startedAt) / 1_000_000_000} s: " +
                    "${p.bytes} B in, ${p.frames} frames (last: ${p.frame}), nothing for $idle ms · ${stats()}")
            }
        }

        /**
         * Close cleanly once our reply (our card) has reached the iPhone, so it retires the session and the next tap
         * works. The iPhone waits for our close before it finishes, and the Apple link goes down right after, so
         * this closes as soon as everything sent is acknowledged instead of after a fixed second.
         */
        private fun closeWhenDelivered(sentBefore: Long, replyBytes: Int) {
            val t0 = System.nanoTime()
            fun ms() = (System.nanoTime() - t0) / 1_000_000
            var how = "after ${CLOSE_MAX_MS} ms (not all acknowledged)"
            if (tech.kwik.core.server.impl.DapTapKwik.bytesInFlight(quic) < 0) {
                Thread.sleep(1_000); how = "after 1 s"
            } else {
                var clear = 0
                while (ms() < CLOSE_MAX_MS) {
                    Thread.sleep(15)
                    val sent = runCatching { quic.stats.bytesSent() - sentBefore }.getOrDefault(0L)
                    val inFlight = tech.kwik.core.server.impl.DapTapKwik.bytesInFlight(quic)
                    clear = if (sent >= replyBytes && inFlight == 0L) clear + 1 else 0
                    if (clear >= 2 && ms() >= CLOSE_MIN_MS) { how = "reply acknowledged in ${ms()} ms"; break }
                }
            }
            closing = true
            runCatching { quic.close() }
            log("NameDrop: exchange finished, connection closed ($how)${if (verbose()) " · " + stats() else ""}")
            finish("exchange finished")
        }

        fun finish(why: String) {
            if (finished) return
            finished = true
            runCatching { onFinished(why, swap) }
        }

        fun closeQuietly() {
            finished = true
            closing = true
            live -= this
            runCatching { quic.close() }
        }

        private fun stats(): String = runCatching {
            val s = quic.stats
            "sent ${s.packetsSent()} packets, lost ${s.lostPackets()}, rtt ${s.smoothedRtt()}±${s.rttVar()} ms"
        }.getOrDefault("no stats")
    }

    /** How far one request has got, for the testing-mode wait log. */
    private class Progress {
        val startedAt = System.nanoTime()
        @Volatile var path = "?"
        @Volatile var bytes = 0L
        @Volatile var frames = 0
        @Volatile var frame = "none"
        @Volatile var lastAt = System.nanoTime()
        @Volatile var done = false
    }

    private class Counting(private val src: InputStream, private val p: Progress) : InputStream() {
        override fun read(): Int = src.read().also { if (it >= 0) { p.bytes++; p.lastAt = System.nanoTime() } }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            src.read(b, off, len).also { if (it > 0) { p.bytes += it; p.lastAt = System.nanoTime() } }
    }

    /** Our own socket on the NameDrop port when there's no iPhone link: sends that fail don't stop the server. */
    private class PlainSocket(private val log: (String) -> Unit) : DatagramSocket(null as java.net.SocketAddress?) {
        @Volatile private var failures = 0
        override fun send(p: DatagramPacket) {
            try {
                super.send(p)
            } catch (e: java.io.IOException) {
                // kwik treats a send exception as fatal for the whole server; a lost datagram is just a lost datagram.
                val n = ++failures
                if (n == 1 || n % 50 == 0) log("NameDrop server: couldn't send a reply ($n so far: ${e.message})")
            }
        }
    }

    /** kwik's own log: warnings always; in testing mode also loss recovery (retransmits, PTOs), capped. */
    private class KwikLog(private val out: (String) -> Unit, verbose: Boolean) : BaseLogger() {
        private var lines = 0
        private var skipping = false
        // Packets are logged as their frame lists (no payloads): what the iPhone sends while a request hangs
        // (a blocked-on-credit frame, a reset, a new stream) shows nowhere else.
        init { logWarning(true); logInfo(false); if (verbose) { logRecovery(true); logPackets(true); logFlowControl(true) } }
        override fun log(message: String) {
            // Timer bookkeeping comes with every packet; only losses, probes and warnings say anything.
            if (message.contains("loss detection timer")) return
            // A card upload is thousands of data and ack packets: past the first few hundred lines, skip those.
            if (lines >= BULK_LINES && (message.contains("StreamFrame") || message.contains("AckFrame"))) {
                if (!skipping) { skipping = true; out("kwik: (data and ack packets no longer logged)") }
                return
            }
            if (++lines <= MAX_LINES) out("kwik: $message".take(300)) else if (lines == MAX_LINES + 1) out("kwik: (more lines dropped)")
        }
        override fun log(message: String, ex: Throwable) { out("kwik: $message (${ex.javaClass.simpleName}: ${ex.message})".take(300)) }
        override fun logWithHexDump(message: String, data: ByteArray, length: Int) = log(message)
        override fun logWithHexDump(message: String, data: ByteBuffer, offset: Int, length: Int) = log(message)
    }

    companion object {
        /** The port a real iPhone advertised for `_asquic._udp`, and the one our mDNS record names. */
        const val PORT = 60192
        /** A request frame bigger than this is refused (an iPhone's card with its poster is ~3 MB). */
        private const val MAX_FRAME = 64L shl 20
        /** After /Exchange, close no sooner than this, and no later than [CLOSE_MAX_MS] even if unacknowledged. */
        private const val CLOSE_MIN_MS = 200L
        private const val CLOSE_MAX_MS = 3_000L
        private const val MAX_LINES = 700
        private const val BULK_LINES = 300
        private const val MAX_STREAM_LINES = 8
        /** Testing mode: how often an unfinished request is reported, and how many times and frames at most. */
        private const val WAIT_LOG_MS = 5_000L
        private const val MAX_WAIT_LINES = 20
        private const val MAX_FRAME_LINES = 12
        @Volatile private var xdhLogged = false
        @Volatile private var warmed = false

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
