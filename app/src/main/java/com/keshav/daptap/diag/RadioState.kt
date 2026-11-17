package com.keshav.daptap.diag

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface

/**
 * What the phone's radios and networks look like, for the tap log. The Apple link is Wi-Fi Aware, so whether Wi-Fi
 * is switched on, whether a network is joined, and whether Aware is available decide whether a swap can work at all;
 * a swap that fails off Wi-Fi needs all of it on record.
 */
object RadioState {
    /** Everything, once (start and end of a swap). Never throws. */
    fun snapshot(context: Context): String = listOf(
        "wifi radio" to { wifiRadio(context) },
        "wifi joined" to { joined(context) },
        "aware" to { aware(context) },
        "location" to { if (Build.VERSION.SDK_INT >= 28) onOff(context.getSystemService(LocationManager::class.java).isLocationEnabled) else "?" },
        "airplane" to { onOff(global(context, Settings.Global.AIRPLANE_MODE_ON) == "1") },
        "bluetooth" to { global(context, "bluetooth_on") ?: "?" },
        "wifi scanning always" to { global(context, "wifi_scan_always_enabled") ?: "?" },
        "default network" to { defaultNetwork(context) },
        "data saver" to { dataSaver(context) },
        "networks" to { networks(context) },
        "interfaces" to { interfaces() },
    ).joinToString(" · ") { (name, read) -> "$name ${runCatching(read).getOrElse { "error ${it.javaClass.simpleName}" }}" }

    /** A short line that changes when anything that matters to the link changes (sampled during a swap). */
    fun brief(context: Context): String = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }
            .filter { it.name.startsWith("wlan") || it.name.startsWith("mosey") || it.name.startsWith("aware") || it.name.startsWith("p2p") || it.name.startsWith("swlan") }
            .joinToString(",") { ni -> ni.name + "#" + ni.index + if (ni.inetAddresses.toList().any { it is java.net.Inet4Address }) "+v4" else "" }
        "wifi ${wifiRadio(context)}, aware ${aware(context)}, joined ${joined(context)}, up [$ifaces]"
    }.getOrElse { "error ${it.javaClass.simpleName}" }

    /** The Wi-Fi switch: on, off, or turning on/off. Null if it can't be read. */
    fun wifiOn(context: Context): Boolean? = runCatching { context.getSystemService(WifiManager::class.java)?.isWifiEnabled }.getOrNull()

    private fun wifiRadio(context: Context): String {
        val wm = context.getSystemService(WifiManager::class.java) ?: return "no wifi"
        return when (wm.wifiState) {
            WifiManager.WIFI_STATE_ENABLED -> "on"
            WifiManager.WIFI_STATE_DISABLED -> "off"
            WifiManager.WIFI_STATE_ENABLING -> "turning on"
            WifiManager.WIFI_STATE_DISABLING -> "turning off"
            else -> "unknown"
        }
    }

    private fun aware(context: Context): String {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return "not supported"
        val am = context.getSystemService(WifiAwareManager::class.java) ?: return "no service"
        return if (am.isAvailable) "available" else "unavailable"
    }

    /** A joined Wi-Fi network (not the Apple link, which is Wi-Fi Aware). */
    @Suppress("DEPRECATION")
    private fun joined(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.filter { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        }
        if (wifi.isEmpty()) return "no"
        return wifi.joinToString(",") { n ->
            val c = cm.getNetworkCapabilities(n)
            "${cm.getLinkProperties(n)?.interfaceName}" + if (c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) " (internet)" else " (no internet)"
        }
    }

    private fun defaultNetwork(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return "none"
        return "${cm.getLinkProperties(n)?.interfaceName} ${transports(cm.getNetworkCapabilities(n))}"
    }

    @Suppress("DEPRECATION")
    private fun networks(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.joinToString(", ") { n -> "${cm.getLinkProperties(n)?.interfaceName}=${transports(cm.getNetworkCapabilities(n))}" }.ifEmpty { "none" }
    }

    private fun dataSaver(context: Context): String = when (context.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus) {
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "on"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "on (DapTap exempt)"
        else -> "off"
    }

    private fun interfaces(): String = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .joinToString(", ") { ni -> ni.name + "#" + ni.index + if (ni.isUp) " up" else " down" }

    private fun transports(c: NetworkCapabilities?): String {
        if (c == null) return "?"
        val names = mapOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cell",
            NetworkCapabilities.TRANSPORT_WIFI_AWARE to "aware", NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bt", NetworkCapabilities.TRANSPORT_ETHERNET to "eth",
        )
        val t = names.filterKeys(c::hasTransport).values.joinToString("+").ifEmpty { "other" }
        val restricted = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        return t + if (restricted) " (restricted)" else ""
    }

    private fun global(context: Context, key: String): String? = Settings.Global.getString(context.contentResolver, key)
    private fun onOff(b: Boolean) = if (b) "on" else "off"
}
