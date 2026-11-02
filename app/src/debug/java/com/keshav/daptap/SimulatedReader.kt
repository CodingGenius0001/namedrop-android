package com.keshav.daptap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.keshav.daptap.core.Ndef
import com.keshav.daptap.core.Type4Tag
import java.io.File

/**
 * adb shell am broadcast -n com.keshav.daptap.debug/com.keshav.daptap.SimulatedReader [--ez web true] [--ez apple true]
 * Sends the same APDUs an iPhone's background reader sends, through the real Type4Tag + TapEngine, and writes
 * what the "iPhone" read to cache/simulated-read.txt (first URL record, or the external payload).
 */
class SimulatedReader : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DapTapApp
        if (intent.hasExtra("web")) app.prefs.updateSettings { it.copy(webFallback = intent.getBooleanExtra("web", false)) }
        if (intent.hasExtra("airdrop")) app.prefs.updateSettings { it.copy(airDropOnTap = intent.getBooleanExtra("airdrop", true)) }
        val engine = app.engine
        if (intent.getBooleanExtra("apple", false)) engine.onPollingFrame(byteArrayOf(0x6A, 0x02, 0xC8.toByte(), 0x01, 0x00, 0x00))
        val tag = Type4Tag({ engine.currentMessage() }, object : Type4Tag.Listener {
            override fun onReaderSelected() = engine.onReaderSelected()
            override fun onMessageRead() = engine.onMessageRead()
            override fun onPeerCard(payload: ByteArray) = engine.onPushedCard(payload)
        })
        fun x(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun send(cmd: ByteArray): ByteArray = tag.process(cmd).also { engine.logApdu(cmd, it) }
        send(x("00A4040007D276000085010100"))
        send(x("00A4000C02E103"))
        send(x("00B000000F"))
        send(x("00A4000C02E104"))
        val nl = send(x("00B0000002"))
        val nlen = ((nl[0].toInt() and 0xFF) shl 8) or (nl[1].toInt() and 0xFF)
        val body = java.io.ByteArrayOutputStream()
        var off = 2
        while (off < nlen + 2) {
            val r = send(byteArrayOf(0, 0xB0.toByte(), (off ushr 8).toByte(), off.toByte(), minOf(0xF0, nlen + 2 - off).toByte()))
            body.write(r, 0, r.size - 2); off += r.size - 2
        }
        if (intent.getBooleanExtra("android", false)) {
            // Act like another DapTap phone: push its card back in the same touch.
            val peer = com.keshav.daptap.core.WirePayload.of(
                com.keshav.daptap.core.ContactCard("Priya Raman", "+1 626 555 0123", "priya@example.com", "Caltech", "PhD student", label = 2),
                com.keshav.daptap.relay.Relay.newSession().id, com.keshav.daptap.relay.Relay.newSession().keyText,
            ).encode().toByteArray(Charsets.US_ASCII)
            val ok = com.keshav.daptap.core.Type4Reader.push(peer) { send(it) }
            Log.i("DapTapSim", "pushed peer card: $ok")
        }
        engine.onDeactivated()
        val records = Ndef.decode(body.toByteArray()).orEmpty()
        val first = records.firstOrNull()?.let { Ndef.decodeUri(it) ?: "external:" + it.typeString + " " + String(it.payload).take(40) + "…" }
        File(context.cacheDir, "simulated-read.txt").writeText(first ?: "nothing")
        Log.i("DapTapSim", "read ${body.size()} bytes, first record: $first")
    }
}
