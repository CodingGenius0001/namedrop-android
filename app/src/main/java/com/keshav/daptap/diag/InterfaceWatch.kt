package com.keshav.daptap.diag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

/**
 * Testing mode experiment: Quick Share's AirDrop support on Galaxy S26 runs Apple's peer-to-peer Wi-Fi link
 * (AWDL) on the phone's Wi-Fi chip. If Android exposes that link as a network interface an app can see (an
 * `awdl0`-style interface with an IPv6 link-local address), DapTap could serve the NameDrop exchange on it
 * without root. This logs every interface that appears or disappears, so a Quick Share session with an
 * iPhone shows whether such an interface exists.
 */
class InterfaceWatch(private val log: (String) -> Unit) {
    fun start(scope: CoroutineScope, enabled: Flow<Boolean>) {
        scope.launch {
            enabled.collectLatest { on ->
                if (!on) return@collectLatest
                var last: Map<String, String>? = null
                while (true) {
                    val now = withContext(Dispatchers.IO) { snapshot() }
                    val before = last
                    if (before == null) {
                        log("interfaces: " + now.entries.joinToString("; ") { "${it.key} ${it.value}" })
                    } else {
                        for ((name, desc) in now) if (before[name] != desc) log("interface ${if (name in before) "changed" else "UP"}: $name $desc")
                        for (name in before.keys - now.keys) log("interface gone: $name")
                    }
                    last = now
                    delay(1_500)
                }
            }
        }
    }

    private fun snapshot(): Map<String, String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { !it.isLoopback }
            .associate { ni ->
                val addrs = ni.inetAddresses.toList().joinToString(",") { a -> a.hostAddress?.substringBefore('%') ?: "?" }
                ni.name to "[${if (ni.isUp) "up" else "down"}${if (ni.isPointToPoint) ",p2p" else ""} mtu ${runCatching { ni.mtu }.getOrDefault(0)}] $addrs"
            }
    }.getOrElse { mapOf("error" to (it.message ?: it.javaClass.simpleName)) }
}
