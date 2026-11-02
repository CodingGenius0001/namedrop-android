package com.keshav.daptap.nfc

import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.PollingFrame
import android.os.Bundle
import com.keshav.daptap.DapTapApp
import com.keshav.daptap.core.SnapApplet
import com.keshav.daptap.core.Type4Tag

/**
 * Makes the phone look like an NFC Forum Type 4 tag holding the user's card, and, while DapTap is open
 * and beaconing, like an iPhone's NameDrop applet (com.apple.boop). Android phones read the tag; an
 * iPhone that heard our NameDrop frame selects the boop applet instead and runs the NameDrop handshake.
 */
class TapService : HostApduService() {
    private val engine get() = (application as DapTapApp).engine

    private val tag by lazy {
        Type4Tag(
            messageProvider = { engine.currentMessage() },
            listener = object : Type4Tag.Listener {
                override fun onReaderSelected() = engine.onReaderSelected()
                override fun onMessageRead() = engine.onMessageRead()
                override fun onPeerCard(payload: ByteArray) = engine.onPushedCard(payload)
                override fun onNdefRefused() = engine.onNdefRefused()
            },
            refuseNdef = { daptapReader -> engine.refuseNdef(daptapReader) },
        )
    }

    private val snap by lazy { SnapApplet(SnapKeys.get(this).second) }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return Type4Tag.SW_WRONG_LENGTH
        inSession = true
        val boop = runCatching { snap.dispatch(commandApdu) }.getOrNull()
        if (boop != null) {
            engine.onSnap(boop.event, boop.detail, snap.peerUuid, snap.peerLabel)
            engine.logApdu(commandApdu, boop.response)
            return boop.response
        }
        val response = runCatching { tag.process(commandApdu) }.getOrElse { Type4Tag.SW_INS_NOT_SUPPORTED }
        engine.logApdu(commandApdu, response)
        return response
    }

    override fun onDeactivated(reason: Int) {
        inSession = false
        tag.reset()
        snap.reset()
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

    companion object {
        /** An ISO-DEP exchange is in progress: the beacon must not raise its reader field and tear it down. */
        @Volatile var inSession = false
            private set
    }
}
