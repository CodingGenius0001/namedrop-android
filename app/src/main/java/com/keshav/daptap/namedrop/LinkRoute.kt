package com.keshav.daptap.namedrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Getting replies back out over the Apple link (`mosey0`). Android routes an app's unicast traffic only through
 * networks the app may use: a socket that isn't tied to one goes via the default network, so a reply to the
 * iPhone's link-local address fails with ENETUNREACH even though its packets arrive fine. If Android exposes
 * the link as a Network, binding the socket to it fixes that. This finds it, tests sends both ways, and logs
 * what the phone allows.
 */
class LinkRoute(private val context: Context, private val log: (String) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val known = HashMap<Network, String?>()
    /** Called with the Network that carries a peer-link interface whenever one appears. */
    @Volatile var onPeerNetwork: ((String, Network) -> Unit)? = null

    /** Watch every network apps may see, so the Apple link's is caught the moment it appears. */
    fun watch() {
        runCatching {
            val req = NetworkRequest.Builder().apply {
                if (Build.VERSION.SDK_INT >= 31) clearCapabilities()
                else {
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                }
            }.build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = seen(network, lp)
                override fun onLost(network: Network) {
                    val iface = synchronized(known) { known.remove(network) }
                    if (iface != null && isPeerLink(iface)) log("route: Android network $network ($iface) lost")
                }
            })
        }.onFailure { log("route: can't watch networks (${it.javaClass.simpleName}: ${it.message})") }
    }

    private fun seen(network: Network, lp: LinkProperties) {
        val iface = lp.interfaceName
        val before = synchronized(known) { known.put(network, iface) }
        if (before == iface) return
        if (iface != null && isPeerLink(iface)) {
            log("route: Android network $network carries $iface caps=${runCatching { cm.getNetworkCapabilities(network) }.getOrNull()}")
            onPeerNetwork?.invoke(iface, network)
        }
    }

    /** The Network carrying [iface], if apps can see one. */
    @Suppress("DEPRECATION")
    fun networkFor(iface: String): Network? = runCatching {
        cm.allNetworks.firstOrNull { n -> cm.getLinkProperties(n)?.interfaceName == iface }
            ?: synchronized(known) { known.entries.firstOrNull { it.value == iface }?.key }
    }.getOrNull()

    /** Testing mode: list what's visible and try a unicast send down [ni] each way we have. */
    fun probe(ni: NetworkInterface) {
        @Suppress("DEPRECATION")
        val all = runCatching {
            cm.allNetworks.joinToString("; ") { n -> "$n=${cm.getLinkProperties(n)?.interfaceName}" }
        }.getOrElse { "lookup failed (${it.message})" }
        log("route: networks apps can see: $all")
        val dst = runCatching {
            Inet6Address.getByAddress(null, ByteArray(16).also { it[0] = 0xFE.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }, ni)
        }.getOrElse { log("route: no scoped address for ${ni.name}"); return }
        log("route: plain socket -> ${trySend(dst, null)}")
        val n = networkFor(ni.name)
        if (n == null) log("route: no Android network for ${ni.name}") else log("route: socket bound to network $n -> ${trySend(dst, n)}")
    }

    private fun trySend(dst: Inet6Address, network: Network?): String = runCatching {
        DatagramSocket(null).use { s ->
            s.bind(InetSocketAddress(0))
            network?.bindSocket(s)
            s.send(DatagramPacket(byteArrayOf(0), 1, InetSocketAddress(dst, 9)))
        }
        "sent OK"
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }

    companion object {
        fun isPeerLink(name: String) = com.keshav.daptap.diag.PeerLink.isPeerLink(name)
    }
}
