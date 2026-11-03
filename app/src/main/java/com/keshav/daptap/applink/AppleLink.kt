package com.keshav.daptap.applink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.keshav.daptap.helper.LinkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * The iPhone link: DapTap's helper, started through the phone's own Wireless debugging, which lets NameDrop
 * replies leave on the Apple link (see [LinkHelper]). Pairing happens once; after that DapTap starts the
 * helper itself whenever Wireless debugging is reachable, switching it on and off again when it may.
 */
class AppleLink(private val context: Context, private val log: (String) -> Unit, private val onHelperUp: () -> Unit) {
    enum class Phase { UNSUPPORTED, NOT_PAIRED, OFF, STOPPED, STARTING, RUNNING }
    data class State(val phase: Phase, val message: String? = null)

    private val prefs = context.getSharedPreferences("daptap_applink", Context.MODE_PRIVATE)
    private val token: ByteArray = prefs.getString("token", null)?.let(LinkHelper::hex)
        ?: ByteArray(16).also { SecureRandom().nextBytes(it); prefs.edit().putString("token", hex(it)).apply() }
    val helper = HelperLink(token)
    private val lock = Mutex()

    private val _state = MutableStateFlow(State(if (supported) Phase.STOPPED else Phase.UNSUPPORTED))
    val state: StateFlow<State> = _state

    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val paired get() = prefs.getBoolean("paired", false)
    /** The user can switch the helper off; NameDrop replies then fall back to link broadcast (experimental). */
    val enabled get() = prefs.getBoolean("enabled", true)
    /** Granted by DapTap's own shell once paired; lets it switch Wireless debugging on to restart the helper. */
    val canToggleWirelessDebugging get() =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Whether the helper is up (and the state reflects it). */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        if (!supported) return@withContext false
        val s = helper.ping()
        val ok = s != null && s.protocol == LinkHelper.PROTOCOL
        if (_state.value.phase != Phase.STARTING) {
            _state.value = when {
                !enabled && paired -> State(Phase.OFF)
                ok -> State(Phase.RUNNING)
                !paired -> State(Phase.NOT_PAIRED)
                else -> State(Phase.STOPPED, _state.value.message)
            }
        }
        ok
    }

    /** Pair with the code shown in Wireless debugging, then start the helper. */
    suspend fun pair(port: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Adb(context).pair("127.0.0.1", port, code) }
            .onSuccess { prefs.edit().putBoolean("paired", true).apply(); log("iPhone link: paired with Wireless debugging") }
            .onFailure { log("iPhone link: pairing failed (${it.message})") }
    }

    /** Start the helper if it isn't running. Needs Wi-Fi with Wireless debugging on (or switchable on). */
    suspend fun start(): Result<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!supported) return@withContext Result.failure(IllegalStateException("Needs Android 11 or newer"))
            if (!enabled) { _state.value = State(Phase.OFF); return@withContext Result.failure(IllegalStateException("turned off")) }
            helper.ping()?.let { s ->
                if (s.protocol == LinkHelper.PROTOCOL) { _state.value = State(Phase.RUNNING); return@withContext Result.success("already running") }
                helper.stop() // an older helper from a previous DapTap version
            }
            if (!paired) { _state.value = State(Phase.NOT_PAIRED); return@withContext Result.failure(IllegalStateException("not paired")) }
            _state.value = State(Phase.STARTING, "Starting the iPhone link…")
            val resolver = context.contentResolver
            // The helper lives in adbd's process group: when adbd stops, it goes too. With USB debugging on, adbd
            // keeps running when Wireless debugging goes off (Wi-Fi changes, or the Apple link taking over Wi-Fi).
            if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 0 && canToggleWirelessDebugging) {
                runCatching { Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1) }
                    .onSuccess { log("iPhone link: turned on USB debugging so the helper outlives Wireless debugging"); delay(2_000) }
            }
            val wasOn = Settings.Global.getInt(resolver, ADB_WIFI, 0) == 1
            var switchedOn = false
            val result = runCatching {
                if (!wasOn && canToggleWirelessDebugging) {
                    Settings.Global.putInt(resolver, ADB_WIFI, 1)
                    switchedOn = true
                }
                val apk = context.applicationInfo.sourceDir
                val cmd = buildString {
                    append("pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS} 2>/dev/null; ")
                    // A helper from an older DapTap (or one that stopped answering) still holds the ports. It runs
                    // under its own process name, so it can be found without matching this command line.
                    append("p=$(pidof $HELPER_NAME); [ -n \"\$p\" ] && kill \$p; sleep 0.3; ")
                    // Helpers from DapTap 1.0.24/1.0.25 ran as plain "app_process"; only kill those running our class.
                    append("for q in $(pidof app_process); do grep -q daptap /proc/\$q/cmdline 2>/dev/null && kill \$q; done; ")
                    append("CLASSPATH='$apk' setsid app_process /system/bin --nice-name=$HELPER_NAME ${LinkHelper::class.java.name} ${hex(token)} </dev/null >/dev/null 2>&1 & ")
                    // adbd kills what's left of the session when the command returns; give the helper time to
                    // detach into its own session first.
                    append("sleep 1")
                }
                var lastError: Throwable? = null
                val out = AdbDiscovery.firstWorking(context, AdbDiscovery.CONNECT, 12_000) { port ->
                    runCatching { Adb(context).shell("127.0.0.1", port, cmd) }.onFailure { e ->
                        lastError = e
                        if (e.message?.contains("pair again") == true) prefs.edit().putBoolean("paired", false).apply()
                    }.getOrNull()
                } ?: throw (lastError ?: IllegalStateException(
                    if (switchedOn || wasOn) "Wireless debugging didn't come up. Connect to Wi-Fi and try again."
                    else "Turn on Wireless debugging (Developer options), on Wi-Fi, then try again."))
                if (out.isNotBlank()) log("iPhone link: shell said ${out.trim().take(200)}")
                var s: HelperLink.Status? = null
                for (i in 0 until 25) { s = helper.ping(); if (s != null) break; delay(200) }
                s ?: error("the helper didn't start")
                "running as uid ${s.uid} (${adbState()})"
            }
            if (switchedOn) runCatching { Settings.Global.putInt(resolver, ADB_WIFI, 0) }
            result.onSuccess {
                log("iPhone link: helper $it")
                _state.value = State(Phase.RUNNING)
                onHelperUp()
            }.onFailure {
                log("iPhone link: couldn't start the helper (${it.message})")
                _state.value = State(if (paired) Phase.STOPPED else Phase.NOT_PAIRED, it.message)
            }
        }
    }

    fun setMessage(phase: Phase, message: String?) { _state.value = State(phase, message) }

    /** Switch the helper on (start it) or off (stop it; the NameDrop server then answers without it). */
    suspend fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        if (on) { log("iPhone link: turned on"); start(); return }
        withContext(Dispatchers.IO) { helper.stop() }
        log("iPhone link: turned off; replies to iPhones go out by link broadcast instead")
        _state.value = State(Phase.OFF)
        onHelperUp() // restart the NameDrop server without the relay
    }

    /** Testing mode: logs the moment the helper appears or disappears, with what adbd and Wi-Fi were doing. */
    suspend fun watch() = withContext(Dispatchers.IO) {
        var last: Boolean? = null
        while (true) {
            val up = helper.ping(600) != null
            if (up != last && (last != null || up)) log("iPhone link watchdog: helper ${if (up) "up" else "GONE"} · ${adbState()}")
            last = up
            delay(1_000)
        }
    }

    fun adbState(): String {
        val r = context.contentResolver
        fun prop(name: String) = runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream.bufferedReader().readText().trim()
        }.getOrDefault("?").ifEmpty { "-" }
        val wifi = runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.joinToString(",")
        }.getOrDefault("?")
        return "usb debugging=${Settings.Global.getInt(r, Settings.Global.ADB_ENABLED, 0)} wireless=${Settings.Global.getInt(r, ADB_WIFI, 0)} " +
            "adbd=${prop("init.svc.adbd")} usb=${prop("sys.usb.state")} nets=$wifi"
    }

    companion object {
        const val ADB_WIFI = "adb_wifi_enabled"
        const val HELPER_NAME = "daptap-link"
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
