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
        appleLink = com.keshav.daptap.applink.AppleLink(this, engine::log) { engine.restartNameDropServer() }
        engine.relaySocketProvider = {
            if (appleLink.supported && appleLink.enabled && appleLink.helper.ping(700) != null) appleLink.helper.relaySocket() else null
        }
        val bindHelper = { iface: String, network: android.net.Network ->
            scope.launch(Dispatchers.IO) {
                if (appleLink.supported && appleLink.enabled) appleLink.helper.bind(network.networkHandle)?.let { engine.log("iPhone link: $iface $it") }
            }
        }
        val linkRoute = com.keshav.daptap.namedrop.LinkRoute(this, engine::log).apply {
            onPeerNetwork = { iface, network -> bindHelper(iface, network); engine.useAppleLinkNetwork(iface, network); engine.onAppleLinkUp() }
            watch()
        }
        scope.launch {
            prefs.settings.map { it.testingMode }.distinctUntilChanged().collectLatest { on -> if (on && appleLink.supported) appleLink.watch() }
        }
        // Bring the iPhone link helper back after a restart or update, if DapTap may switch Wireless debugging on.
        scope.launch {
            if (appleLink.supported && !appleLink.refresh() && appleLink.enabled && appleLink.paired && appleLink.canToggleWirelessDebugging) appleLink.start()
        }
        val peerLink = com.keshav.daptap.diag.PeerLink(engine::log) {
            runCatching { com.keshav.daptap.nfc.SnapKeys.get(this).first.uuidString }.getOrNull()
        }
        com.keshav.daptap.diag.InterfaceWatch(engine::log,
            onUp = { ni ->
                if (com.keshav.daptap.diag.PeerLink.isPeerLink(ni.name)) {
                    scope.launch { engine.keepProbe(true) }
                    peerLink.up(ni)
                    scope.launch(Dispatchers.IO) {
                        linkRoute.networkFor(ni.name)?.let { bindHelper(ni.name, it); engine.useAppleLinkNetwork(ni.name, it) }
                        linkRoute.probe(ni) { handle, dst -> if (appleLink.supported) appleLink.helper.probe(handle, dst) else null }
                    }
                }
            },
            onGone = { name ->
                if (com.keshav.daptap.diag.PeerLink.isPeerLink(name)) { peerLink.down(); scope.launch { engine.keepProbe(false) } }
            },
        ).start(scope, prefs.settings.map { it.testingMode })
        scope.launch {
            prefs.settings.map { it.testingMode }.distinctUntilChanged().collect { com.keshav.daptap.diag.TestingService.set(this@DapTapApp, it) }
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

    /** Whether Android registered the peer link as a Network (then sockets and NSD can be pinned to it). */

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
}
