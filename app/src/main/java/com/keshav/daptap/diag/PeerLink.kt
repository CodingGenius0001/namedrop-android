package com.keshav.daptap.diag

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Experiment: Quick Share's AirDrop support on the Galaxy S26 brings up a link-local interface (`mosey0`)
 * while it talks to an iPhone. This joins mDNS on that interface, logs what the iPhone says there, and
 * announces DapTap's NameDrop listener (`<UUID>._asquic._udp`) on it, answering queries for it. If the
 * iPhone then opens QUIC to us, the Wi-Fi probe's socket (bound on all interfaces) logs it.
 */
class PeerLink(private val log: (String) -> Unit, private val uuid: () -> String?) {
    private var socket: MulticastSocket? = null
    private var iface: NetworkInterface? = null
    private var seen = HashSet<String>()

    @Synchronized
    fun up(ni: NetworkInterface) {
        if (iface?.name == ni.name && socket != null) return
        down()
        val group = InetSocketAddress(InetAddress.getByName("ff02::fb"), MDNS_PORT)
        val s = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(MDNS_PORT))
                networkInterface = ni
                joinGroup(group, ni)
                timeToLive = 255
            }
        }.getOrElse { log("peer link ${ni.name}: couldn't join mDNS (${it.javaClass.simpleName}: ${it.message})"); return }
        socket = s
        iface = ni
        seen = HashSet()
        log("peer link ${ni.name}: listening for mDNS")
        Thread({ listen(s, ni) }, "daptap-peerlink-rx").apply { isDaemon = true; start() }
        Thread({ announce(s, ni) }, "daptap-peerlink-tx").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun down() {
        socket?.close()
        socket = null
        iface = null
    }

    private fun listen(s: MulticastSocket, ni: NetworkInterface) {
        val buf = ByteArray(9000)
        var packets = 0
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            if (runCatching { s.receive(p) }.isFailure) break
            packets++
            val from = p.address?.hostAddress?.substringBefore('%') ?: "?"
            if (ownAddress(ni, p.address)) continue
            val msg = runCatching { Dns.parse(p.data, p.length) }.getOrNull()
            val summary = msg?.summary() ?: "${p.length} B (unparsed)"
            // Each distinct thing the iPhone says, once; plus a running count.
            if (seen.add(summary.take(300))) log("peer link mDNS from $from: $summary")
            if (packets % 100 == 0) log("peer link: $packets mDNS packets so far")
            val id = uuid() ?: continue
            if (msg != null && !msg.response && msg.questions.any { q -> q.lowercase().let { it.startsWith("_asquic._udp") || it.contains(id.lowercase()) } }) {
                runCatching { send(s, ni, id) }
                log("peer link: iPhone asked for our NameDrop listener; answered")
            }
        }
    }

    private fun announce(s: MulticastSocket, ni: NetworkInterface) {
        var n = 0
        while (!s.isClosed) {
            val id = uuid()
            if (id != null) runCatching { send(s, ni, id) }.onFailure {
                if (n == 0) log("peer link: announce failed (${it.javaClass.simpleName}: ${it.message})")
            }.onSuccess { if (n == 0) log("peer link: announcing $id._asquic._udp on ${ni.name}") }
            n++
            Thread.sleep(ANNOUNCE_MS)
        }
    }

    private fun send(s: MulticastSocket, ni: NetworkInterface, id: String) {
        val addr = ni.inetAddresses.toList().filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress } ?: return
        val bytes = Dns.asquicResponse(id, addr.address, ASQUIC_PORT)
        s.send(DatagramPacket(bytes, bytes.size, Inet6Address.getByAddress(null, InetAddress.getByName("ff02::fb").address, ni), MDNS_PORT))
    }

    private fun ownAddress(ni: NetworkInterface, a: InetAddress?) = a != null && ni.inetAddresses.toList().any { it.address.contentEquals(a.address) }

    companion object {
        const val MDNS_PORT = 5353
        const val ASQUIC_PORT = 60192
        private const val ANNOUNCE_MS = 1_000L

        /** Interfaces Quick Share's Apple link has been seen as. */
        fun isPeerLink(name: String) = name.startsWith("mosey") || name.startsWith("awdl") || name.startsWith("llw")
    }
}

/** Just enough DNS to read mDNS traffic and announce one service. */
object Dns {
    class Message(val response: Boolean, val questions: List<String>, val answers: List<String>) {
        fun summary(): String = buildString {
            append(if (response) "response" else "query")
            if (questions.isNotEmpty()) append(" Q[").append(questions.joinToString(" ")).append(']')
            if (answers.isNotEmpty()) append(" A[").append(answers.take(8).joinToString(" ")).append(']')
        }
    }

    private val TYPES = mapOf(1 to "A", 12 to "PTR", 16 to "TXT", 28 to "AAAA", 33 to "SRV", 47 to "NSEC", 255 to "ANY")

    fun parse(b: ByteArray, len: Int): Message {
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        val flags = u16(2)
        val qd = u16(4); val an = u16(6) + u16(8) + u16(10)
        var p = 12
        fun name(start: Int): Pair<String, Int> {
            val parts = ArrayList<String>()
            var i = start; var end = -1; var hops = 0
            while (i < len && hops < 20) {
                val l = b[i].toInt() and 0xFF
                when {
                    l == 0 -> { i++; break }
                    l and 0xC0 == 0xC0 -> { if (end < 0) end = i + 2; i = ((l and 0x3F) shl 8) or (b[i + 1].toInt() and 0xFF); hops++ }
                    else -> { parts += String(b, i + 1, l, Charsets.UTF_8); i += 1 + l }
                }
            }
            return parts.joinToString(".") to (if (end >= 0) end else i)
        }
        val qs = ArrayList<String>()
        repeat(qd) {
            val (n, next) = name(p); p = next
            qs += n + "/" + (TYPES[u16(p)] ?: u16(p).toString()); p += 4
        }
        val ans = ArrayList<String>()
        repeat(an) {
            if (p >= len) return@repeat
            val (n, next) = name(p); p = next
            val type = u16(p); val rdlen = u16(p + 8); p += 10
            val data = when (type) {
                12 -> name(p).first
                33 -> name(p + 6).first + ":" + u16(p + 4)
                28 -> InetAddress.getByAddress(b.copyOfRange(p, p + 16)).hostAddress
                else -> ""
            }
            ans += "$n/${TYPES[type] ?: type}" + if (data.isNotEmpty()) "=$data" else ""
            p += rdlen
        }
        return Message(flags and 0x8000 != 0, qs, ans)
    }

    /** An unsolicited mDNS response: PTR, SRV, TXT and AAAA for `<UUID>._asquic._udp.local`, as an iPhone sends it. */
    fun asquicResponse(uuid: String, linkLocal: ByteArray, port: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Long) { for (s in 24 downTo 0 step 8) out.write((v ushr s).toInt() and 0xFF) }
        fun name(n: String) { n.split('.').forEach { l -> val bytes = l.toByteArray(); out.write(bytes.size); out.write(bytes) }; out.write(0) }
        fun nameBytes(n: String) = ByteArrayOutputStream().also { o -> n.split('.').forEach { l -> val x = l.toByteArray(); o.write(x.size); o.write(x) }; o.write(0) }.toByteArray()
        val service = "_asquic._udp.local"
        val instance = "${uuid.uppercase()}.$service"
        val host = "${uuid.lowercase()}.local"
        u16(0); u16(0x8400); u16(0); u16(4); u16(0); u16(0)
        // PTR
        name(service); u16(12); u16(1); u32(4500)
        nameBytes(instance).let { u16(it.size); out.write(it) }
        // SRV
        name(instance); u16(33); u16(0x8001); u32(120)
        val target = nameBytes(host)
        u16(6 + target.size); u16(0); u16(0); u16(port); out.write(target)
        // TXT: one empty string
        name(instance); u16(16); u16(0x8001); u32(4500); u16(1); out.write(0)
        // AAAA
        name(host); u16(28); u16(0x8001); u32(120); u16(16); out.write(linkLocal)
        return out.toByteArray()
    }
}
