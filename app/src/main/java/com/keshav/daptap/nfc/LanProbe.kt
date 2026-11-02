package com.keshav.daptap.nfc

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Experiment: after the NameDrop handshake, the iPhone looks for `<listener UUID>._asquic._udp` and opens
 * QUIC to it. iPhones normally do that over AWDL, Apple's private Wi-Fi link, which Android can't speak.
 * This advertises the same name on the ordinary Wi-Fi network and logs any QUIC packet that arrives, to
 * find out whether iOS will also look there. If it does, a QUIC server here can finish the exchange.
 *
 * Runs only while asked to (the beacon is on, or just after a handshake), then closes.
 */
class LanProbe(private val context: Context, private val log: (String) -> Unit) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var packets = 0

    @Synchronized
    fun start(instance: String) {
        if (socket != null) return
        val s = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }
            .getOrElse { log("Wi-Fi probe: couldn't open UDP $PORT (${it.message})"); return }
        socket = s
        packets = 0
        thread = Thread({ listen(s) }, "daptap-lan-probe").apply { isDaemon = true; start() }
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply { serviceName = instance; serviceType = TYPE; port = PORT }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = log("Wi-Fi probe: advertising ${i.serviceName}.$TYPE on port $PORT")
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) = log("Wi-Fi probe: mDNS registration failed ($code)")
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); registration = listener }
            .onFailure { log("Wi-Fi probe: mDNS unavailable (${it.message})") }
    }

    @Synchronized
    fun stop() {
        registration?.let { l -> runCatching { context.getSystemService(NsdManager::class.java)?.unregisterService(l) } }
        registration = null
        socket?.close()
        socket = null
        thread = null
    }

    private fun listen(s: DatagramSocket) {
        val buf = ByteArray(2048)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            if (runCatching { s.receive(p) }.isFailure) break
            packets++
            if (packets <= 3 || packets % 50 == 0) {
                val b = p.data
                // QUIC long header: top bit set; bytes 1-4 are the version (00000001 = QUIC v1).
                val quic = p.length >= 5 && (b[0].toInt() and 0x80) != 0
                val version = if (quic) "%02X%02X%02X%02X".format(b[1], b[2], b[3], b[4]) else "-"
                log("Wi-Fi probe: ${if (quic) "QUIC v$version" else "UDP"} packet #$packets (${p.length} B) from ${p.address?.hostAddress} — the iPhone is trying Wi-Fi!")
            }
        }
    }

    companion object {
        const val TYPE = "_asquic._udp"
        /** The port a real iPhone advertised. */
        const val PORT = 60192
    }
}
