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
 * While DapTap is open, the phone alternates between two NFC roles a few times a second:
 *
 *  - Beacon (reader mode): polls with Apple's NameDrop ECP frame as an Android 17 polling-loop
 *    annotation, exactly the frame one iPhone sends another to start NameDrop. The iPhone answers with
 *    its NameDrop animation and an AirDrop ECP frame. If another DapTap phone is in range instead, its
 *    card is read directly.
 *  - Card (listen mode): the normal DapTap tag, so iPhones read it and the iPhone's reply frames arrive
 *    at [TapService.processPollingFrames].
 *
 * Phase lengths are randomised so two DapTap phones never stay in lock-step. To spare the battery the
 * beacon rests (plain card mode, which still shares on a tap) after [REST_AFTER_MS] without a touch on the
 * screen or a tap, and wakes on the next one.
 * Needs Android 17 (API 37) and an NFC controller that supports reader-mode annotations.
 */
class NameDropBeacon(
    private val activity: Activity,
    private val engine: TapEngine,
    private val scope: CoroutineScope,
) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val random = SecureRandom()
    private var job: Job? = null
    private var readerOn = false
    private val lastActivity = MutableStateFlow(SystemClock.elapsedRealtime())

    /** The user touched the screen or phones touched: keep (or start) beaconing. */
    fun nudge() { lastActivity.value = SystemClock.elapsedRealtime() }

    /** Random static BLE address (top two bits set, as BLE requires for static random addresses). */
    private val bleAddress = ByteArray(6).also { random.nextBytes(it); it[0] = (it[0].toInt() or 0xC0).toByte() }
    val frame: ByteArray = AppleEcp.nameDropFrame(bleAddress)

    val supported: Boolean get() = isSupported(adapter)

    fun start() {
        if (job?.isActive == true || !supported || adapter?.isEnabled != true) return
        engine.log("NameDrop beacon on: ${TapEngine.hex(frame)}")
        nudge()
        job = scope.launch {
            launch { engine.phase.collect { if (it !is TapEngine.Phase.Ready) nudge() } }
            while (isActive) {
                if (SystemClock.elapsedRealtime() - lastActivity.value > REST_AFTER_MS) {
                    setReader(false)
                    engine.log("NameDrop beacon resting; touch the screen to wake it")
                    val since = lastActivity.value
                    lastActivity.first { it != since }
                    engine.log("NameDrop beacon awake")
                }
                val idle = engine.phase.value is TapEngine.Phase.Ready && engine.incoming.value == null
                if (idle) {
                    setReader(true)
                    delay(350L + random.nextInt(300))
                }
                setReader(false)
                // Stay a card longer while a touch is in progress so the iPhone can finish reading it.
                delay(if (idle) 450L + random.nextInt(350) else 800L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        setReader(false)
    }

    private fun setReader(on: Boolean) {
        val a = adapter ?: return
        if (on == readerOn) return
        runCatching {
            if (on) {
                val extras = Bundle().apply { putByteArray(EXTRA_ANNOTATION, frame) }
                a.enableReaderMode(
                    activity, ::onTag,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    extras,
                )
            } else {
                a.disableReaderMode(activity)
            }
            readerOn = on
        }.onFailure { engine.log("beacon ${if (on) "start" else "stop"} failed: ${it.message}") }
    }

    /** Called on a binder thread when something answers our poll. */
    private fun onTag(tag: Tag) {
        val iso = IsoDep.get(tag)
        var pushedOk = false
        val bytes = iso?.let {
            runCatching {
                it.connect()
                it.timeout = 1_500
                Type4Reader.read { apdu -> it.transceive(apdu) }?.also { _ ->
                    // Hand them ours in the same touch: a two-way swap, like NameDrop.
                    pushedOk = Type4Reader.push(engine.pushPayload()) { apdu -> it.transceive(apdu) }
                    engine.log(if (pushedOk) "sent our card to the other DapTap phone" else "couldn't push our card (they can still read it)")
                }
            }.getOrNull().also { _ -> runCatching { iso.close() } }
        }
        val payload = bytes?.let { Ndef.decode(it) }?.let { Ndef.findPayload(it) }
        scope.launch(Dispatchers.Main) {
            if (payload != null) engine.receivePayload(payload, "tap", theyHaveOurs = pushedOk) else engine.onForeignTagInReaderPhase()
        }
    }

    companion object {
        /** NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION (API 37). */
        const val EXTRA_ANNOTATION = "android.nfc.extra.READER_TECH_A_POLLING_LOOP_ANNOTATION"

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
