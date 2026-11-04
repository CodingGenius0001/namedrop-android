package com.keshav.daptap

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.keshav.daptap.data.History
import com.keshav.daptap.data.Prefs
import com.keshav.daptap.relay.Relay
import com.keshav.daptap.tap.AndroidFeedback
import com.keshav.daptap.tap.TapEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DapTapApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var history: History; private set
    lateinit var engine: TapEngine; private set
    lateinit var updater: com.keshav.daptap.update.Updater; private set
    lateinit var logUploader: com.keshav.daptap.diag.LogUploader; private set
    lateinit var appleLink: com.keshav.daptap.applink.AppleLink; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile var startedActivities = 0; private set
    @Volatile private var currentPeerNetwork: android.net.Network? = null
    @Volatile private var currentPeerIface: String? = null
    /** The iPhone has finished its handshake on the current link (repeat binds stop; see bindHelper). */
    @Volatile private var iphoneConnected = false
    /** DapTap left the Wi-Fi network for the current swap and should rejoin when it ends. */
    @Volatile private var leftWifi = false
    /** Binds the helper to a link (set once the bind logic exists; the helper-up callback is built before it). */
    @Volatile private var onHelperBind: ((String, android.net.Network, String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        recordCrashes()
        prefs = Prefs(this)
        history = History(this)
        engine = TapEngine(this, prefs, history, Relay(BuildConfig.RELAY_BASE), scope, AndroidFeedback(this))
        Notifications.createChannels(this)
        updater = com.keshav.daptap.update.Updater(this, BuildConfig.WEB_BASE)
        reportLastCrash()
        logUploader = com.keshav.daptap.diag.LogUploader(this, BuildConfig.RELAY_BASE, engine.log,
            prefs.settings.map { it.testingMode }) {
            "DapTap ${BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})" +
                " · NameDrop beacon supported: ${com.keshav.daptap.nfc.NameDropBeacon.isSupported(android.nfc.NfcAdapter.getDefaultAdapter(this))}" +
                " · settings ${prefs.settings.value}"
        }.also { it.start(scope) }
        appleLink = com.keshav.daptap.applink.AppleLink(this, engine::log) {
            // A new helper process has no network bound yet: bind it to a link that's already up.
            val network = currentPeerNetwork
            val iface = currentPeerIface
            if (network != null && iface != null) onHelperBind?.invoke(iface, network, "new helper")
            engine.restartNameDropServer()
        }
        appleLink.helper.onNotice = { engine.log("iPhone link helper: $it") }
        engine.relaySocketProvider = {
            // The state says whether the helper is up without a round trip; a quick ping covers a stale state.
            if (appleLink.supported && appleLink.enabled &&
                (appleLink.state.value.phase == com.keshav.daptap.applink.AppleLink.Phase.RUNNING || appleLink.helper.ping(150) != null))
                appleLink.helper.relaySocket() else null
        }
        engine.appleLinkReady = {
            appleLink.supported && appleLink.enabled && appleLink.state.value.phase == com.keshav.daptap.applink.AppleLink.Phase.RUNNING
        }
        engine.startAppleLink = {
            scope.launch { if (appleLink.supported && appleLink.enabled && appleLink.paired && appleLink.canToggleWirelessDebugging) appleLink.start() }
        }
        // Per second during a swap: the Apple link's kernel counters and the iPhone's neighbour entry, so a stall
        // shows whether our packets still leave the phone and whether the kernel still knows where the iPhone is.
        val linkSamples = java.util.Collections.synchronizedList(mutableListOf<String>())
        var linkSampler: kotlinx.coroutines.Job? = null
        var wifiWatch: kotlinx.coroutines.Job? = null
        engine.swapDiagnostics = {
            if (appleLink.supported) listOfNotNull(appleLink.helper.stats()?.let { "iPhone link helper: $it" },
                appleLink.helper.traffic()?.let { "iPhone link traffic (per second, in/out): $it" },
                synchronized(linkSamples) { linkSamples.toList() }.takeIf { it.isNotEmpty() }?.let { "iPhone link kernel (per second): ${it.joinToString(" · ")}" },
            ).joinToString("\n").ifEmpty { null } else null
        }
        engine.onSwapActive = { on ->
            com.keshav.daptap.tap.SwapService.set(this, on)
            linkSampler?.cancel()
            wifiWatch?.cancel()
            if (!on && leftWifi) {
                leftWifi = false
                scope.launch(Dispatchers.IO) { engine.log("Wi-Fi: rejoining after the swap -> ${appleLink.helper.wifi(true) ?: "helper didn't answer"}") }
            }
            if (on) {
                wifiWatch = scope.launch(Dispatchers.IO) { keepOffWifiDuringSwap() }
                appleLink.helper.resetTraffic()
                iphoneConnected = false
                linkSamples.clear()
                linkSampler = scope.launch(Dispatchers.IO) {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    var prev: String? = null
                    while (linkSamples.size < 90) {
                        val iface = currentPeerIface
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (iface != null && appleLink.supported) appleLink.helper.link(iface)?.let { r ->
                            linkSamples += "+${(now - t0) / 1000}s ${linkDelta(prev, r)}"
                            prev = r
                        }
                        kotlinx.coroutines.delay((1_000 - (android.os.SystemClock.elapsedRealtime() - now)).coerceAtLeast(100))
                    }
                }
            }
        }
        engine.onIphoneConnected = { iphoneConnected = true }
        /**
         * Ties the helper's NameDrop socket to the Apple link. A bind made the moment the link's network appears can
         * be too early: the bind succeeds, sends "succeed", and nothing reaches the iPhone. Every working swap had a
         * second bind after mosey0 was fully up; 1.0.33 skipped repeats and the iPhone sat on "waiting". So this is
         * never deduplicated: [rebindSoon] repeats it over the first seconds of a link, and an iPhone's first packet
         * triggers one more (see HelperLink.onNewPeer). A bind is cheap: the helper applies it between receives.
         */
        val bindHelper = { iface: String, network: android.net.Network, why: String ->
            scope.launch(Dispatchers.IO) {
                // Once the iPhone has connected, the path works: leave the socket alone. Every failed tap on
                // 1.0.34-1.0.38 had a repeat bind land mid-exchange (the +3400 ms one) and stalled around it;
                // the one that worked was done before it. A new helper or a new link still binds.
                if (iphoneConnected && why != "link up" && why != "new helper") {
                    engine.log("iPhone link: left $iface bound ($why): the iPhone is connected")
                } else if (appleLink.supported && appleLink.enabled) {
                    appleLink.helper.bind(network.networkHandle)?.let { r -> engine.log("iPhone link: $iface $r ($why)") }
                }
            }
        }
        onHelperBind = { iface, network, why -> bindHelper(iface, network, why) }
        // The iPhone's first packet: the link is surely set up by now, so bind once more before we answer it.
        appleLink.helper.onNewPeer = {
            val network = currentPeerNetwork
            val iface = currentPeerIface
            if (network != null && iface != null) bindHelper(iface, network, "iPhone ${it.address.hostAddress} sent to us")
        }
        var rebindJob: kotlinx.coroutines.Job? = null
        val rebindSoon = { iface: String, network: android.net.Network ->
            rebindJob?.cancel()
            rebindJob = scope.launch(Dispatchers.IO) {
                var at = 0L
                for (gap in REBIND_GAPS_MS) {
                    kotlinx.coroutines.delay(gap); at += gap
                    if (currentPeerNetwork != network) break
                    bindHelper(iface, network, "+$at ms").join()
                }
            }
        }
        val peerLink = com.keshav.daptap.diag.PeerLink(engine::log) {
            runCatching { com.keshav.daptap.nfc.SnapKeys.get(this).first.uuidString }.getOrNull()
        }
        /** The Apple link is up: announce our listener on it at once and send replies through it. */
        val onPeerLinkUp = { iface: String ->
            scope.launch(Dispatchers.IO) {
                // The interface can lag its network by a moment.
                var ni: java.net.NetworkInterface? = null
                for (i in 0 until 60) { ni = runCatching { java.net.NetworkInterface.getByName(iface) }.getOrNull(); if (ni != null) break; kotlinx.coroutines.delay(50) }
                ni?.let(peerLink::up) ?: engine.log("peer link: $iface not visible as an interface")
            }
            scope.launch { engine.keepProbe(true) }
        }
        val linkRoute = com.keshav.daptap.namedrop.LinkRoute(this, engine::log).apply {
            onPeerNetwork = { iface, network ->
                iphoneConnected = false
                currentPeerNetwork = network
                currentPeerIface = iface
                onPeerLinkUp(iface)
                bindHelper(iface, network, "link up")
                rebindSoon(iface, network)
                engine.useAppleLinkNetwork(iface, network)
                engine.onAppleLinkUp()
            }
            onPeerNetworkLost = { _, network ->
                // A late loss of an older link must not shut down the one that's up now.
                if (currentPeerNetwork == null || currentPeerNetwork == network) {
                    currentPeerNetwork = null
                    rebindJob?.cancel()
                    peerLink.down()
                    scope.launch { engine.keepProbe(false) }
                }
            }
            watch()
        }
        // Keep the iPhone link up whether or not testing mode is on: bring the helper back after a reboot, an update
        // (replacing an older one), or something switching debugging off; never in the middle of a swap.
        scope.launch {
            appleLink.refresh()
            appleLink.supervise(busy = { currentPeerNetwork != null }) { problem ->
                if (problem != null) Notifications.linkProblem(this@DapTapApp, problem) else Notifications.cancelLinkProblem(this@DapTapApp)
            }
        }
        // Testing mode: log interface changes, and probe what can be sent on a new Apple link (once per link).
        var probed: String? = null
        com.keshav.daptap.diag.InterfaceWatch(engine::log,
            onUp = { ni ->
                if (com.keshav.daptap.diag.PeerLink.isPeerLink(ni.name)) {
                    // Normally the network callback got here first; this covers a link Android shows no network for.
                    peerLink.up(ni)
                    scope.launch { engine.keepProbe(true) }
                    val first = probed != ni.name
                    probed = ni.name
                    scope.launch(Dispatchers.IO) {
                        linkRoute.networkFor(ni.name)?.let { bindHelper(ni.name, it, "interface up"); engine.useAppleLinkNetwork(ni.name, it) }
                        if (first) linkRoute.probe(ni) { handle, dst -> if (appleLink.supported) appleLink.helper.probe(handle, dst) else null }
                    }
                }
            },
            onGone = { name ->
                if (com.keshav.daptap.diag.PeerLink.isPeerLink(name)) { probed = null; peerLink.down(); scope.launch { engine.keepProbe(false) } }
            },
        ).start(scope, prefs.settings.map { it.testingMode })
        scope.launch {
            prefs.settings.map { it.testingMode }.distinctUntilChanged().collect { com.keshav.daptap.diag.TestingService.set(this@DapTapApp, it) }
        }
        // Testing mode: try for direct access to the Apple link. The probe above logs whether it worked.
        scope.launch(Dispatchers.IO) {
            prefs.settings.map { it.testingMode }.distinctUntilChanged().collect { testing ->
                if (testing && appleLink.supported) engine.log("direct link test: ${appleLink.listForRestrictedNetworks()}")
            }
        }
        scope.launch(Dispatchers.IO) {
            val others = com.keshav.daptap.nfc.NfcRouting.otherNdefClaimants(this@DapTapApp)
            if (others.isNotEmpty()) engine.log("NFC: other apps also claim the tag AID: ${others.joinToString()}")
        }
        // The NameDrop identity is minted once; do it off the main thread so the NFC service never waits on it.
        scope.launch(Dispatchers.Default) { com.keshav.daptap.nfc.SnapKeys.warm(this@DapTapApp) }
        scope.launch {
            kotlinx.coroutines.flow.combine(prefs.card, prefs.photoVersion, prefs.settings) { _, _, _ -> }.collect {
                kotlinx.coroutines.withContext(Dispatchers.Default) { runCatching { engine.currentMessage() } }
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Samsung usually drops Wi-Fi as the Apple link comes up. On the one logged tap where it stayed connected, none
     * of our replies reached the iPhone, so during a swap DapTap does the same if Samsung hasn't. It gives Samsung a
     * moment first, and keeps checking in case Android rejoins. Runs until the swap ends and cancels it.
     */
    private suspend fun keepOffWifiDuringSwap() {
        while (currentPeerNetwork == null) kotlinx.coroutines.delay(100)
        kotlinx.coroutines.delay(WIFI_GRACE_MS)
        if (!wifiConnected()) engine.log("Wi-Fi: not connected with the iPhone link up, nothing to do")
        while (true) {
            if (wifiConnected()) {
                val r = appleLink.helper.wifi(false)
                engine.log("Wi-Fi: still connected with the iPhone link up; leaving the network for this swap -> ${r ?: "helper didn't answer"}")
                if (r == "ok") leftWifi = true
                kotlinx.coroutines.delay(1_500)
            } else kotlinx.coroutines.delay(300)
        }
    }

    private fun wifiConnected(): Boolean = runCatching {
        val wifi = java.net.NetworkInterface.getByName("wlan0") ?: return false
        wifi.isUp && wifi.inetAddresses.toList().any { it is java.net.Inet4Address }
    }.getOrDefault(false)

    private val crashFile get() = java.io.File(filesDir, "last-crash.txt")

    /** Keeps the stack trace of a crash so the next launch can show it in the tap log. */
    private fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile.writeText("DapTap ${BuildConfig.VERSION_NAME} crashed on ${thread.name}:\n" + error.stackTraceToString().take(6000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun reportLastCrash() {
        reportLastFreeze()
        val text = runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        crashFile.delete()
        text.lines().take(40).forEach { engine.log("CRASH $it") }
    }

    /** If Android last killed DapTap for not responding (ANR), log where the main thread was stuck. */
    private fun reportLastFreeze() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val am = getSystemService(android.app.ActivityManager::class.java)
                val exit = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return@runCatching
                if (exit.reason != android.app.ApplicationExitInfo.REASON_ANR) return@runCatching
                val seen = getSharedPreferences("daptap_diag", MODE_PRIVATE)
                if (seen.getLong("anr_at", 0) == exit.timestamp) return@runCatching
                seen.edit().putLong("anr_at", exit.timestamp).apply()
                engine.log("FREEZE: Android stopped DapTap for not responding (${exit.description})")
                val trace = exit.traceInputStream?.bufferedReader()?.readText().orEmpty()
                val main = trace.substringAfter("\"main\"", "").lineSequence().takeWhile { it.isNotBlank() }.take(30)
                main.forEach { engine.log("FREEZE   ${it.trim()}") }
            }
        }
    }

    companion object {
        private val COUNTERS = Regex("tx (\\d+) txdrop (\\d+) txerr (\\d+) rx (\\d+) rxdrop (\\d+) ns (\\d+) na (\\d+) discards (\\d+)")

        /**
         * One kernel sample, against the one before: counter deltas, and the iPhone's neighbour entry in full the
         * first time and whenever it changes ("same" otherwise).
         */
        fun linkDelta(prev: String?, now: String): String {
            val a = prev?.let { COUNTERS.find(it)?.groupValues?.drop(1)?.map(String::toLong) }
            val b = COUNTERS.find(now)?.groupValues?.drop(1)?.map(String::toLong) ?: return now
            val peer = now.substringAfter(" | ", "")
            if (a == null) return "${now.substringBefore(" | ")} | $peer"
            val d = b.zip(a) { x, y -> x - y }
            val samePeer = prev?.substringAfter(" | ", "") == peer
            return "tx+${d[0]} rx+${d[3]}" + (if (d[1] + d[2] + d[4] + d[7] != 0L) " txdrop+${d[1]} txerr+${d[2]} rxdrop+${d[4]} discards+${d[7]}" else "") +
                (if (d[5] + d[6] != 0L) " ns+${d[5]} na+${d[6]}" else "") +
                if (samePeer) "" else " | $peer"
        }

        /** Gaps between the repeat binds after an Apple link appears: at +150, +400, +900, +1900 and +3400 ms. */
        private val REBIND_GAPS_MS = longArrayOf(150, 250, 500, 1_000, 1_500)

        /** How long after the Apple link appears Samsung gets to drop Wi-Fi by itself. */
        private const val WIFI_GRACE_MS = 800L
    }
}
