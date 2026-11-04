package com.keshav.daptap.applink

import com.keshav.daptap.helper.LinkHelper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/** DapTap's side of the link helper's loopback protocol (see [LinkHelper]). */
class HelperLink(private val token: ByteArray) {
    private val helper = InetSocketAddress(InetAddress.getByName("::1"), LinkHelper.CONTROL_PORT)

    data class Status(val protocol: Int, val uid: Int, val portHeld: Boolean, val lastError: String)

    /** The running helper's status, or null if none answers. */
    fun ping(timeoutMs: Int = 400): Status? = request(LinkHelper.PING, ByteArray(0), timeoutMs)?.let { b ->
        Status(b.getInt(), b.getInt(), b.get().toInt() == 1, String(ByteArray(b.remaining()).also { b.get(it) }))
    }

    /** Tie the helper's NameDrop socket to the Apple link's network. */
    fun bind(networkHandle: Long): String? =
        request(LinkHelper.BIND, ByteBuffer.allocate(8).putLong(networkHandle).array(), 1_000)?.let { text(it) }

    /** Testing mode: can the helper send to [dst] (on the network with [networkHandle])? */
    fun probe(networkHandle: Long, dst: InetSocketAddress): String? {
        val b = ByteBuffer.allocate(8 + 22).putLong(networkHandle)
        LinkHelper.writeAddress(b, dst)
        return request(LinkHelper.PROBE, b.array(), 1_500)?.let { text(it) }
    }

    fun stop() { request(LinkHelper.STOP, ByteArray(0), 500) }

    /** The helper's counters (relayed, retried, failed sends, kernel drops), or null if it doesn't answer. */
    fun stats(): String? = request(LinkHelper.STATS, ByteArray(0), 500)?.let { text(it) }

    /** Called with what the helper reports unasked (sends that failed), on the relay's receive thread. */
    @Volatile var onNotice: ((String) -> Unit)? = null

    /**
     * Called (on the relay's receive thread, so it must not block) when a datagram arrives from an address we
     * haven't heard from before: an iPhone has just started talking to us.
     */
    @Volatile var onNewPeer: ((InetSocketAddress) -> Unit)? = null
    @Volatile private var lastPeer: InetSocketAddress? = null

    // Per-second datagram counts through the relay for the current swap (testing-mode diagnostics): which way a
    // stall went, in or out, shows at a glance.
    private val trafficIn = java.util.concurrent.atomic.AtomicIntegerArray(TRAFFIC_SECONDS)
    private val trafficOut = java.util.concurrent.atomic.AtomicIntegerArray(TRAFFIC_SECONDS)
    @Volatile private var trafficStart = android.os.SystemClock.elapsedRealtime()

    fun resetTraffic() {
        for (i in 0 until TRAFFIC_SECONDS) { trafficIn.set(i, 0); trafficOut.set(i, 0) }
        trafficStart = android.os.SystemClock.elapsedRealtime()
    }

    private fun count(arr: java.util.concurrent.atomic.AtomicIntegerArray) {
        val s = ((android.os.SystemClock.elapsedRealtime() - trafficStart) / 1000).toInt()
        if (s in 0 until TRAFFIC_SECONDS) arr.incrementAndGet(s)
    }

    /** "+0s 4/3 · +1s 12/9 …" up to the last second with traffic, or null if none. */
    fun traffic(): String? {
        val last = (TRAFFIC_SECONDS - 1 downTo 0).firstOrNull { trafficIn.get(it) + trafficOut.get(it) > 0 } ?: return null
        return (0..last).joinToString(" · ") { "+${it}s ${trafficIn.get(it)}/${trafficOut.get(it)}" }
    }

    private fun text(b: ByteBuffer) = String(ByteArray(b.remaining()).also { b.get(it) })

    private fun request(type: Int, body: ByteArray, timeoutMs: Int): ByteBuffer? = runCatching {
        DatagramSocket(InetSocketAddress(InetAddress.getByName("::1"), 0)).use { s ->
            s.soTimeout = timeoutMs
            val out = ByteBuffer.allocate(19 + body.size)
            LinkHelper.header(out, token, type)
            out.put(body)
            s.send(DatagramPacket(out.array(), out.position(), helper))
            val buf = ByteArray(2048)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { return@use null }
                val b = ByteBuffer.wrap(p.data, 0, p.length)
                if (p.length < 19 || b.get() != 'D'.code.toByte() || b.get() != 'T'.code.toByte()) continue
                val t = ByteArray(16).also { b.get(it) }
                if (t.contentEquals(token) && b.get().toInt() and 0xFF == (LinkHelper.REPLY or type)) return@use b.slice()
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }.getOrNull()

    /**
     * A socket for kwik that goes through the helper: what kwik sends leaves on the Apple link from the
     * NameDrop port, and what the iPhone sends there arrives here with its real address.
     */
    fun relaySocket(): DatagramSocket = RelaySocket()

    private inner class RelaySocket : DatagramSocket(InetSocketAddress(InetAddress.getByName("::1"), 0)) {
        private val rx = ByteArray(65536)

        init {
            runCatching { receiveBufferSize = 4 shl 20 }
            val out = ByteBuffer.allocate(19)
            LinkHelper.header(out, token, LinkHelper.ATTACH)
            super.send(DatagramPacket(out.array(), out.position(), helper))
        }

        override fun send(p: DatagramPacket) {
            val dst = p.socketAddress as InetSocketAddress
            val out = ByteBuffer.allocate(19 + 22 + p.length)
            LinkHelper.header(out, token, LinkHelper.SEND)
            LinkHelper.writeAddress(out, dst)
            out.put(p.data, p.offset, p.length)
            super.send(DatagramPacket(out.array(), out.position(), helper))
            count(trafficOut)
        }

        override fun receive(p: DatagramPacket) {
            while (true) {
                val q = DatagramPacket(rx, rx.size)
                super.receive(q)
                val b = ByteBuffer.wrap(q.data, 0, q.length)
                if (q.length < 19 + 22 || b.get() != 'D'.code.toByte() || b.get() != 'T'.code.toByte()) continue
                val t = ByteArray(16).also { b.get(it) }
                if (!t.contentEquals(token)) continue
                when (b.get().toInt()) {
                    LinkHelper.RECV -> Unit
                    LinkHelper.NOTICE -> { runCatching { onNotice?.invoke(text(b)) }; continue }
                    else -> continue
                }
                val from = LinkHelper.readAddress(b)
                if (from.address != lastPeer?.address) { lastPeer = from; runCatching { onNewPeer?.invoke(from) } }
                count(trafficIn)
                val n = minOf(b.remaining(), p.data.size - p.offset)
                b.get(p.data, p.offset, n)
                p.length = n
                p.socketAddress = from
                return
            }
        }
    }

    private companion object {
        const val TRAFFIC_SECONDS = 90
    }
}
