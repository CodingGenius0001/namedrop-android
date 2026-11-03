package com.keshav.daptap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
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
        if (intent.hasExtra("testing")) app.prefs.updateSettings { it.copy(testingMode = intent.getBooleanExtra("testing", false)) }
        if (intent.hasExtra("airdrop")) app.prefs.updateSettings { it.copy(airDropOnTap = intent.getBooleanExtra("airdrop", true)) }
        val engine = app.engine
        intent.getStringExtra("peerlink")?.let { name ->
            Thread {
                val link = com.keshav.daptap.diag.PeerLink(engine::log) { com.keshav.daptap.nfc.SnapKeys.get(context).first.uuidString }
                java.net.NetworkInterface.getByName(name)?.let { link.up(it) }
                Thread.sleep(8_000); link.down()
            }.start()
            return
        }
        if (intent.getBooleanExtra("xdhcheck", false)) {
            fun t(name: String, f: () -> Any?) = Log.i("DapTapSim", "$name: " + runCatching { f().toString().take(120) }.getOrElse { "FAIL ${it.javaClass.simpleName}: ${it.message}" })
            t("providers") { java.security.Security.getProviders().joinToString { it.name } }
            t("KPG XDH+NamedParameterSpec") { java.security.KeyPairGenerator.getInstance("XDH").apply { initialize(java.security.spec.NamedParameterSpec("X25519")) }.generateKeyPair().public.javaClass.name }
            t("KPG X25519") { java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair().public.let { it.javaClass.name + " " + it.encoded.size + " xec=" + (it is java.security.interfaces.XECPublicKey) } }
            t("KF XDH XECPublicKeySpec") { java.security.KeyFactory.getInstance("XDH").generatePublic(java.security.spec.XECPublicKeySpec(java.security.spec.NamedParameterSpec("X25519"), java.math.BigInteger.valueOf(9))).javaClass.name }
            t("KF XDH X509") { val pub = java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair().public; java.security.KeyFactory.getInstance("XDH").generatePublic(java.security.spec.X509EncodedKeySpec(pub.encoded)).javaClass.name }
            t("KA XDH") { val a = java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair(); val b = java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair(); javax.crypto.KeyAgreement.getInstance("XDH").run { init(a.private); doPhase(b.public, true); generateSecret().size } }
            return
        }
        if (intent.getBooleanExtra("applinkstart", false)) {
            val app = context.applicationContext as com.keshav.daptap.DapTapApp
            context.getSharedPreferences("daptap_applink", Context.MODE_PRIVATE).edit().putBoolean("paired", true).commit()
            app.scope.launch { Log.i("DapTapSim", "applink: " + app.appleLink.start()) }
        }
        intent.getStringExtra("adbpair")?.let { arg ->
            val (port, code) = arg.split(":")
            Thread { Log.i("DapTapSim", "adbpair: " + runCatching { com.keshav.daptap.applink.Adb(context.applicationContext).pair("127.0.0.1", port.toInt(), code); "paired" }.getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }) }.start()
        }
        intent.getStringExtra("adbshell")?.let { arg ->
            val port = arg.substringBefore(":"); val cmd = arg.substringAfter(":")
            Thread { Log.i("DapTapSim", "adbshell: " + runCatching { com.keshav.daptap.applink.Adb(context.applicationContext).shell("127.0.0.1", port.toInt(), cmd) }.getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }) }.start()
        }
        intent.getStringExtra("routeprobe")?.let { name ->
            Thread {
                val r = com.keshav.daptap.namedrop.LinkRoute(context.applicationContext) { Log.i("DapTapSim", it) }
                java.net.NetworkInterface.getByName(name)?.let(r::probe) ?: Log.i("DapTapSim", "no interface $name")
            }.start()
        }
        if (intent.getBooleanExtra("loopback", false)) {
            Thread { Thread.sleep(1500); com.keshav.daptap.namedrop.LoopbackIphone.run { Log.i("DapTapSim", "loopback: $it") } }.start()
        }
        if (intent.getBooleanExtra("kwikdebug", false)) System.setProperty("daptap.kwikdebug", "1")
        if (intent.getBooleanExtra("namedrop", false)) { simulateNameDrop(context, engine); return }
        if (intent.getBooleanExtra("apple", false)) engine.onPollingFrame(byteArrayOf(0x6A, 0x02, 0xC8.toByte(), 0x01, 0x00, 0x00))
        val tag = Type4Tag({ engine.currentMessage() }, object : Type4Tag.Listener {
            override fun onReaderSelected() = engine.onReaderSelected()
            override fun onMessageRead() = engine.onMessageRead()
            override fun onPeerCard(payload: ByteArray) = engine.onPushedCard(payload)
        })
        fun x(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val t0 = android.os.SystemClock.uptimeMillis()
        val sinceStart = t0 - android.os.Process.getStartUptimeMillis()
        val times = ArrayList<Long>()
        fun send(cmd: ByteArray): ByteArray { val a = android.os.SystemClock.uptimeMillis(); return tag.process(cmd).also { engine.logApdu(cmd, it); times.add(android.os.SystemClock.uptimeMillis() - a) } }
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
        val first = records.firstOrNull()?.let {
            Ndef.decodeUri(it) ?: ((if (it.tnf == Ndef.TNF_MIME) "mime:" else "external:") + it.typeString + " " + String(it.payload).take(40) + "…")
        }
        File(context.cacheDir, "simulated-read.txt").writeText(first ?: "nothing")
        Log.i("DapTapSim", "read ${body.size()} bytes, first record: $first")
        Log.i("DapTapSim", "timing: process up ${sinceStart} ms before the first APDU; slowest APDU ${times.maxOrNull()} ms; all ${times.take(8)}")
        // The vCard record exactly as it went over the air, so it can be checked against a Contacts app.
        records.firstOrNull { it.tnf == Ndef.TNF_MIME }?.let { File(context.cacheDir, "simulated-vcard.vcf").writeBytes(it.payload) }
    }

    /**
     * --ez namedrop true: an iPhone that heard our NameDrop frame. It tries a tag read (which the beacon must
     * refuse), then runs the two-activation boop handshake through the real SnapApplet and identity.
     */
    private fun simulateNameDrop(context: Context, engine: com.keshav.daptap.tap.TapEngine) {
        fun x(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun hx(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
        val (id, blobs) = com.keshav.daptap.nfc.SnapKeys.get(context)
        engine.setBeaconActive(true)
        engine.onPollingFrame(x("6A02890500010000000000000000"))
        val tag = Type4Tag({ engine.currentMessage() }, object : Type4Tag.Listener {
            override fun onNdefRefused() = engine.onNdefRefused()
        }, refuseNdef = { engine.refuseNdef(it) })
        val ndef = tag.process(x("00A4040007D276000085010100"))
        val applet = com.keshav.daptap.core.SnapApplet(blobs)
        fun send(cmd: ByteArray): ByteArray {
            val r = applet.dispatch(cmd)!!
            engine.onSnap(r.event, r.detail, applet.peerUuid, applet.peerLabel)
            return r.response
        }
        val theirs = com.keshav.daptap.core.Cbor.encode(linkedMapOf(
            0 to "1.1", 1 to "com.apple.boop.SNAP", 2 to listOf(linkedMapOf(0 to "Bonjour", 1 to 0)),
            3 to linkedMapOf(0 to x("01020304"), 1 to id.spki, 2 to ByteArray(16) { 0x11 }, 3 to ByteArray(6), 4 to ByteArray(64), 5 to 1)))
        val out = StringBuilder()
        out.append("ndef=").append(hx(ndef)).append('\n')
        out.append("select=").append(hx(send(x("00A4040009A000000858044F010000")))).append('\n')
        val si = send(byteArrayOf(0, 0xCA.toByte(), 1, 3, theirs.size.toByte()) + theirs + 0)
        out.append("serverinfo=").append(si.size - 2).append(" B sw=").append(hx(si.takeLast(2).toByteArray())).append('\n')
        out.append("caps=").append(hx(send(byteArrayOf(0, 0xCA.toByte(), 1, 3, 85) + ByteArray(85) + 0).takeLast(2).toByteArray())).append('\n')
        applet.reset()
        send(x("00A4040009A000000858044F010000"))
        out.append("resume=").append(hx(send(x("00CA010404" + "02195AA5" + "00")).takeLast(2).toByteArray())).append('\n')
        out.append("uuid=").append(id.uuidString).append(" spki=").append(id.spki.size).append('\n')
        File(context.cacheDir, "simulated-namedrop.txt").writeText(out.toString())
        Log.i("DapTapSim", out.toString())
    }
}
