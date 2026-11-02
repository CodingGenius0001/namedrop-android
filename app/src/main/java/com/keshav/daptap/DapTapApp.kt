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

class DapTapApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var history: History; private set
    lateinit var engine: TapEngine; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile var startedActivities = 0; private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        history = History(this)
        engine = TapEngine(this, prefs, history, Relay(BuildConfig.RELAY_BASE), scope, AndroidFeedback(this))
        Notifications.createChannels(this)
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
}
