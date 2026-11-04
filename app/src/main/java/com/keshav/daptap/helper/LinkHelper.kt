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
    const val PROTOCOL = 4
    /** Oldest helper whose SEND/RECV/BIND/ATTACH wire format matches: it can still carry a swap. */
    const val MIN_COMPATIBLE = 2
    const val CONTROL_PORT = 60193
    const val DATA_PORT = 60192

    const val ATTACH = 1; const val PING = 2; const val BIND = 3; const val SEND = 4; const val RECV = 5
    const val STOP = 6; const val PROBE = 7
    /** Helper → app, unasked: something the app should log (a failed send). */
    const val NOTICE = 8
    /** Counters: datagrams relayed, sends failed, kernel drops on the NameDrop port. */
    const val STATS = 9
    /** The Apple link as the kernel sees it: interface counters and the iPhone's neighbour entry (protocol 4). */
    const val LINK = 10
    const val REPLY = 0x80

    @Volatile private var app: SocketAddress? = null
    @Volatile private var data: DatagramSocket? = null
    @Volatile private var lastError = ""
    @Volatile private var sent = 0L
    @Volatile private var received = 0L
    @Volatile private var failed = 0L
    @Volatile private var retried = 0L
    private var lastNoticeAt = 0L
    private var unreported = 0
    /** Kept short: this runs on the one thread that relays every reply. */
    private val RETRY_MS = longArrayOf(2, 8)
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
                    send(control, token, DatagramPacket(payload, payload.size, dst))
                }
                STATS -> reply(control, token, p.socketAddress, STATS,
                    "relayed $sent out / $received in, $retried retried, $failed failed, kernel drops ${drops()}".toByteArray())
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
                LINK -> {
                    // "iface|peer address". Reading /sys and running ip takes a few ms: off the relay thread.
                    val (iface, peer) = String(ByteArray(b.remaining()).also { b.get(it) }).split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    val to = p.socketAddress
                    Thread { reply(control, token, to, LINK, linkState(iface, peer).take(1800).toByteArray()) }.start()
                }
                STOP -> { reply(control, token, p.socketAddress, STOP, ByteArray(0)); exitProcess(0) }
            }
        }
    }

    /**
     * Sends one datagram to the iPhone. A send can fail for a moment (the link's buffers full, the link being
     * re-set up); QUIC would only notice after its retransmit timer (~2 s), so retry a few times first, and tell
     * the app about anything that still fails.
     */
    private fun send(control: DatagramSocket, token: ByteArray, p: DatagramPacket) {
        var attempt = 0
        while (true) {
            val e = runCatching { (data ?: throw java.io.IOException("no data socket yet")).send(p) }.exceptionOrNull()
            if (e == null) { sent++; if (attempt > 0) retried++; return }
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            if (attempt >= RETRY_MS.size) {
                failed++
                notice(control, token, "send to ${p.socketAddress} failed after ${attempt + 1} tries: $lastError")
                return
            }
            Thread.sleep(RETRY_MS[attempt++])
        }
    }

    /** Tells the app (rate-limited: one message per 200 ms, with a count of what was held back). */
    private fun notice(control: DatagramSocket, token: ByteArray, text: String) {
        val to = app ?: return
        val now = System.currentTimeMillis()
        if (now - lastNoticeAt < 200) { unreported++; return }
        val msg = if (unreported > 0) "$text (+$unreported more)" else text
        unreported = 0
        lastNoticeAt = now
        val body = msg.take(300).toByteArray()
        val out = ByteBuffer.allocate(19 + body.size)
        header(out, token, NOTICE)
        out.put(body)
        runCatching { control.send(DatagramPacket(out.array(), out.position(), to)) }
    }

    /** The kernel's drop counter for UDP sockets on the NameDrop port (the shell user may read it). */
    /**
     * "tx 120 txdrop 0 txerr 0 rx 98 rxdrop 0 ns 3 na 3 discards 0 | peer fe80::1 lladdr … REACHABLE | 14 neighbours": whether our
     * packets leave the phone, and whether the kernel still knows how to reach the iPhone on the link.
     */
    private fun linkState(iface: String, peer: String): String {
        if (!iface.matches(Regex("[A-Za-z0-9_.-]{1,15}"))) return "bad interface"
        // /sys/class/net is closed to the shell user; /proc/net/dev and dev_snmp6 are not.
        val dev = runCatching {
            java.io.File("/proc/net/dev").readLines().firstOrNull { it.trim().startsWith("$iface:") }
                ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))
        }.getOrNull()
        fun d(i: Int) = dev?.getOrNull(i) ?: "?"
        val snmp = runCatching {
            java.io.File("/proc/net/dev_snmp6/$iface").readLines().associate { l -> l.trim().split(Regex("\\s+")).let { it[0] to it.getOrElse(1) { "?" } } }
        }.getOrDefault(emptyMap())
        fun n(k: String) = snmp[k] ?: "?"
        val neigh = runCatching {
            val proc = ProcessBuilder("ip", "-6", "neigh", "show", "dev", iface).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrElse { listOf("ip failed: ${it.message}") }
        val bare = peer.substringBefore('%').lowercase()
        val mine = neigh.filter { bare.isNotEmpty() && it.lowercase().startsWith("$bare ") }
        // /proc/net/dev: rx bytes packets errs drop fifo frame compressed multicast, then the same for tx.
        return "tx ${d(9)} txdrop ${d(11)} txerr ${d(10)} rx ${d(1)} rxdrop ${d(3)}" +
            " ns ${n("Icmp6OutNeighborSolicits")} na ${n("Icmp6InNeighborAdvertisements")} discards ${n("Ip6OutDiscards")}" +
            " | peer ${mine.joinToString("; ").ifEmpty { if (bare.isEmpty()) "unknown" else "no entry" }} | ${neigh.size} neighbours"
    }

    private fun drops(): String = runCatching {
        val port = "%04X".format(DATA_PORT)
        (java.io.File("/proc/net/udp6").readLines() + java.io.File("/proc/net/udp").readLines())
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size > 12 && it[1].substringAfter(':') == port }
            .sumOf { it.last().toLongOrNull() ?: 0L }.toString()
    }.getOrDefault("?")

    /** Owns the NameDrop port (retrying while DapTap still holds it) and forwards what arrives. */
    private fun dataLoop(control: DatagramSocket, token: ByteArray) {
        var s: DatagramSocket? = null
        while (s == null) {
            s = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(DATA_PORT)) } }.getOrNull()
            if (s == null) Thread.sleep(1_000)
        }
        // Short, so a BIND (applied between receives) never holds up the relay for long.
        s.soTimeout = 20
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
            received++
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
