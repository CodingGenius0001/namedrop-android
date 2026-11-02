package com.keshav.daptap.tap

import android.content.Context
import android.os.SystemClock
import com.keshav.daptap.BuildConfig
import com.keshav.daptap.core.AppleEcp
import com.keshav.daptap.core.Base64Url
import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.core.Ndef
import com.keshav.daptap.core.RelayMessage
import com.keshav.daptap.core.VCard
import com.keshav.daptap.core.WirePayload
import com.keshav.daptap.data.History
import com.keshav.daptap.data.Person
import com.keshav.daptap.data.Photos
import com.keshav.daptap.data.Prefs
import com.keshav.daptap.relay.Relay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The brain behind a tap. The NFC service reports reader activity; activities report cards read from
 * other phones, AirDropped vCards and links. The engine decides what each touch means and drives the UI.
 */
class TapEngine(
    private val context: Context,
    private val prefs: Prefs,
    private val history: History,
    private val relay: Relay,
    private val scope: CoroutineScope,
    private val feedback: Feedback,
) {
    /** Side effects the engine needs from Android, behind an interface so tests can fake them. */
    interface Feedback {
        fun touch() {}
        fun sent(peer: Peer) {}
        fun appInForeground(): Boolean = true
        fun showOverlay(phase: Phase) {}
        fun launchQuickShare(card: ContactCard, photo: ByteArray?): Boolean = false
        fun notifyIncoming(person: Person) {}
    }

    enum class Peer { IPHONE, ANDROID, UNKNOWN }

    sealed interface Phase {
        data object Ready : Phase
        /** A reader is touching us right now. */
        data class Touching(val at: Long) : Phase
        /** Our card went out; [airDrop] when Quick Share was opened for the iPhone. */
        data class Sent(val peer: Peer, val airDrop: Boolean, val at: Long) : Phase
    }

    data class Incoming(
        val person: Person,
        val photo: ByteArray?,
        /** Where to send our card back, when the sender gave us a mailbox. */
        val replyTo: Relay.Session?,
        val sharedBack: Boolean = false,
    )

    private val _phase = MutableStateFlow<Phase>(Phase.Ready)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _incoming = MutableStateFlow<Incoming?>(null)
    val incoming: StateFlow<Incoming?> = _incoming.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-shot UI messages (snackbars). */
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Random per-install id so we can ignore our own relay messages. */
    private val instanceId = UUID.randomUUID().toString().take(8)

    private var session: Relay.Session = Relay.newSession()
    private var sessionCreatedAt = SystemClock.elapsedRealtime()
    private var sessionUsedAt = 0L
    private val listeners = HashMap<String, Job>()
    /** When each session's listener closes; a later, longer request pushes it out instead of reconnecting. */
    private val listenUntil = HashMap<String, Long>()
    private var photoPublishedFor: String? = null

    private var touchStartedAt = 0L
    private var lastActionAt = -ACTION_DEBOUNCE_MS
    private var lastPeerTagAt = -1_000_000L
    @Volatile private var lastPushAt = 0L
    private var lastAppleFrameAt = -1_000_000L
    private var lastOtherFrameLogAt = -1_000_000L
    private var resetJob: Job? = null

    private var cachedMessage: ByteArray? = null
    private var cachedKey: String? = null

    // ---------------------------------------------------------------- NFC service callbacks (main thread)

    /** The NDEF message for this read. Cached; rebuilt only when the card, photo, settings or session change. */
    @Synchronized
    fun currentMessage(): ByteArray {
        rotateSessionIfStale()
        val card = prefs.card.value
        val settings = prefs.settings.value
        val key = "${card.hashCode()}|${prefs.photoVersion.value}|${settings.webFallback}|${session.id}"
        cachedMessage?.takeIf { cachedKey == key }?.let { return it }
        val photo = Photos.readMine(context)?.let { Photos.thumbnail(it, NFC_PHOTO_PX, NFC_PHOTO_MAX) }
        val lean = WirePayload.of(card, session.id, session.keyText)
        val records = buildList {
            // iPhones act on the first record only when it is a URL, so the web card leads when it is on.
            if (settings.webFallback) add(Ndef.uriRecord(WirePayload.webUrl(BuildConfig.WEB_BASE, lean)))
            // Any Android phone, DapTap or not: its Contacts app imports this vCard natively. It carries the
            // photo, so the photo crosses the air once.
            add(Ndef.mimeRecord(Ndef.VCARD_MIME, VCard.write(card, photo).toByteArray(Charsets.UTF_8)))
            // A DapTap phone also gets the relay session for a reply.
            add(Ndef.externalRecord(Ndef.DAPTAP_EXTERNAL_TYPE, lean.encode().toByteArray(Charsets.US_ASCII)))
        }
        return Ndef.encode(records).also { cachedMessage = it; cachedKey = key }
    }

    /** Our card as pushed to another DapTap phone in a two-way swap. */
    @Synchronized
    fun pushPayload(): ByteArray {
        rotateSessionIfStale()
        val photo = Photos.readMine(context)?.let { Photos.thumbnail(it, NFC_PHOTO_PX, NFC_PHOTO_MAX) }
        return WirePayload.of(prefs.card.value, session.id, session.keyText, photo).encode().toByteArray(Charsets.US_ASCII)
    }

    /** A DapTap phone that read our tag pushed its card to us. Runs on the main thread (HCE). */
    fun onPushedCard(bytes: ByteArray) {
        lastPushAt = SystemClock.elapsedRealtime()
        val payload = WirePayload.decode(String(bytes, Charsets.US_ASCII))
        if (payload == null) { log("ignored a malformed pushed card"); return }
        receivePayload(payload, "tap", theyHaveOurs = true)
    }

    fun onReaderSelected() {
        val now = SystemClock.elapsedRealtime()
        log("reader selected DapTap tag")
        if (_phase.value !is Phase.Touching) {
            touchStartedAt = now
            _phase.value = Phase.Touching(now)
            feedback.touch()
            if (!feedback.appInForeground() && prefs.settings.value.overlay) feedback.showOverlay(_phase.value)
        }
        scheduleReset()
    }

    fun onMessageRead() {
        val now = SystemClock.elapsedRealtime()
        sessionUsedAt = now
        val served = session
        log("card read by peer (session ${served.id.take(6)}…)")
        // Web-card replies can come back any time while they fill in the form.
        if (prefs.settings.value.webFallback) listen(served, WEB_LISTEN_MS)
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return // the same hold, read again
        lastActionAt = now
        scope.launch { decide(now, served) }
    }

    /**
     * Apple enhanced-contactless polling frames (0x6A…). NameDrop/AirDrop-family frames only come from an
     * iPhone within a few centimetres, so they count as a touch even if the iPhone never reads our tag
     * (for example because it's busy answering our NameDrop frame).
     */
    fun onPollingFrame(data: ByteArray) {
        if (data.isEmpty() || data[0] != AppleEcp.HEADER) return
        val now = SystemClock.elapsedRealtime()
        val kind = AppleEcp.classify(data)
        if (!AppleEcp.isIphoneSharing(data)) {
            if (now - lastOtherFrameLogAt > 2_000) log("Apple reader frame ($kind) ${hex(data)}")
            lastOtherFrameLogAt = now
            return
        }
        if (now - lastAppleFrameAt > 2_000) {
            log(when (kind) {
                AppleEcp.Kind.AIRDROP -> "iPhone answered our NameDrop frame (AirDrop ECP ${hex(data)})"
                AppleEcp.Kind.NAMEDROP -> "iPhone is offering NameDrop (ECP ${hex(data)})"
                else -> "iPhone sharing frame ${hex(data)}"
            })
        }
        lastAppleFrameAt = now
        iphoneTouch()
    }

    /** Something touched us in the beacon's reader phase that isn't a DapTap phone. */
    fun onForeignTagInReaderPhase() {
        log("a non-DapTap device answered our NameDrop poll")
        lastAppleFrameAt = SystemClock.elapsedRealtime()
        iphoneTouch()
    }

    private fun iphoneTouch() {
        val now = SystemClock.elapsedRealtime()
        if (_phase.value !is Phase.Touching && _phase.value !is Phase.Sent) {
            touchStartedAt = now
            _phase.value = Phase.Touching(now)
            feedback.touch()
            if (!feedback.appInForeground() && prefs.settings.value.overlay) feedback.showOverlay(_phase.value)
        }
        scheduleReset()
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return
        lastActionAt = now
        scope.launch { decide(now, session) }
    }

    fun onDeactivated() {
        scheduleReset(1_500)
    }

    fun logApdu(command: ByteArray, response: ByteArray) {
        if (BuildConfig.DEBUG || _log.value.size < 400) log("> ${hex(command)}  < ${hex(response.takeLast(2).toByteArray())}")
    }

    private suspend fun decide(readAt: Long, served: Relay.Session) {
        val settings = prefs.settings.value
        val appleSeen = readAt - lastAppleFrameAt < 3_000
        if (!appleSeen) {
            // Two DapTap phones read each other at the same time; give ours a moment to land.
            withTimeoutOrNull(PEER_WINDOW_MS) {
                while (lastPeerTagAt < touchStartedAt - 1_000) delay(50)
            }
        }
        val peer = when {
            lastPeerTagAt >= touchStartedAt - 1_000 -> Peer.ANDROID
            appleSeen -> Peer.IPHONE
            else -> Peer.UNKNOWN
        }
        log("decided peer=$peer (apple frames: $appleSeen)")
        // Without the web card, the only reply that can come over the relay is a DapTap phone whose card
        // didn't make it back during the touch ("Send mine back"). Only then is it worth a connection.
        if (!settings.webFallback && peer != Peer.IPHONE && lastPushAt < touchStartedAt) listen(served, SWAP_LISTEN_MS)
        var airDrop = false
        if (peer != Peer.ANDROID && settings.airDropOnTap) {
            val card = prefs.card.value
            // A failed handoff must never take the app down mid-tap: log it and carry on.
            airDrop = runCatching { feedback.launchQuickShare(card, Photos.readMine(context)) }
                .onFailure { log("Quick Share failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            log(if (airDrop) "opened Quick Share with vCard" else "could not open Quick Share")
            if (airDrop && settings.autoPickIphone) AutoPick.arm()
        }
        val phase = Phase.Sent(peer, airDrop, SystemClock.elapsedRealtime())
        _phase.value = phase
        feedback.sent(peer)
        if (!feedback.appInForeground() && settings.overlay) feedback.showOverlay(phase)
        scheduleReset(SENT_HOLD_MS)
    }

    private fun scheduleReset(after: Long = TOUCH_TIMEOUT_MS) {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(after)
            _phase.value = Phase.Ready
        }
    }

    // ---------------------------------------------------------------- incoming cards

    /** A DapTap tag or web-card link was read from another phone. */
    fun receivePayload(payload: WirePayload, via: String, theyHaveOurs: Boolean = false) {
        lastPeerTagAt = SystemClock.elapsedRealtime()
        val card = payload.card()
        if (card.isEmpty) { log("ignored empty card"); return }
        val photo = payload.photoBytes()?.takeIf { Photos.decodeBytes(it) != null }
        val replyTo = Relay.sessionOf(payload.session, payload.key)
        log("received ${card.name} via $via")
        present(card, photo, replyTo, via, theyHaveOurs)
    }

    /** A vCard arrived (AirDropped from an iPhone through Quick Share, or shared into DapTap). */
    fun receiveVCard(card: ContactCard, photo: ByteArray?, via: String) {
        if (card.isEmpty) return
        present(card.sanitized(), photo?.let { Photos.thumbnail(it, 720, 400_000) ?: it }, null, via)
    }

    private fun present(card: ContactCard, photo: ByteArray?, replyTo: Relay.Session?, via: String, theyHaveOurs: Boolean = false) {
        val person = history.add(card, photo, via)
        val current = _incoming.value
        // The same person read twice during one hold: keep the screen, just refresh the photo.
        if (current != null && current.person.id == person.id) {
            _incoming.value = current.copy(person = person, photo = photo ?: current.photo, replyTo = replyTo ?: current.replyTo,
                sharedBack = current.sharedBack || theyHaveOurs)
            return
        }
        _incoming.value = Incoming(person, photo, replyTo, sharedBack = theyHaveOurs)
        if (!feedback.appInForeground()) feedback.notifyIncoming(person)
    }

    fun dismissIncoming() { _incoming.value = null }

    fun markSaved(person: Person) {
        history.markSaved(person.id)
        _incoming.update { if (it?.person?.id == person.id) it.copy(person = it.person.copy(saved = true)) else it }
    }

    /** Sends our card to the mailbox the other phone gave us. */
    suspend fun shareBack(): Boolean {
        val target = _incoming.value?.replyTo ?: return false
        val card = prefs.card.value
        val photo = Photos.readMine(context)?.let { Photos.thumbnail(it, 96, RELAY_PHOTO_MAX) }
        var message = RelayMessage("card", WirePayload.of(card, photo = photo), from = instanceId)
        if (!fitsRelay(message)) message = RelayMessage("card", WirePayload.of(card), from = instanceId)
        val ok = relay.publish(target, message)
        if (ok) _incoming.update { it?.copy(sharedBack = true) }
        log(if (ok) "shared card back" else "share back failed")
        return ok
    }

    fun sendWithQuickShare(): Boolean = runCatching { feedback.launchQuickShare(prefs.card.value, Photos.readMine(context)) }
        .onFailure { log("Quick Share failed: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrDefault(false)

    // ---------------------------------------------------------------- relay mailbox

    private fun rotateSessionIfStale() {
        val now = SystemClock.elapsedRealtime()
        val stale = now - sessionCreatedAt > SESSION_MAX_AGE_MS || (sessionUsedAt > 0 && now - sessionUsedAt > SESSION_REUSE_MS)
        if (stale) {
            session = Relay.newSession()
            sessionCreatedAt = now
            sessionUsedAt = 0
        }
        if (prefs.settings.value.webFallback && photoPublishedFor != session.id) {
            photoPublishedFor = session.id
            val s = session
            scope.launch { publishPhoto(s) }
        }
    }

    private suspend fun publishPhoto(s: Relay.Session) {
        val photo = Photos.readMine(context)?.let { Photos.thumbnail(it, 128, RELAY_PHOTO_MAX) } ?: return
        val msg = RelayMessage("photo", photo = Base64Url.encode(photo), from = instanceId)
        if (fitsRelay(msg)) relay.publish(s, msg)
    }

    private fun fitsRelay(m: RelayMessage) = (m.encode().size + 28) * 4 / 3 + 4 <= Relay.MAX_BODY

    /** Listens for replies on a session we handed out, for [forMs]; the connection closes after that. */
    private fun listen(s: Relay.Session, forMs: Long) {
        val until = SystemClock.elapsedRealtime() + forMs
        synchronized(listeners) {
            if (listeners[s.id]?.isActive == true) {
                // Already connected: just keep the connection open longer if this asks for more.
                if (until > (listenUntil[s.id] ?: 0L)) {
                    listenUntil[s.id] = until
                    log("listening for a reply for ${forMs / 60_000} min")
                }
                return
            }
            listenUntil[s.id] = until
            log("listening for a reply for ${forMs / 60_000} min")
            listeners[s.id] = scope.launch {
                val replies = launch {
                    relay.subscribe(s).collect { msg ->
                        if (msg.from == instanceId || msg.type != "card") return@collect
                        val payload = msg.card ?: return@collect
                        val card = payload.card()
                        if (card.isEmpty) return@collect
                        log("reply from ${card.name} via relay")
                        present(card, payload.photoBytes()?.takeIf { Photos.decodeBytes(it) != null }, null, "web")
                    }
                }
                while (true) {
                    val left = synchronized(listeners) { listenUntil[s.id] ?: 0L } - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    delay(left)
                }
                replies.cancel()
                synchronized(listeners) { listeners.remove(s.id); listenUntil.remove(s.id) }
            }
        }
    }

    // ---------------------------------------------------------------- diagnostics

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(line: String) {
        val entry = timeFmt.format(Date()) + "  " + line
        _log.update { (it + entry).takeLast(LOG_MAX) }
        if (BuildConfig.DEBUG) android.util.Log.d("DapTap", line)
    }

    fun clearLog() { _log.value = emptyList() }

    companion object {
        const val PEER_WINDOW_MS = 1_500L
        const val ACTION_DEBOUNCE_MS = 8_000L
        const val TOUCH_TIMEOUT_MS = 4_000L
        const val SENT_HOLD_MS = 6_000L
        const val SESSION_MAX_AGE_MS = 10 * 60_000L
        const val SESSION_REUSE_MS = 60_000L
        const val WEB_LISTEN_MS = 10 * 60_000L
        const val SWAP_LISTEN_MS = 3 * 60_000L
        /** The photo that rides on the tag: big enough for a contact header, small enough for a quick read. */
        const val NFC_PHOTO_PX = 128
        const val NFC_PHOTO_MAX = 3_600
        const val RELAY_PHOTO_MAX = 2_000
        const val LOG_MAX = 300

        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    }
}
