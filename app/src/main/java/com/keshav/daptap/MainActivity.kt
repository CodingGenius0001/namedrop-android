package com.keshav.daptap

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.keshav.daptap.core.Ndef
import com.keshav.daptap.core.Type4Reader
import com.keshav.daptap.core.VCard
import com.keshav.daptap.core.WirePayload
import com.keshav.daptap.nfc.NameDropBeacon
import com.keshav.daptap.nfc.TapService
import com.keshav.daptap.ui.DapTapRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app get() = application as DapTapApp
    private var nfc: NfcAdapter? = null
    private var beacon: NameDropBeacon? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)
        setContent { DapTapRoot(app) }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        beacon?.nudge()
    }

    override fun onResume() {
        super.onResume()
        Notifications.cancelUpdated(this)
        // Coming back from "Install unknown apps" continues an update; release builds also look for a newer
        // DapTap (once per launch, then at most every six hours). Debug builds are a different app id.
        lifecycleScope.launch {
            app.updater.onPermissionResult()
            if (!BuildConfig.DEBUG) app.updater.check()
        }
        val adapter = nfc ?: return
        enableForegroundDispatch(adapter)
        runCatching {
            // And our card wins NFC routing even if another app claims the same tag AID.
            CardEmulation.getInstance(adapter).setPreferredService(this, ComponentName(this, TapService::class.java))
        }
        val settings = app.prefs.settings.value
        val beaconOn = settings.tapEnabled && settings.nameDropBeacon && NameDropBeacon.isSupported(adapter)
        // Observe mode keeps the card silent until we allow it, which would stall the NameDrop handshake.
        if (!beaconOn && settings.tapEnabled && settings.observeFrames && Build.VERSION.SDK_INT >= 35) {
            runCatching { if (adapter.isObserveModeSupported) adapter.setObserveModeEnabled(true) }
        }
        if (beaconOn) {
            (beacon ?: NameDropBeacon(this, app.engine, lifecycleScope) { enableForegroundDispatch(adapter) }
                .also { beacon = it }).start()
        }
    }

    /** While DapTap is open, every tag the phone reads comes here first (no app chooser). */
    private fun enableForegroundDispatch(adapter: NfcAdapter) {
        runCatching {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getActivity(this, 0,
                Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
            adapter.enableForegroundDispatch(this, pi, null, null)
        }
    }

    override fun onPause() {
        super.onPause()
        val adapter = nfc ?: return
        runCatching { adapter.disableForegroundDispatch(this) }
        beacon?.stop()
        if (Build.VERSION.SDK_INT >= 35) runCatching { if (adapter.isObserveModeEnabled) adapter.setObserveModeEnabled(false) }
        runCatching { CardEmulation.getInstance(adapter).unsetPreferredService(this) }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED, NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_TAG_DISCOVERED -> readTag(intent)
            Intent.ACTION_VIEW -> intent.data?.let { openUri(it, intent.type) }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> openUri(stream, intent.type, via = "share")
                    text != null -> importVCardText(text, "share")
                }
            }
        }
    }

    private fun readTag(intent: Intent) {
        @Suppress("DEPRECATION")
        val raw: Array<Parcelable>? = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val messages = raw?.filterIsInstance<NdefMessage>()?.mapNotNull { Ndef.decode(it.toByteArray()) }.orEmpty()
        val payload = messages.firstNotNullOfOrNull { Ndef.findPayload(it) }
        val vcard = if (payload == null) messages.firstNotNullOfOrNull { Ndef.findVCard(it) } else null
        when {
            payload != null -> {
                // Another DapTap phone: hand ours back while the phones are still together, so both sides
                // swap, and so that phone knows it was an Android (and doesn't open Quick Share for an iPhone).
                @Suppress("DEPRECATION")
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                app.engine.receivePayload(payload, "tap")
                if (tag != null) lifecycleScope.launch {
                    if (withContext(Dispatchers.IO) { pushOurCard(tag) }) app.engine.markTheyHaveOurs()
                }
            }
            // Any other phone or tag carrying a plain contact card.
            vcard != null -> app.engine.receiveVCard(vcard.card, vcard.photo, "tap")
            else -> {
                // A DapTap phone with its NameDrop beacon on turns Android's own tag read away (it can't tell it
                // from an iPhone's). Read it ourselves: DapTap's reader says hello first, so it's let in.
                @Suppress("DEPRECATION")
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                if (tag == null || IsoDep.get(tag) == null) { app.engine.log("read a tag that isn't a contact card"); return }
                lifecycleScope.launch { readDapTap(tag) }
            }
        }
    }

    private suspend fun readDapTap(tag: Tag) {
        val iso = IsoDep.get(tag) ?: return
        val payload = withContext(Dispatchers.IO) {
            runCatching {
                iso.connect()
                iso.timeout = 1_500
                Type4Reader.read { iso.transceive(it) }
            }.getOrNull()?.let { Ndef.decode(it) }?.let { Ndef.findPayload(it) }
        }
        if (payload == null) {
            runCatching { iso.close() }
            app.engine.log("read a tag that isn't a contact card")
            return
        }
        app.engine.receivePayload(payload, "tap")
        val pushed = withContext(Dispatchers.IO) {
            runCatching { Type4Reader.push(app.engine.pushPayload()) { iso.transceive(it) } }.getOrDefault(false)
                .also { runCatching { iso.close() } }
        }
        app.engine.log(if (pushed) "sent our card back to the other DapTap phone" else "couldn't hand our card back (they can still read it)")
        if (pushed) app.engine.markTheyHaveOurs()
    }

    private fun pushOurCard(tag: Tag): Boolean {
        val iso = IsoDep.get(tag) ?: return false
        val ok = runCatching {
            iso.connect()
            iso.timeout = 1_500
            Type4Reader.push(app.engine.pushPayload()) { iso.transceive(it) }
        }.getOrDefault(false)
        runCatching { iso.close() }
        app.engine.log(if (ok) "sent our card back to the other DapTap phone" else "couldn't hand our card back (they can still read it)")
        return ok
    }

    private fun openUri(uri: Uri, type: String?, via: String = "airdrop") {
        if (uri.scheme == "https" || uri.scheme == "http") {
            WirePayload.fromUrl(uri.toString())?.let { app.engine.receivePayload(it, "link") }
            return
        }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(8 shl 20)
                        String(bytes, Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text != null) importVCardText(text, via) else app.engine.log("couldn't open $uri")
        }
    }

    private fun importVCardText(text: String, via: String) {
        val parsed = VCard.parse(text)
        if (parsed != null) app.engine.receiveVCard(parsed.card, parsed.photo, via)
        else app.engine.log("shared item isn't a contact card")
    }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
