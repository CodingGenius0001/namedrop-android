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

    // The tap's haptic arc: a rising swell when the phones meet, a swell into a snap when a card lands.
    // Rich primitives where the vibrator has them (Galaxy and Pixel do), simpler effects otherwise.

    override fun touch() = play(
        listOf(Prim(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.55f), Prim(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 30)),
        VibrationEffect.EFFECT_TICK, longArrayOf(0, 18),
    )

    override fun sent(peer: TapEngine.Peer) {
        if (peer == TapEngine.Peer.ANDROID) return // their card landing plays received(); one snap, not two
        play(listOf(Prim(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.5f), Prim(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 20)),
            VibrationEffect.EFFECT_DOUBLE_CLICK, longArrayOf(0, 30, 70, 40))
    }

    override fun received() = play(
        listOf(Prim(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.6f), Prim(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 10), Prim(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 60)),
        VibrationEffect.EFFECT_HEAVY_CLICK, longArrayOf(0, 40, 60, 30),
    )

    private class Prim(val id: Int, val scale: Float, val delayMs: Int = 0)

    private fun play(primitives: List<Prim>, predefined: Int, fallback: LongArray) {
        val v = vibrator ?: return
        runCatching {
            when {
                Build.VERSION.SDK_INT >= 31 && v.areAllPrimitivesSupported(*primitives.map { it.id }.toIntArray()) ->
                    v.vibrate(VibrationEffect.startComposition().apply {
                        primitives.forEach { addPrimitive(it.id, it.scale, it.delayMs) }
                    }.compose())
                Build.VERSION.SDK_INT >= 29 -> v.vibrate(VibrationEffect.createPredefined(predefined))
                else -> v.vibrate(VibrationEffect.createWaveform(fallback, -1))
            }
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
        if (!ok) runCatching { Notifications.sendPending(app, QuickShare.buildIntent(app, card, photo)) }
        return ok
    }

    override fun notifyIncoming(person: Person) {
        Notifications.incoming(app, person)
        if (Settings.canDrawOverlays(app) && app.prefs.settings.value.overlay) overlay.showIncoming(person)
    }
}
