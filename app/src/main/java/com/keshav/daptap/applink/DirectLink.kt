package com.keshav.daptap.applink

import android.net.Network
import com.keshav.daptap.namedrop.NameDropServer
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * DapTap's own socket on the NameDrop port, tied to the Apple link's network. It works once DapTap is on Android's
 * list of apps allowed on restricted networks (see [AppleLink.isDirect]), with no helper or debugging involved.
 */
class DirectLink(
    private val traffic: LinkTraffic,
    private val log: (String) -> Unit,
    /** Android refused to let DapTap onto the link, with the error it gave. */
    private val onDenied: (String) -> Unit,
) {
    /** Called (on the receive thread, so it must not block) when an iPhone we haven't heard from starts talking. */
    @Volatile var onNewPeer: ((InetSocketAddress) -> Unit)? = null

    @Volatile private var socket: DirectSocket? = null
    @Volatile private var network: Network? = null
    /** The link's interface index, or 0 when unknown. It changes whenever Samsung rebuilds the link. */
    @Volatile private var linkIndex = 0
    @Volatile private var lastPeer: InetAddress? = null

    private val sent = AtomicInteger()
    private val received = AtomicInteger()
    private val failed = AtomicInteger()
    private val readdressed = AtomicInteger()

    /** A new socket on the NameDrop port, on the link if one is up. Waits briefly for an old helper to let go of it. */
    fun socket(): DatagramSocket {
        val s = DirectSocket()
        socket = s
        network?.let { n -> log("iPhone link: new socket ${apply(n)}") }
        return s
    }

    /** Tie the socket to [network], which carries [iface]. Returns what happened, for the log. */
    fun bind(iface: String, network: Network): String {
        if (this.network != network) lastPeer = null
        this.network = network
        linkIndex = runCatching { NetworkInterface.getByName(iface)?.index }.getOrNull() ?: 0
        return apply(network)
    }

    private fun apply(network: Network): String {
        val s = socket?.takeUnless { it.isClosed } ?: return "will bind when the server opens its socket"
        return runCatching {
            network.bindSocket(s)
            "bound directly to network ${network.networkHandle} (interface index $linkIndex)"
        }.getOrElse { e ->
            val error = "${e.javaClass.simpleName}: ${e.message}"
            if (DENIED.containsMatchIn(error)) onDenied(error)
            "couldn't bind directly to network ${network.networkHandle}: $error"
        }
    }

    /** [network] went away: leave the socket as it is until the next link binds it. */
    fun lost(network: Network) {
        if (this.network == network) { this.network = null; linkIndex = 0 }
    }

    fun resetStats() {
        sent.set(0); received.set(0); failed.set(0); readdressed.set(0)
    }

    fun stats() = "sent ${sent.get()}, received ${received.get()}, failed ${failed.get()}, re-addressed ${readdressed.get()}"

    private inner class DirectSocket : DatagramSocket(null as SocketAddress?) {
        init {
            // No address reuse: a helper still shutting down must fail this bind, not share the port with us.
            var attempt = 0
            while (true) {
                try {
                    bind(InetSocketAddress(NameDropServer.PORT))
                    break
                } catch (e: BindException) {
                    if (++attempt >= BIND_ATTEMPTS) { close(); throw e }
                    Thread.sleep(BIND_RETRY_MS)
                }
            }
        }

        override fun send(p: DatagramPacket) {
            try {
                val outgoing = onCurrentLink(p)
                super.send(outgoing)
                sent.incrementAndGet()
                traffic.sent()
            } catch (e: IOException) {
                // kwik treats a send exception as fatal for the whole server; a lost datagram is just a lost datagram.
                val n = failed.incrementAndGet()
                if (n == 1 || n % 50 == 0) log("iPhone link: couldn't send to ${p.socketAddress} ($n so far: ${e.message})")
            }
        }

        /**
         * kwik keeps the address the iPhone first came from, with that interface's index. When Samsung rebuilds the
         * link the index changes, and every reply to the old one fails, so send to the same address on today's link.
         */
        private fun onCurrentLink(p: DatagramPacket): DatagramPacket {
            val dst = p.address as? Inet6Address ?: return p
            val index = linkIndex
            if (!dst.isLinkLocalAddress || index == 0 || dst.scopeId == index) return p
            if (readdressed.incrementAndGet() == 1) log("iPhone link: interface index changed (${dst.scopeId} -> $index); following it")
            return DatagramPacket(p.data, p.offset, p.length, Inet6Address.getByAddress(null, dst.address, index), p.port)
        }

        override fun receive(p: DatagramPacket) {
            super.receive(p)
            received.incrementAndGet()
            traffic.received()
            val from = p.socketAddress as? InetSocketAddress ?: return
            if (from.address.isLinkLocalAddress && from.address != lastPeer) {
                lastPeer = from.address
                runCatching { onNewPeer?.invoke(from) }
            }
        }
    }

    private companion object {
        const val BIND_ATTEMPTS = 15
        const val BIND_RETRY_MS = 100L
        val DENIED = Regex("EPERM|EACCES")
    }
}
