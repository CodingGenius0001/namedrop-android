package com.keshav.daptap.helper

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import kotlin.system.exitProcess

/**
 * DapTap's link helper: a tiny process started through Wireless debugging (`app_process`, as the shell user).
 * The shell user may send on the phone's restricted Apple link (mosey0), which DapTap itself may not. It owns
 * the NameDrop UDP port, hands every datagram the iPhone sends to DapTap over loopback, and sends DapTap's
 * replies out on the link. It does nothing else, and only talks to the holder of the token it was started with.
 *
 * Messages (UDP on [::1]:[CONTROL_PORT]): "DT" + token(16) + type(1) + body. See [HelperLink] for the app side.
 */
object LinkHelper {
    const val PROTOCOL = 2
    const val CONTROL_PORT = 60193
    const val DATA_PORT = 60192

    const val ATTACH = 1; const val PING = 2; const val BIND = 3; const val SEND = 4; const val RECV = 5
    const val STOP = 6; const val PROBE = 7
    const val REPLY = 0x80

    @Volatile private var app: SocketAddress? = null
    @Volatile private var data: DatagramSocket? = null
    @Volatile private var lastError = ""
    /** A network to tie the data socket to, applied by the data thread between receives (see [dataLoop]). */
    private val pendingBind = java.util.concurrent.SynchronousQueue<Long>()
    private val bindResult = java.util.concurrent.SynchronousQueue<String>()

    @JvmStatic
    fun main(args: Array<String>) {
        val token = hex(args.getOrNull(0) ?: error("token required"))
        val control = DatagramSocket(InetSocketAddress(InetAddress.getByName("::1"), CONTROL_PORT))
        Thread({ dataLoop(control, token) }, "data").apply { isDaemon = true; start() }
        val buf = ByteArray(65536)
        while (true) {
            val p = DatagramPacket(buf, buf.size)
            control.receive(p)
            val b = ByteBuffer.wrap(p.data, 0, p.length)
            if (p.length < 19 || b.get() != 'D'.code.toByte() || b.get() != 'T'.code.toByte()) continue
            val t = ByteArray(16).also { b.get(it) }
            if (!t.contentEquals(token)) continue
            when (b.get().toInt()) {
                ATTACH -> { app = p.socketAddress; status(control, token, p.socketAddress) }
                PING -> status(control, token, p.socketAddress)
                BIND -> {
                    // Android's DatagramSocket holds its lock while blocked in receive(), so binding it from this
                    // thread would hang the helper. The data thread does it between receives.
                    val handle = b.getLong()
                    val r = if (data == null) "no data socket yet"
                        else if (!pendingBind.offer(handle, 2, java.util.concurrent.TimeUnit.SECONDS)) "data thread busy"
                        else bindResult.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: "bind timed out"
                    reply(control, token, p.socketAddress, BIND, r.toByteArray())
                }
                SEND -> {
                    val dst = readAddress(b)
                    val payload = ByteArray(b.remaining()).also { b.get(it) }
                    runCatching { data?.send(DatagramPacket(payload, payload.size, dst)) }
                        .onFailure { lastError = "${it.javaClass.simpleName}: ${it.message}" }
                }
                PROBE -> {
                    val handle = b.getLong()
                    val dst = readAddress(b)
                    val r = runCatching {
                        DatagramSocket(null).use { s ->
                            s.bind(InetSocketAddress(0))
                            if (handle != 0L) Network.fromNetworkHandle(handle).bindSocket(s)
                            s.send(DatagramPacket(byteArrayOf(0), 1, dst))
                        }
                        "sent OK"
                    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
                    reply(control, token, p.socketAddress, PROBE, r.toByteArray())
                }
                STOP -> { reply(control, token, p.socketAddress, STOP, ByteArray(0)); exitProcess(0) }
            }
        }
    }

    /** Owns the NameDrop port (retrying while DapTap still holds it) and forwards what arrives. */
    private fun dataLoop(control: DatagramSocket, token: ByteArray) {
        var s: DatagramSocket? = null
        while (s == null) {
            s = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(DATA_PORT)) } }.getOrNull()
            if (s == null) Thread.sleep(1_000)
        }
        s.soTimeout = 250
        data = s
        val buf = ByteArray(65536)
        while (true) {
            pendingBind.poll()?.let { handle ->
                val r = runCatching { Network.fromNetworkHandle(handle).bindSocket(s); "bound to network $handle" }
                    .getOrElse { "bind failed: ${it.javaClass.simpleName}: ${it.message}" }
                bindResult.offer(r, 1, java.util.concurrent.TimeUnit.SECONDS)
            }
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { Thread.sleep(100); continue }
            val to = app ?: continue
            val from = p.socketAddress as? InetSocketAddress ?: continue
            val out = ByteBuffer.allocate(19 + 22 + p.length)
            header(out, token, RECV)
            writeAddress(out, from)
            out.put(p.data, p.offset, p.length)
            runCatching { control.send(DatagramPacket(out.array(), out.position(), to)) }
        }
    }

    private fun status(control: DatagramSocket, token: ByteArray, to: SocketAddress) {
        val body = ByteBuffer.allocate(9 + 256)
        body.putInt(PROTOCOL).putInt(android.os.Process.myUid()).put(if (data != null) 1 else 0)
        body.put(lastError.take(200).toByteArray())
        reply(control, token, to, PING, body.array().copyOf(body.position()))
    }

    private fun reply(control: DatagramSocket, token: ByteArray, to: SocketAddress, type: Int, body: ByteArray) {
        val out = ByteBuffer.allocate(19 + body.size)
        header(out, token, REPLY or type)
        out.put(body)
        runCatching { control.send(DatagramPacket(out.array(), out.position(), to)) }
    }

    fun header(out: ByteBuffer, token: ByteArray, type: Int) {
        out.put('D'.code.toByte()).put('T'.code.toByte()).put(token).put(type.toByte())
    }

    /** scope id (4), IPv6 or IPv4-mapped address (16), port (2). */
    fun writeAddress(out: ByteBuffer, a: InetSocketAddress) {
        val ip = a.address
        out.putInt((ip as? Inet6Address)?.scopeId ?: 0)
        val raw = ip.address
        if (raw.size == 4) out.put(ByteArray(10)).put(0xFF.toByte()).put(0xFF.toByte()).put(raw) else out.put(raw)
        out.putShort(a.port.toShort())
    }

    fun readAddress(b: ByteBuffer): InetSocketAddress {
        val scope = b.getInt()
        val raw = ByteArray(16).also { b.get(it) }
        val port = b.getShort().toInt() and 0xFFFF
        val v4 = raw.copyOf(10).all { it.toInt() == 0 } && raw[10] == 0xFF.toByte() && raw[11] == 0xFF.toByte()
        val ip = if (v4) InetAddress.getByAddress(raw.copyOfRange(12, 16))
            else if (scope != 0) Inet6Address.getByAddress(null, raw, scope) else InetAddress.getByAddress(raw)
        return InetSocketAddress(ip, port)
    }

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
