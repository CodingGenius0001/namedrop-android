package com.keshav.daptap.tap

import android.content.Context
import android.os.SystemClock
import com.keshav.daptap.BuildConfig
import com.keshav.daptap.core.AppleEcp
import com.keshav.daptap.namedrop.PosterName
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
        /** Someone's card just landed on this phone. */
        fun received() {}
        fun appInForeground(): Boolean = true
        fun showOverlay(phase: Phase) {}
        fun launchQuickShare(card: ContactCard, photo: ByteArray?): Boolean = false
        /** Cover the screen with DapTap's own "swapping with iPhone" view (Quick Share runs underneath). */
        fun coverAppleLink(on: Boolean): Boolean = false
        /** The iPhone's card arrived during a swap: show it on the cover right away. False if there's no cover. */
        fun coverCard(person: Person, photo: ByteArray?): Boolean = false
        /** Build what opening Quick Share needs (card file, intent) ahead of time, off the main thread. */
        fun prepareQuickShare(card: ContactCard, photo: ByteArray?) {}
        /** Bring DapTap's own screen to the front (without a launch animation). */
        fun bringAppForward() {}
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

    // NameDrop (see NameDropBeacon / SnapApplet)
    @Volatile private var beaconActive = false
    /** The beacon is switched on (DapTap open), even while it rests between touches. */
    @Volatile private var beaconEnabled = false
    /** Set by the beacon: burst a NameDrop frame now (an iPhone just showed up), not at the next cycle. */
    @Volatile var beaconKick: (() -> Unit)? = null
    /** Set by the beacon: when its recent bursts went out, for the tap timeline. */
    @Volatile var beaconHistory: (() -> String)? = null
    @Volatile private var nameDropStartedAt = -1_000_000L
    @Volatile private var nameDropDoneAt = -1_000_000L
    @Volatile private var appleLinkFinishedAt = -1_000_000L
    /** This NFC activation turned an iPhone's tag read away (it needs our next NameDrop frame). */
    @Volatile private var refusedThisActivation = false
    /** RESUMED arrived; Quick Share opens the moment the NFC link drops. */
    @Volatile private var pendingAppleLinkAt = -1_000_000L
    /** Set by the app: whether the iPhone link helper can carry a swap right now (see AppleLink). */
    @Volatile var appleLinkReady: () -> Boolean = { false }
    /** Set by the app: try to start the iPhone link helper (it wasn't running when an iPhone tapped). */
    @Volatile var startAppleLink: () -> Unit = {}
    /** Set by the app: the link helper's counters, logged after each swap in testing mode (blocking; IO only). */
    @Volatile var swapDiagnostics: (() -> String?)? = null
    /** An iPhone swap started (true) or ended (false): keep DapTap running at full speed meanwhile. */
    @Volatile var onSwapActive: ((Boolean) -> Unit)? = null
    /** The iPhone finished its QUIC handshake with us: the path through the Apple link works. */
    @Volatile var onIphoneConnected: (() -> Unit)? = null
    private var appleLinkWatchdog: Job? = null
    @Volatile private var appleLinkStartedAt = 0L
    @Volatile private var appleLinkUpAt = 0L
    @Volatile private var iphoneConnectedAt = 0L
    /** Whether the cover is showing for the current swap. */
    @Volatile private var swapCovered = false
    private var lastRefusalLogAt = -1_000_000L
    private val nfcState = context.getSharedPreferences("daptap_nfc", Context.MODE_PRIVATE)
    /** This phone's NFC chip has reported an iPhone's polling frames at least once, so they can be relied on. */
    @Volatile private var framesReliable = nfcState.getBoolean("apple_frames_seen", false)
    /** Answers the iPhone's NameDrop connection (QUIC + HTTP/3) once it finds us on the Apple link. */
    private val nameDropServer by lazy {
        val id = com.keshav.daptap.nfc.SnapKeys.get(context).first
        val pkcs8 = checkNotNull(com.keshav.daptap.nfc.SnapKeys.privateKey(context)) { "no NameDrop key" }
        val key = java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pkcs8))
        com.keshav.daptap.namedrop.NameDropServer(id.spki, key, id.uuidString.lowercase() + ".local",
            com.keshav.daptap.namedrop.Exchange(id.uuidString, ::nameDropCard, ::onNameDropCard, ::log),
            ::log,
            linkSocket = { linkSocketProvider?.invoke() },
            onConnected = { onIphoneConnected(); swapId },
            onRequest = { path -> mark(path.trim('/').lowercase()) },
            onFinished = { why, id -> if (id == swapId) finishAppleLink(why) else log("NameDrop: an earlier connection ended ($why); ignored") },
            verbose = { prefs.settings.value.testingMode },
        )
    }
    /** Set by the app: a socket that can use the Apple link right now and how it does, if there is one. */
    @Volatile var linkSocketProvider: (() -> Pair<java.net.DatagramSocket, String>?)? = null

    /** Server starts and stops run one at a time, in order, so a stale stop can't undo a newer start. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serverDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

    /** How the server reaches the Apple link just changed: reopen its socket. */
    fun restartNameDropServer() {
        scope.launch(serverDispatcher) { runCatching { nameDropServer.restart() } }
    }

    private fun startNameDropServer() {
        scope.launch(serverDispatcher) { runCatching { nameDropServer.start() }.onFailure { log("NameDrop server: ${it.message}") } }
    }

    private fun stopNameDropServer() {
        // Off the main thread: stopping waits for open connections to close. Re-checked when it runs, so a stop
        // queued behind a newer start (DapTap paused and resumed) does nothing.
        scope.launch(serverDispatcher) { if (!beaconActive && !peerLinkUp) runCatching { nameDropServer.stop() } }
    }

    /** Our card as the iPhone gets it in /Ask and /Exchange, built once per card/photo change. */
    @Volatile private var nameDropCardCache: Pair<String, com.keshav.daptap.namedrop.Exchange.Card>? = null

    /** Our card as the iPhone gets it in /Exchange: a vCard with the photo, CRLF line ends. */
    private fun nameDropCard(): com.keshav.daptap.namedrop.Exchange.Card {
        val card = prefs.card.value
        val key = "${card.hashCode()}|${prefs.photoVersion.value}"
        nameDropCardCache?.takeIf { it.first == key }?.let { return it.second }
        val photo = Photos.readMine(context)?.let { Photos.thumbnail(it, NAMEDROP_PHOTO_PX, NAMEDROP_PHOTO_MAX) }
        val text = VCard.write(card, photo).replace("\r\n", "\n").replace("\n", "\r\n")
        return com.keshav.daptap.namedrop.Exchange.Card(card.name.ifBlank { "DapTap" }, card.email.ifBlank { null },
            card.phone.ifBlank { null }, text.toByteArray(Charsets.UTF_8)).also { nameDropCardCache = key to it }
    }

    /** The iPhone's card arrived over NameDrop (server thread). Everything heavy happens off the main thread. */
    private fun onNameDropCard(vcard: ByteArray, fullName: String?) {
        mark("card")
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val text = String(vcard, Charsets.UTF_8)
            val parsed = VCard.parse(text)
            if (parsed == null) { log("NameDrop: couldn't read the iPhone's card (${vcard.size} B)"); return@launch }
            val testing = prefs.settings.value.testingMode
            if (testing) {
                // Which vCard properties the iPhone sent (names and sizes only), for debugging.
                log("NameDrop: card properties " + text.lineSequence()
                    .filter { it.isNotEmpty() && !it[0].isWhitespace() && it.contains(':') }
                    .map { it.substringBefore(':').substringBefore(';') + "(" + (it.length - it.indexOf(':') - 1) + ")" }
                    .joinToString(" ").take(600))
            }
            // iOS sends no name when the sender's own card has none (the Contact Poster never carries it): fall
            // back to the name the exchange gave, and the user can add one.
            val card = if (parsed.card.name.isBlank() && !fullName.isNullOrBlank()) parsed.card.copy(name = fullName) else parsed.card
            val photo = prepareIncomingPhoto(parsed.photo)
            val person = if (card.isEmpty) null else history.add(card.sanitized(), photo, "namedrop")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                log("NameDrop: got ${card.name.ifBlank { "a card without a name" }} from the iPhone")
                if (person != null) show(person, photo, null, false, "namedrop")
                mark("shown")
            }
            if (testing && parsed.card.name.isBlank()) {
                // Testing mode: what the poster holds, capped so it can't push the tap's timing out of the log.
                runCatching { PosterName.inspect(text) }.getOrNull()?.let { r ->
                    val lines = r.description.lineSequence().filter { it.isNotBlank() }.toList()
                    log("NameDrop: poster outline (${lines.size} lines${if (lines.size > POSTER_LOG_LINES) ", first $POSTER_LOG_LINES" else ""}), name found: ${r.name ?: "none"}")
                    lines.take(POSTER_LOG_LINES).forEach { log("  poster| $it") }
                }
            }
        }
    }

    /** A received photo scaled for history and the screen; null when it isn't an image we can decode. */
    private fun prepareIncomingPhoto(photo: ByteArray?): ByteArray? = photo?.let { Photos.thumbnail(it, 720, 400_000) }
    private var probeStopJob: Job? = null

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
        if (!framesReliable) { framesReliable = true; nfcState.edit().putBoolean("apple_frames_seen", true).apply() }
        iphoneTouch()
    }

    // ---------------------------------------------------------------- NameDrop

    /** The beacon (DapTap open, Android 17) is emitting NameDrop frames. */
    fun setBeaconActive(on: Boolean) {
        beaconActive = on
        probeStopJob?.cancel()
        if (on) {
            startNameDropServer()
        } else {
            // Keep listening a little after a handshake: the iPhone connects only once Share is tapped there. Never
            // stop at once, so a quick pause/resume (or Quick Share opening mid-handshake) doesn't drop the server.
            val keep = (maxOf(nameDropDoneAt, nameDropStartedAt) + PROBE_AFTER_NAMEDROP_MS - SystemClock.elapsedRealtime())
                .coerceAtLeast(SERVER_LINGER_MS)
            probeStopJob = scope.launch { delay(keep); if (!beaconActive && !peerLinkUp) stopNameDropServer() }
        }
    }

    /** The beacon was switched on or off as a whole (DapTap came to the front or left it). */
    fun setBeaconEnabled(on: Boolean) { beaconEnabled = on }

    /** An iPhone is running (or just ran) NameDrop with us: don't touch it from the reader side. */
    fun iphoneNearby(): Boolean = SystemClock.elapsedRealtime() - maxOf(lastAppleFrameAt, nameDropStartedAt) < 10_000

    /** The NFC link dropped while the iPhone had the NameDrop applet selected. */
    fun onNameDropLinkEnded(reason: Int) {
        log("NameDrop: NFC link ended (${if (reason == 0) "lost: phones moved or field dropped" else "iPhone deselected us"})")
        // The handshake just finished and the NFC exchange is over: open Quick Share now, not after a fixed wait.
        if (SystemClock.elapsedRealtime() - pendingAppleLinkAt < 2_000) {
            pendingAppleLinkAt = -1_000_000L
            bringUpAppleLink()
        }
    }

    /**
     * Only Quick Share brings up the phone's Apple link (mosey0), and the iPhone looks for us on it. DapTap covers
     * Quick Share with its own screen until the swap is over.
     */
    /** The Apple link's network appeared. */
    fun onAppleLinkUp() {
        if (appleLinkStartedAt == 0L || appleLinkUpAt != 0L) return
        appleLinkUpAt = SystemClock.elapsedRealtime()
        mark("link")
    }

    /** The iPhone opened its QUIC connection to us (server thread). */
    private fun onIphoneConnected() {
        iphoneConnectedAt = SystemClock.elapsedRealtime()
        mark("connected")
        runCatching { onIphoneConnected?.invoke() }
    }

    /** Each swap's number: a connection from an earlier swap can't end this one. */
    @Volatile private var swapId = 0

    /** The user cancelled the swap on the cover. */
    fun cancelAppleLink() = finishAppleLink("cancelled")

    /** The NameDrop swap is over (our reply delivered, or given up): show DapTap again and drop the cover. */
    private fun finishAppleLink(why: String) {
        // Whatever is still connected belongs to the swap that just ended.
        if (why != "exchange finished") scope.launch(serverDispatcher) { runCatching { nameDropServer.closeConnections() } }
        scope.launch {
            appleLinkWatchdog?.cancel()
            appleLinkWatchdog = null
            val wasActive = appleLinkStartedAt != 0L
            appleLinkStartedAt = 0L
            runCatching { onSwapActive?.invoke(false) }
            appleLinkFinishedAt = SystemClock.elapsedRealtime()
            if (!wasActive) return@launch
            mark("done")
            log("NameDrop: swap over ($why)")
            logTimeline()
            // Say so when it failed, instead of DapTap just coming back as if nothing happened.
            when (why) {
                "the Apple link never came up", "the iPhone never connected" ->
                    _events.tryEmit("The iPhone couldn't reach this phone. Try the tap again.")
                "timed out waiting for the iPhone's Share" -> _events.tryEmit("The iPhone didn't tap Share in time.")
            }
            if (prefs.settings.value.testingMode) launch(kotlinx.coroutines.Dispatchers.IO) { swapDiagnostics?.invoke()?.let(::log) }
            if (swapCovered) {
                // DapTap comes forward under the cover; the cover goes once DapTap has drawn (see onAppShown),
                // or after a short fallback, so Quick Share never flashes.
                uncoverPending = true
                feedback.bringAppForward()
                delay(UNCOVER_FALLBACK_MS)
                if (uncoverPending) uncover()
            } else {
                feedback.bringAppForward()
            }
        }
    }

    @Volatile private var uncoverPending = false

    /** MainActivity drew its first frame after coming back: safe to remove the cover now. */
    fun onAppShown() { if (uncoverPending) scope.launch { uncover() } }

    private fun uncover() {
        uncoverPending = false
        swapCovered = false
        feedback.coverAppleLink(false)
    }

    private fun bringUpAppleLink() {
        if (!appleLinkReady()) {
            // Without the iPhone link, replies can't reach the iPhone (broadcast replies were tested and fail): don't
            // open Quick Share for nothing. Say what's missing and try to start the helper for the next tap.
            log("NameDrop: the iPhone link isn't running, so the swap can't finish; starting it for the next tap")
            _events.tryEmit("Turn on the iPhone link in Settings to swap with iPhones")
            startAppleLink()
            return
        }
        swapId++
        runCatching { onSwapActive?.invoke(true) }
        appleLinkStartedAt = SystemClock.elapsedRealtime()
        appleLinkUpAt = 0L
        iphoneConnectedAt = 0L
        mark("quickshare")
        // Quick Share only raises the Apple link while it's on screen. DapTap covers it with its own view so the
        // user never sees Quick Share; without the overlay permission Quick Share stays visible until the swap ends.
        val covered = runCatching { feedback.coverAppleLink(true) }.getOrDefault(false)
        swapCovered = covered
        val opened = runCatching { feedback.launchQuickShare(prefs.card.value, null) }.getOrDefault(false)
        log(if (opened) "opened Quick Share to bring up the Apple link (${if (covered) "covered by DapTap" else "no overlay permission: Quick Share stays visible"})"
            else "couldn't open Quick Share for the Apple link")
        if (!opened) { appleLinkStartedAt = 0L; uncover(); return }
        // Give up (and come back to DapTap) if the link or the iPhone never shows: the user walked away, or the
        // iPhone took Receive Only and never connected. Once connected, the iPhone's Share tap can take a while.
        appleLinkWatchdog?.cancel()
        val started = appleLinkStartedAt
        appleLinkWatchdog = scope.launch {
            while (appleLinkStartedAt == started) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                val why = when {
                    appleLinkUpAt == 0L && iphoneConnectedAt == 0L && now - started > LINK_UP_TIMEOUT_MS -> "the Apple link never came up"
                    appleLinkUpAt != 0L && iphoneConnectedAt == 0L && now - appleLinkUpAt > CONNECT_TIMEOUT_MS -> "the iPhone never connected"
                    now - started > SWAP_TIMEOUT_MS -> "timed out waiting for the iPhone's Share"
                    else -> null
                }
                if (why != null) { finishAppleLink(why); break }
            }
        }
    }

    /** Quick Share's Apple link is up: keep the NameDrop server listening on it, whatever the beacon does. */
    fun keepProbe(on: Boolean) {
        peerLinkUp = on
        if (on) {
            probeStopJob?.cancel()
            startNameDropServer()
        } else if (!beaconActive) {
            probeStopJob = scope.launch { delay(30_000); if (!beaconActive && !peerLinkUp) stopNameDropServer() }
        }
    }
    @Volatile private var peerLinkUp = false

    /**
     * No NameDrop frame now: a DapTap phone was just read or pushed to us, an iPhone is mid-handshake (a frame
     * in the 0.9 s redirect gap would tear down the resume), or a swap just ended with the phones still together.
     */
    fun beaconOnHold(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val peer = now - maxOf(lastPeerTagAt, lastPushAt) < 2_500
        val handshake = nameDropStartedAt > nameDropDoneAt && now - nameDropStartedAt < 3_000
        val justDone = now - maxOf(nameDropDoneAt, appleLinkFinishedAt) < 5_000
        // An Android phone without DapTap is here (or reading us): a burst would switch our card off mid-read.
        val android = now - maxOf(foreignTagAt, foreignReaderServedAt) < ANDROID_HOLD_MS
        return peer || handshake || justDone || android
    }

    /**
     * Whether a reader should be turned away from our NDEF tag. While the beacon runs, an iPhone tries a tag
     * read before it starts NameDrop; answering it makes iOS stop at the tag read. DapTap readers identify
     * themselves and are never refused; the opt-in web card needs the tag read, so it disables this.
     * Where the chip reports Apple frames, only a reader seen sending them (an iPhone) is refused. While the beacon
     * rests the iPhone is still turned away, and the refusal wakes the beacon.
     */
    fun refuseNdef(daptapReader: Boolean): Boolean {
        if (daptapReader || prefs.settings.value.webFallback) return false
        val now = SystemClock.elapsedRealtime()
        // Mid-NameDrop (including after Quick Share has taken the screen and the beacon has stopped), the iPhone
        // retries a tag read; it must keep being turned away, as 1.0.32 did.
        if (now - nameDropStartedAt < 10_000 || appleLinkStartedAt != 0L) return true
        val maybeIphone = (beaconEnabled && now - lastAppleFrameAt < 3_000) || (beaconActive && !framesReliable)
        if (!maybeIphone) return false
        // Our own poll just read this phone's tag: iPhones never answer a plain poll, Android phones do. Serve it
        // on its first try.
        val androidSeen = now - foreignTagAt < ANDROID_SEEN_MS
        // A reader that keeps asking without starting NameDrop is an Android phone without DapTap: serve it.
        if (!androidSeen && refusals.refuse(now)) return true
        if (now - foreignReaderServedAt > 6_000) later {
            log(if (androidSeen) "an Android phone without DapTap is reading us (we read its tag first): giving it our card"
                else "a reader kept asking for the tag without starting NameDrop (an Android phone without DapTap): giving it our card")
        }
        foreignReaderServedAt = now
        return false
    }

    /**
     * DapTap's own NFC poll (the activity's tag dispatch, between beacon bursts) read a tag with no DapTap or
     * contact card on it. That's an Android phone's card emulation, or a bank card or badge; an iPhone only
     * answers our NameDrop frame, never a plain poll (no logged iPhone tap ever showed one). [detail] describes
     * the tag, so a later log can tell phones from cards.
     */
    fun onForeignTag(detail: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - nameDropStartedAt < 10_000 || appleLinkStartedAt != 0L) {
            log("read a tag that isn't a contact card ($detail), during NameDrop")
            return
        }
        foreignTagAt = now
        log("read a tag that isn't a contact card ($detail): likely an Android phone, so its next read of us gets our card")
    }
    /** When DapTap's own poll last read a non-DapTap tag (see [onForeignTag]). */
    @Volatile private var foreignTagAt = -1_000_000L
    private val refusals = RefusalCap()
    /** When a reader was last served after being turned away: an Android phone without DapTap, not an iPhone. */
    @Volatile private var foreignReaderServedAt = -1_000_000L

    fun onNdefRefused() {
        val now = SystemClock.elapsedRealtime()
        refusedThisActivation = true
        if (now - lastRefusalLogAt > 2_000) {
            log("turned the tag read away so the iPhone moves on to NameDrop")
            mark("refused")
        }
        lastRefusalLogAt = now
    }

    /** Progress of the NameDrop handshake with an iPhone (HCE thread = main thread). Kept short: the iPhone waits. */
    fun onSnap(event: com.keshav.daptap.core.SnapApplet.Event, detail: String, peerUuid: String?, peerLabel: String?) {
        val now = SystemClock.elapsedRealtime()
        when (event) {
            com.keshav.daptap.core.SnapApplet.Event.BOOP_SELECTED -> {
                refusals.reset()
                val fresh = now - nameDropStartedAt > 10_000
                nameDropStartedAt = now
                lastAppleFrameAt = now
                if (fresh) mark("boop")
                later {
                    log("NameDrop: $detail")
                    if (fresh) {
                        log("iPhone started NameDrop with us")
                        beaconHistory?.invoke()?.let { log("NameDrop beacon: $it") }
                        // Get Quick Share's card file and intent ready while the handshake runs.
                        val card = prefs.card.value
                        launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { feedback.prepareQuickShare(card, Photos.readMine(context)) }
                            runCatching { nameDropCard() }
                        }
                    }
                    iphoneTouch()
                }
                return
            }
            com.keshav.daptap.core.SnapApplet.Event.SERVER_INFO -> nameDropStartedAt = now
            com.keshav.daptap.core.SnapApplet.Event.RESUMED -> {
                val busy = appleLinkStartedAt != 0L
                val fresh = now - nameDropDoneAt > 10_000 && !busy
                nameDropDoneAt = now
                if (busy) later { log("NameDrop: handshake again while a swap is still running; carrying on with that one") }
                if (fresh) {
                    mark("resumed")
                    // Quick Share opens the moment the iPhone lets go of the NFC link (onNameDropLinkEnded); this
                    // fallback covers a link loss Android never reports.
                    pendingAppleLinkAt = now
                    scope.launch {
                        delay(APPLE_LINK_FALLBACK_MS)
                        if (pendingAppleLinkAt == now) { pendingAppleLinkAt = -1_000_000L; bringUpAppleLink() }
                    }
                }
                later {
                    log("NameDrop: $detail")
                    if (fresh) {
                        log("NameDrop handshake complete: the iPhone should show its NameDrop card now")
                        _events.tryEmit("NameDrop started on the iPhone")
                    }
                }
                return
            }
            else -> {}
        }
        later {
            log("NameDrop: $detail")
            if (event == com.keshav.daptap.core.SnapApplet.Event.SERVER_INFO) log("NameDrop: iPhone listener $peerUuid, service ${peerLabel ?: "?"}")
        }
    }

    /** Runs [block] on the main thread after the current work (an HCE response) has returned. */
    private fun later(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        scope.launch(kotlinx.coroutines.Dispatchers.Main, block = block)

    /** Something touched us in the beacon's reader phase that isn't a DapTap phone. */
    fun onForeignTagInReaderPhase() {
        log("a non-DapTap device answered our NameDrop poll")
        lastAppleFrameAt = SystemClock.elapsedRealtime()
        iphoneTouch()
    }

    /** Our beacon found another phone: give the touch feedback now, not after its whole card has crossed. */
    fun onReaderTouch() {
        val now = SystemClock.elapsedRealtime()
        if (_phase.value !is Phase.Touching && _phase.value !is Phase.Sent) {
            touchStartedAt = now
            _phase.value = Phase.Touching(now)
            feedback.touch()
            if (!feedback.appInForeground() && prefs.settings.value.overlay) feedback.showOverlay(_phase.value)
        }
        scheduleReset()
    }

    /** Our card reached the phone whose card is on screen (sent back after theirs was shown). */
    fun markTheyHaveOurs() {
        _incoming.update { it?.copy(sharedBack = true) }
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

    /** The NFC link to a reader ended. [boopSelected]: it was an iPhone's NameDrop handshake. */
    fun onDeactivated(boopSelected: Boolean = false) {
        // We just turned an iPhone's tag read away: it now needs our NameDrop frame, so send one straight away
        // instead of waiting out the rest of the beacon's card phase.
        if (refusedThisActivation && !boopSelected && !beaconOnHold()) beaconKick?.invoke()
        refusedThisActivation = false
        scheduleReset(1_500)
    }

    /** Logged after the response went back (posted), so the reader never waits on it. */
    fun logApdu(command: ByteArray, response: ByteArray) {
        if (BuildConfig.DEBUG || prefs.settings.value.testingMode) {
            val sw = response.takeLast(2).toByteArray()
            mainHandler.post { log("> ${hex(command)}  < ${hex(sw)}") }
        }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---------------------------------------------------------------- tap timeline

    private val marks = ArrayList<Pair<String, Long>>()

    /** Notes when a step of the current tap happened, for one timing line per swap (any thread). */
    fun mark(what: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(marks) {
            if (marks.isEmpty() || now - marks.last().second > 30_000) marks.clear()
            marks += what to now
        }
    }

    private fun logTimeline() {
        val line = synchronized(marks) {
            if (marks.isEmpty()) return
            val t0 = marks.first().second
            marks.joinToString(" · ") { (w, t) -> "$w +%.2f".format(Locale.US, (t - t0) / 1000.0) }
        }
        log("timeline: $line")
    }

    private suspend fun decide(readAt: Long, served: Relay.Session) {
        val settings = prefs.settings.value
        val appleSeen = readAt - lastAppleFrameAt < 3_000
        // An Android phone without DapTap that read our tag (see refuseNdef): it has the card, nothing else to do.
        val tagReader = { foreignReaderServedAt >= touchStartedAt - 1_000 }
        if (!appleSeen && !tagReader()) {
            // Two DapTap phones read each other at the same time; give ours a moment to land.
            withTimeoutOrNull(PEER_WINDOW_MS) {
                while (lastPeerTagAt < touchStartedAt - 1_000) delay(50)
            }
        }
        val peer = when {
            lastPeerTagAt >= touchStartedAt - 1_000 -> Peer.ANDROID
            tagReader() -> Peer.UNKNOWN
            appleSeen -> Peer.IPHONE
            else -> Peer.UNKNOWN
        }
        log("decided peer=$peer (apple frames: $appleSeen${if (tagReader()) ", Android phone without DapTap" else ""})")
        var nameDropEngaged = false
        if (peer == Peer.IPHONE && beaconEnabled) {
            // The iPhone runs NameDrop with us next. Anything that takes the screen now (Quick Share) would stop
            // the beacon and the card, so wait for the handshake first.
            val started = withTimeoutOrNull(NAMEDROP_START_WAIT_MS) {
                while (nameDropStartedAt < touchStartedAt - 1_000 && !tagReader()) delay(50)
            } != null && !tagReader()
            if (tagReader()) {
                // Not an iPhone after all: logged below.
            } else if (started) {
                nameDropEngaged = true
                withTimeoutOrNull(NAMEDROP_FINISH_WAIT_MS) { while (nameDropDoneAt < nameDropStartedAt - 1_000) delay(50) }
                val done = nameDropDoneAt >= nameDropStartedAt - 1_000
                log(if (done) "NameDrop is up on the iPhone" else "NameDrop started but the iPhone didn't finish the handshake")
            } else {
                log("no NameDrop handshake from the iPhone")
            }
        }
        // Without the web card, the only reply that can come over the relay is a DapTap phone whose card
        // didn't make it back during the touch ("Send mine back"). Only then is it worth a connection.
        val plainAndroid = tagReader()
        if (plainAndroid) log("an Android phone without DapTap read our card")
        if (!settings.webFallback && peer != Peer.IPHONE && !plainAndroid && lastPushAt < touchStartedAt) listen(served, SWAP_LISTEN_MS)
        var airDrop = false
        // Once the iPhone has taken up NameDrop, an AirDrop request on top of it is exactly what NameDrop is
        // meant to replace, so it is never opened automatically then (the card screen still offers it).
        if (nameDropEngaged) log("NameDrop in progress: not opening Quick Share")
        if (peer != Peer.ANDROID && settings.airDropOnTap && !nameDropEngaged && !plainAndroid) {
            val card = prefs.card.value
            // A failed handoff must never take the app down mid-tap: log it and carry on.
            airDrop = runCatching { feedback.launchQuickShare(card, Photos.readMine(context)) }
                .onFailure { log("Quick Share failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            log(if (airDrop) "opened Quick Share with vCard" else "could not open Quick Share")
            if (airDrop && settings.autoPickIphone) AutoPick.arm()
        }
        val phase = Phase.Sent(if (plainAndroid) Peer.UNKNOWN else peer, airDrop, SystemClock.elapsedRealtime())
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
        // A photo that doesn't decode (an iPhone's PHOTO can be a reference, not an image) is dropped.
        present(card.sanitized(), prepareIncomingPhoto(photo), null, via)
    }

    private fun present(card: ContactCard, photo: ByteArray?, replyTo: Relay.Session?, via: String, theyHaveOurs: Boolean = false) =
        show(history.add(card, photo, via), photo, replyTo, theyHaveOurs, via)

    private fun show(person: Person, photo: ByteArray?, replyTo: Relay.Session?, theyHaveOurs: Boolean, via: String) {
        val current = _incoming.value
        // The same person read twice during one hold: keep the screen, just refresh the photo.
        if (current != null && current.person.id == person.id) {
            _incoming.value = current.copy(person = person, photo = photo ?: current.photo, replyTo = replyTo ?: current.replyTo,
                sharedBack = current.sharedBack || theyHaveOurs)
            return
        }
        _incoming.value = Incoming(person, photo, replyTo, sharedBack = theyHaveOurs)
        feedback.received()
        // During an iPhone swap the cover shows the card at once (DapTap comes forward when the swap ends).
        if (via == "namedrop" && swapCovered && feedback.coverCard(person, photo)) return
        if (!feedback.appInForeground()) feedback.notifyIncoming(person)
    }

    fun dismissIncoming() { _incoming.value = null }

    /** Names a card that arrived without one (an iPhone can send just a number and photo). */
    fun rename(person: Person, name: String) {
        val updated = history.rename(person.id, name) ?: return
        _incoming.update { if (it?.person?.id == person.id) it.copy(person = updated) else it }
    }

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

    /** Thread-safe (kwik, h3 workers, binder and main threads all log). */
    private val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

    fun log(line: String) {
        val entry = java.time.LocalTime.now().format(timeFmt) + "  " + line
        _log.update { (it + entry).takeLast(LOG_MAX) }
        // Also to logcat (release too), so the tap log can be read over adb while testing with a real iPhone.
        android.util.Log.i("DapTap", line)
    }

    fun clearLog() { _log.value = emptyList() }

    companion object {
        /** How long to wait for another DapTap phone's card before assuming an iPhone. Covers a cold start of
         *  DapTap on the other phone, which reads us through Android's tag dispatch and then pushes back. */
        const val PEER_WINDOW_MS = 2_000L
        /** How long after our poll read an Android phone's tag its read of us is served at once. */
        const val ANDROID_SEEN_MS = 8_000L
        /** No NameDrop frame this long after an Android phone without DapTap showed up or read us. */
        const val ANDROID_HOLD_MS = 3_000L
        /** After an iPhone shows up, how long to wait for it to start NameDrop (it may need a few beacon cycles). */
        const val NAMEDROP_START_WAIT_MS = 5_000L
        const val NAMEDROP_FINISH_WAIT_MS = 10_000L
        /** How long the NameDrop server stays up after a handshake once DapTap leaves the screen. */
        const val NAMEDROP_PHOTO_PX = 400
        const val NAMEDROP_PHOTO_MAX = 60_000
        const val PROBE_AFTER_NAMEDROP_MS = 90_000L
        /** The server never stops sooner than this after DapTap leaves the screen. */
        const val SERVER_LINGER_MS = 5_000L
        /** If RESUMED's NFC link loss never gets reported, Quick Share opens after this anyway. */
        const val APPLE_LINK_FALLBACK_MS = 300L
        /** The cover comes off this long after DapTap is brought back, if DapTap's first frame isn't reported. */
        const val UNCOVER_FALLBACK_MS = 700L
        /** Give up a swap if Quick Share doesn't bring up the Apple link, or the iPhone doesn't connect, in time. */
        const val LINK_UP_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val SWAP_TIMEOUT_MS = 75_000L
        /** Testing mode logs this much of an iPhone's Contact Poster outline. */
        const val POSTER_LOG_LINES = 150
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
        const val LOG_MAX = 1500

        private val HEX = "0123456789ABCDEF".toCharArray()
        fun hex(b: ByteArray): String {
            val out = CharArray(b.size * 2)
            b.forEachIndexed { i, x -> out[2 * i] = HEX[(x.toInt() shr 4) and 0xF]; out[2 * i + 1] = HEX[x.toInt() and 0xF] }
            return String(out)
        }
    }
}
