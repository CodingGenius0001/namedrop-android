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
import kotlinx.coroutines.launch

class DapTapApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var history: History; private set
    lateinit var engine: TapEngine; private set
    lateinit var updater: com.keshav.daptap.update.Updater; private set

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
        val text = runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        crashFile.delete()
        text.lines().take(40).forEach { engine.log("CRASH $it") }
    }
}
