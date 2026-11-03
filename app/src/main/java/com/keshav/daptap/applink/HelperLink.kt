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
        }

        override fun receive(p: DatagramPacket) {
            while (true) {
                val q = DatagramPacket(rx, rx.size)
                super.receive(q)
                val b = ByteBuffer.wrap(q.data, 0, q.length)
                if (q.length < 19 + 22 || b.get() != 'D'.code.toByte() || b.get() != 'T'.code.toByte()) continue
                val t = ByteArray(16).also { b.get(it) }
                if (!t.contentEquals(token) || b.get().toInt() != LinkHelper.RECV) continue
                val from = LinkHelper.readAddress(b)
                val n = minOf(b.remaining(), p.data.size - p.offset)
                b.get(p.data, p.offset, n)
                p.length = n
                p.socketAddress = from
                return
            }
        }
    }
}
