package com.keshav.daptap.nfc

import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.PollingFrame
import android.os.Bundle
import com.keshav.daptap.DapTapApp
import com.keshav.daptap.core.Type4Tag

/**
 * Makes the phone look like an NFC Forum Type 4 tag holding the user's card. iPhones read it in the
 * background; Android phones read it through the system tag dispatcher. Every read tells the engine
 * that a phone is touching ours.
 */
class TapService : HostApduService() {
    private val engine get() = (application as DapTapApp).engine

    private val tag by lazy {
        Type4Tag(
            messageProvider = { engine.currentMessage() },
            listener = object : Type4Tag.Listener {
                override fun onReaderSelected() = engine.onReaderSelected()
                override fun onMessageRead() = engine.onMessageRead()
            },
        )
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val response = runCatching { tag.process(commandApdu) }.getOrElse { Type4Tag.SW_INS_NOT_SUPPORTED }
        if (commandApdu != null) engine.logApdu(commandApdu, response)
        return response
    }

    override fun onDeactivated(reason: Int) {
        tag.reset()
        engine.onDeactivated()
    }

    /** Android 15+: polling frames observed before any APDU. Apple ECP frames reveal an iPhone nearby. */
    @androidx.annotation.RequiresApi(35)
    override fun processPollingFrames(frame: List<PollingFrame>) {
        frame.forEach { f ->
            engine.onPollingFrame(f.data)
            // In observe mode the controller stays silent until we allow it; an iPhone's sharing frame is
            // our cue to answer so it can read the card.
            if (com.keshav.daptap.core.AppleEcp.isIphoneSharing(f.data)) {
                runCatching {
                    val a = android.nfc.NfcAdapter.getDefaultAdapter(this)
                    if (a.isObserveModeEnabled) a.setObserveModeEnabled(false)
                }
            }
        }
    }
}
