package com.keshav.daptap.applink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Finds this phone's own Wireless debugging ports, which adbd advertises over mDNS and changes often:
 * `_adb-tls-pairing._tcp` while the pairing-code dialog is open, `_adb-tls-connect._tcp` while it's on.
 * Other phones on the same Wi-Fi advertise the same services, so only ones at our own address count.
 */
object AdbDiscovery {
    const val PAIRING = "_adb-tls-pairing._tcp"
    const val CONNECT = "_adb-tls-connect._tcp"

    /** Calls [onPort] with every port of [type] this phone advertises, until the returned handle is stopped. */
    fun watch(context: Context, type: String, onPort: (Int) -> Unit): AutoCloseable {
        val nsd = context.getSystemService(NsdManager::class.java)
        val resolver = Executors.newSingleThreadExecutor()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                resolver.execute {
                    val done = java.util.concurrent.CountDownLatch(1)
                    @Suppress("DEPRECATION")
                    runCatching {
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(i: NsdServiceInfo, code: Int) { done.countDown() }
                            override fun onServiceResolved(i: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                if (isOwn(i.host)) onPort(i.port)
                                done.countDown()
                            }
                        })
                    }.onFailure { done.countDown() }
                    // Stopping the watch interrupts this wait; that's fine, just stop.
                    try { done.await(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        return AutoCloseable {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolver.shutdownNow()
        }
    }

    /** The first port of [type] this phone advertises within [timeoutMs], or null. */
    suspend fun find(context: Context, type: String, timeoutMs: Long): Int? = firstWorking(context, type, timeoutMs) { it }

    /**
     * Tries each port of [type] this phone advertises with [attempt] until one succeeds or [timeoutMs] passes.
     * mDNS can hand back a stale port from before adbd moved (e.g. Wireless debugging toggled), so a refused
     * port just means waiting for the next one.
     */
    suspend fun <T> firstWorking(context: Context, type: String, timeoutMs: Long, attempt: suspend (Int) -> T?): T? {
        val ch = Channel<Int>(Channel.UNLIMITED)
        val handle = watch(context, type) { ch.trySend(it) }
        val tried = HashSet<Int>()
        return try {
            withTimeoutOrNull(timeoutMs) {
                var result: T? = null
                while (result == null) {
                    val port = ch.receive()
                    if (tried.add(port)) result = runCatching { attempt(port) }.getOrNull()
                }
                result
            }
        } finally { handle.close() }
    }

    private fun isOwn(host: InetAddress?): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { ni -> ni.inetAddresses.toList().any { it.address.contentEquals(host.address) } }
        }.getOrDefault(false)
    }
}
