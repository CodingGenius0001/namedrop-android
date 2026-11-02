package com.keshav.daptap.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.keshav.daptap.core.AppleEcp
import com.keshav.daptap.core.Ndef
import com.keshav.daptap.core.Type4Reader
import com.keshav.daptap.tap.TapEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * While DapTap is open, the phone alternates between two NFC roles:
 *
 *  - Beacon (reader mode, [ON_MS]): emits Apple's ECP frame as an Android 17 polling-loop annotation,
 *    alternating the NameDrop frame (fresh payload every burst, since iOS ignores a repeat) and the AirDrop
 *    frame. That is what an iPhone sends another iPhone to start NameDrop.
 *  - Card ([OFF_MS]): the iPhone, now treating us as a NameDrop peer, selects the boop applet in
 *    [TapService] and runs the handshake. Android phones read our tag in this phase too.
 *
 * Timing and flags follow github.com/enkai-liu/namedrop-re-public, measured against iOS 26 on an unrooted
 * Pixel 9: 500 ms on / 2000 ms off, and an emission only goes out when reader mode is (re)configured.
 * A burst never starts mid-exchange (raising our field would tear the handshake down). To spare the
 * battery the beacon rests after [REST_AFTER_MS] without a touch and wakes on the next one.
 * Needs Android 17 (API 37) and an NFC controller that supports reader-mode annotations.
 */
class NameDropBeacon(
    private val activity: Activity,
    private val engine: TapEngine,
    private val scope: CoroutineScope,
    /** Reader mode replaces foreground dispatch and turning it off doesn't restore it; the activity re-arms it. */
    private val onCardPhase: () -> Unit = {},
) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val random = SecureRandom()
    private var job: Job? = null
    private var readerOn = false
    private var nextIsAirDrop = false
    private val lastActivity = MutableStateFlow(SystemClock.elapsedRealtime())

    /** The user touched the screen or phones touched: keep (or start) beaconing. */
    fun nudge() { lastActivity.value = SystemClock.elapsedRealtime() }

    val supported: Boolean get() = isSupported(adapter)

    fun start() {
        if (job?.isActive == true || !supported || adapter?.isEnabled != true) return
        engine.log("NameDrop beacon on (${ON_MS} ms frame / ${OFF_MS} ms card)")
        engine.setBeaconActive(true)
        nudge()
        job = scope.launch {
            launch { engine.phase.collect { if (it !is TapEngine.Phase.Ready) nudge() } }
            while (isActive) {
                if (SystemClock.elapsedRealtime() - lastActivity.value > REST_AFTER_MS) {
                    setReader(false)
                    engine.setBeaconActive(false)
                    engine.log("NameDrop beacon resting; touch the screen to wake it")
                    val since = lastActivity.value
                    lastActivity.first { it != since }
                    engine.setBeaconActive(true)
                    engine.log("NameDrop beacon awake")
                }
                if (!TapService.inSession && !engine.beaconOnHold()) {
                    burst()
                    delay(ON_MS)
                    setReader(false)
                }
                delay(OFF_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        setReader(false)
        engine.setBeaconActive(false)
    }

    private fun burst() {
        val frame = if (nextIsAirDrop) AppleEcp.AIRDROP_FRAME else AppleEcp.nameDropBurstFrame(random)
        nextIsAirDrop = !nextIsAirDrop
        // Emission happens when reader mode is configured, so it must go off and on again each burst.
        setReader(false)
        setReader(true, frame)
    }

    private fun setReader(on: Boolean, frame: ByteArray? = null) {
        val a = adapter ?: return
        if (on == readerOn) return
        runCatching {
            if (on) {
                val extras = Bundle().apply { putByteArray(EXTRA_ANNOTATION, frame) }
                a.enableReaderMode(activity, ::onTag, READER_FLAGS, extras)
            } else {
                a.disableReaderMode(activity)
                onCardPhase()
            }
            readerOn = on
        }.onFailure { engine.log("beacon ${if (on) "start" else "stop"} failed: ${it.message}") }
    }

    /** Called on a binder thread when something answers our poll. */
    private fun onTag(tag: Tag) {
        val iso = IsoDep.get(tag)
        if (iso == null) { scope.launch(Dispatchers.Main) { engine.onForeignTagInReaderPhase() }; return }
        // Contact: buzz and ripple now, not after the whole card has crossed.
        scope.launch(Dispatchers.Main) { engine.onReaderTouch() }
        val bytes = runCatching {
            iso.connect()
            iso.timeout = 1_500
            Type4Reader.read { iso.transceive(it) }
        }.getOrNull()
        val payload = bytes?.let { Ndef.decode(it) }?.let { Ndef.findPayload(it) }
        if (payload == null) {
            runCatching { iso.close() }
            scope.launch(Dispatchers.Main) { engine.onForeignTagInReaderPhase() }
            return
        }
        // Their card goes on screen straight away; ours goes back while the phones are still together.
        scope.launch(Dispatchers.Main) { engine.receivePayload(payload, "tap") }
        val pushed = runCatching { Type4Reader.push(engine.pushPayload()) { iso.transceive(it) } }.getOrDefault(false)
        runCatching { iso.close() }
        engine.log(if (pushed) "sent our card to the other DapTap phone" else "couldn't push our card (they can still read it)")
        if (pushed) scope.launch(Dispatchers.Main) { engine.markTheyHaveOurs() }
    }

    companion object {
        /** NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION (API 37). */
        const val EXTRA_ANNOTATION = "android.nfc.extra.READER_TECH_A_POLLING_LOOP_ANNOTATION"

        /** Below ~450 ms nothing goes out; 500 on / 2000 off was the measured sweet spot. */
        const val ON_MS = 500L
        const val OFF_MS = 2_000L

        /**
         * NFC-A, skip the NDEF check, no platform sounds, plus 0x1000 ("keep listening, stop polling", which
         * NfcService special-cases). 0x1181 is what namedrop-re-public's NfcService log shows accepted.
         */
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS or 0x1000

        /** Beaconing stops after this long without a screen touch or a tap. */
        const val REST_AFTER_MS = 2 * 60_000L

        fun isSupported(adapter: NfcAdapter?): Boolean {
            if (adapter == null || Build.VERSION.SDK_INT < 37) return false
            return runCatching {
                adapter.javaClass.getMethod("isReaderModeAnnotationSupported").invoke(adapter) as Boolean
            }.getOrDefault(false)
        }
    }
}
