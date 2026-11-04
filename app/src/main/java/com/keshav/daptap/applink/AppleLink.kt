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
    /** [needsUser]: DapTap can't fix this itself (something keeps switching debugging off); [message] says what to do. */
    data class State(val phase: Phase, val message: String? = null, val needsUser: Boolean = false)

    private val prefs = context.getSharedPreferences("daptap_applink", Context.MODE_PRIVATE)
    private val token: ByteArray = prefs.getString("token", null)?.let(LinkHelper::hex)
        ?: ByteArray(16).also { SecureRandom().nextBytes(it); prefs.edit().putString("token", hex(it)).apply() }
    val helper = HelperLink(token)
    /** The running helper is from an older DapTap: it works, but [start] should replace it when it can. */
    @Volatile var outdated = false; private set
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

    /**
     * Experiment: Android lets the apps listed in this setting use restricted networks, which would let DapTap
     * send on the Apple link itself, with no helper or debugging. This only adds DapTap to the list and says what
     * happened; [com.keshav.daptap.namedrop.LinkRoute.probe] then logs whether a send on the link works.
     */
    fun listForRestrictedNetworks(): String = runCatching {
        if (!canToggleWirelessDebugging) return "no permission to change system settings yet"
        val resolver = context.contentResolver
        val uid = android.os.Process.myUid().toString()
        val listed = Settings.Global.getString(resolver, RESTRICTED_NETWORK_UIDS).orEmpty().split(';').filter { it.isNotBlank() }
        if (uid in listed) return "already listed (uid $uid)"
        Settings.Global.putString(resolver, RESTRICTED_NETWORK_UIDS, (listed + uid).joinToString(";"))
        val now = Settings.Global.getString(resolver, RESTRICTED_NETWORK_UIDS).orEmpty()
        if (uid in now.split(';')) "listed uid $uid" else "wrote uid $uid but the setting reads \"$now\""
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }

    /** Whether the helper is up (and the state reflects it). */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        if (!supported) return@withContext false
        val s = helper.ping()
        // A helper from an older DapTap still relays (same wire format since protocol 2); start() upgrades it.
        val ok = s != null && s.protocol in LinkHelper.MIN_COMPATIBLE..LinkHelper.PROTOCOL
        outdated = ok && s!!.protocol < LinkHelper.PROTOCOL
        if (_state.value.phase != Phase.STARTING) {
            _state.value = when {
                !enabled && paired -> State(Phase.OFF)
                ok -> State(Phase.RUNNING)
                !paired -> State(Phase.NOT_PAIRED)
                else -> State(Phase.STOPPED, _state.value.message, _state.value.needsUser)
            }
        }
        if (!ok) poke() // DapTap was opened with the link down: let the supervisor try now rather than on its timer
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
            // An older helper keeps running (and relaying) until its replacement is launched: the launch command
            // replaces it, so a failed launch never leaves the phone without one.
            val before = helper.ping()
            if (before != null && before.protocol == LinkHelper.PROTOCOL) { _state.value = State(Phase.RUNNING); return@withContext Result.success("already running") }
            if (!paired) { _state.value = State(Phase.NOT_PAIRED); return@withContext Result.failure(IllegalStateException("not paired")) }
            _state.value = State(Phase.STARTING, "Starting the iPhone link…")
            val resolver = context.contentResolver
            // The helper lives in adbd's process group: when adbd stops, it goes too. With USB debugging on, adbd
            // keeps running when Wireless debugging goes off (Wi-Fi changes, or the Apple link taking over Wi-Fi).
            if (canToggleWirelessDebugging) ensureDebugging()?.let { blocked ->
                log("iPhone link: USB debugging won't stay on (${adbState()})")
                _state.value = State(Phase.STOPPED, blocked, needsUser = true)
                return@withContext Result.failure(IllegalStateException(blocked))
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
            // Switched on, and off again by the time we got here: something is turning it off (not a Wi-Fi problem,
            // or the supervisor wouldn't have tried).
            val wirelessBlocked = result.isFailure && switchedOn && Settings.Global.getInt(resolver, ADB_WIFI, 0) == 0 && onWifi()
            if (switchedOn) runCatching { Settings.Global.putInt(resolver, ADB_WIFI, 0) }
            result.onSuccess {
                outdated = false
                log("iPhone link: helper $it")
                _state.value = State(Phase.RUNNING)
                onHelperUp()
            }.onFailure {
                log("iPhone link: couldn't start the helper (${it.message})")
                val old = helper.ping()
                if (old != null && old.protocol in LinkHelper.MIN_COMPATIBLE..LinkHelper.PROTOCOL) {
                    log("iPhone link: keeping the running helper (protocol ${old.protocol})")
                    outdated = old.protocol < LinkHelper.PROTOCOL
                    _state.value = State(Phase.RUNNING)
                } else if (wirelessBlocked) {
                    log("iPhone link: Wireless debugging won't stay on (${adbState()})")
                    _state.value = State(Phase.STOPPED, blockedMessage(), needsUser = true)
                } else {
                    _state.value = State(if (paired) Phase.STOPPED else Phase.NOT_PAIRED, it.message)
                }
            }
        }
    }

    fun setMessage(phase: Phase, message: String?) { _state.value = State(phase, message) }

    /**
     * Developer options and USB debugging on, switching them back on if something turned them off. Returns what
     * to tell the user when they won't stay on (on Samsung phones, usually Auto Blocker), else null.
     */
    private suspend fun ensureDebugging(): String? {
        val r = context.contentResolver
        val dev = Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
        val usb = Settings.Global.ADB_ENABLED
        var ok = true
        if (Settings.Global.getInt(r, dev, 0) == 0) {
            ok = runCatching { Settings.Global.putInt(r, dev, 1) }.isSuccess
            if (ok) log("iPhone link: turned Developer options back on")
        }
        if (ok && Settings.Global.getInt(r, usb, 0) == 0) {
            ok = runCatching { Settings.Global.putInt(r, usb, 1) }.isSuccess
            if (ok) { log("iPhone link: turned on USB debugging so the helper outlives Wireless debugging"); delay(2_000) }
        }
        val stuck = !ok || Settings.Global.getInt(r, dev, 0) == 0 || Settings.Global.getInt(r, usb, 0) == 0
        return if (stuck) blockedMessage() else null
    }

    private fun blockedMessage(): String =
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true))
            "Something keeps switching off USB debugging, which the iPhone link needs. On Samsung phones this is " +
                "usually Auto Blocker: turn it off in Settings › Security and privacy › Auto Blocker. DapTap reconnects by itself."
        else "Something keeps switching off USB debugging, which the iPhone link needs. Turn it back on in Developer " +
            "options and DapTap reconnects by itself."

    /** Why the helper went away, when it's something DapTap can see. */
    private fun cause(): String? {
        val r = context.contentResolver
        return when {
            Settings.Global.getInt(r, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0 -> "Developer options were turned off"
            Settings.Global.getInt(r, Settings.Global.ADB_ENABLED, 0) == 0 -> "USB debugging was turned off"
            else -> null
        }
    }

    /** Wireless debugging only runs on Wi-Fi (not the Apple link, which is Wi-Fi Aware). */
    private fun onWifi(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        }
    }.getOrDefault(true)

    private val pokes = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Check the link now instead of on the supervisor's timer (DapTap opened, a setting changed). */
    fun poke() { pokes.trySend(Unit) }

    /**
     * Keeps the iPhone link up, testing mode or not: notices the helper is gone, logs why, and starts it again
     * (switching debugging back on as needed) with a backoff, never during a swap ([busy]). Also replaces a helper
     * from an older DapTap. [notify] gets what the user must do when DapTap can't fix it itself, or null.
     */
    suspend fun supervise(busy: () -> Boolean, notify: (String?) -> Unit) = withContext(Dispatchers.IO) {
        if (!supported) return@withContext
        var wasUp: Boolean? = null
        var failures = 0
        var nextTry = 0L
        var notified: String? = null
        fun tell(text: String?) { if (text != notified) { notified = text; runCatching { notify(text) } } }
        while (true) {
            val s = helper.ping(600)
            val up = s != null && s.protocol in LinkHelper.MIN_COMPATIBLE..LinkHelper.PROTOCOL
            if (up) outdated = s!!.protocol < LinkHelper.PROTOCOL
            val now = android.os.SystemClock.elapsedRealtime()
            if (up && wasUp == false) log("iPhone link: helper is back · ${adbState()}")
            if (!up && wasUp != false && paired && enabled) log("iPhone link: helper not running · ${adbState()}${cause()?.let { " · $it" } ?: ""}")
            if (up) {
                if (_state.value.phase == Phase.STOPPED) _state.value = State(Phase.RUNNING)
                tell(null)
            }
            val canStart = enabled && paired && canToggleWirelessDebugging
            if (canStart && (!up || outdated) && !busy() && now >= nextTry) {
                if (!onWifi()) {
                    // Nothing to try until Wi-Fi is back (the loop looks again every few seconds).
                    if (!up && _state.value.phase == Phase.STOPPED && !_state.value.needsUser)
                        _state.value = State(Phase.STOPPED, "Reconnects by itself the next time this phone is on Wi-Fi.")
                } else {
                    val ok = start().isSuccess && helper.ping(600) != null
                    if (ok) { failures = 0; nextTry = 0; tell(null) } else {
                        failures++
                        nextTry = now + BACKOFF_MS[minOf(failures - 1, BACKOFF_MS.size - 1)]
                        val st = _state.value
                        if (st.needsUser) tell(st.message)
                    }
                }
            }
            wasUp = up || helper.ping(300) != null
            // A poke (DapTap opened) retries at once, even inside the backoff.
            if (kotlinx.coroutines.withTimeoutOrNull(if (wasUp == true) 5_000L else 3_000L) { pokes.receive() } != null) nextTry = 0
        }
    }

    /** Switch the helper on (start it) or off (stop it; the NameDrop server then answers without it). */
    suspend fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        if (on) { log("iPhone link: turned on"); start(); return }
        withContext(Dispatchers.IO) { helper.stop() }
        log("iPhone link: turned off; replies to iPhones go out by link broadcast instead")
        _state.value = State(Phase.OFF)
        onHelperUp() // restart the NameDrop server without the relay
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
        /** android.net.ConnectivitySettingsManager.UIDS_ALLOWED_ON_RESTRICTED_NETWORKS: ";"-separated uids. */
        const val RESTRICTED_NETWORK_UIDS = "uids_allowed_on_restricted_networks"
        /** Waits between failed restarts: 15 s, 30 s, 1 min, 2 min, then every 5 min (a poke retries at once). */
        private val BACKOFF_MS = longArrayOf(15_000, 30_000, 60_000, 120_000, 300_000)
        const val HELPER_NAME = "daptap-link"
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
