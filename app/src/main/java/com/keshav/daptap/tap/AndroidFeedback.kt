package com.keshav.daptap.tap

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.keshav.daptap.DapTapApp
import com.keshav.daptap.Notifications
import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.data.Person
import com.keshav.daptap.overlay.OverlayController
import com.keshav.daptap.share.QuickShare

class AndroidFeedback(private val app: DapTapApp) : TapEngine.Feedback {
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
    }
    private val overlay by lazy { OverlayController(app) }

    override fun touch() = buzz(VibrationEffect.EFFECT_TICK, longArrayOf(0, 18))

    override fun sent(peer: TapEngine.Peer) = buzz(VibrationEffect.EFFECT_DOUBLE_CLICK, longArrayOf(0, 30, 70, 40))

    private fun buzz(predefined: Int, fallback: LongArray) {
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(predefined))
            else v.vibrate(VibrationEffect.createWaveform(fallback, -1))
        }
    }

    override fun appInForeground() = app.startedActivities > 0

    override fun showOverlay(phase: TapEngine.Phase) {
        if (Settings.canDrawOverlays(app)) overlay.show(phase)
    }

    override fun launchQuickShare(card: ContactCard, photo: ByteArray?): Boolean {
        // HostApduService is one of the system-bound services allowed to start activities from the
        // background; an overlay window (when permitted) is a second, independent allowance.
        val ok = QuickShare.launch(app, card, photo)
        if (!ok) Notifications.sendPending(app, QuickShare.buildIntent(app, card, photo))
        return ok
    }

    override fun notifyIncoming(person: Person) {
        Notifications.incoming(app, person)
        if (Settings.canDrawOverlays(app) && app.prefs.settings.value.overlay) overlay.showIncoming(person)
    }
}
