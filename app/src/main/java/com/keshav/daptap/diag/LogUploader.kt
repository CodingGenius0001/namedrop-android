package com.keshav.daptap.diag

import android.content.Context
import com.keshav.daptap.core.Base64Url
import com.keshav.daptap.core.SecretBox
import com.keshav.daptap.relay.Relay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream

/**
 * Testing mode: the tap log goes to a private, end-to-end encrypted ntfy topic, so whoever holds the testing code can
 * read it without the phone being plugged in.
 *
 * ntfy.sh forgets messages after 12 hours, so the log lives on the phone for 96 hours ([LogStore]) and reaches the
 * server two ways: each burst of activity sends the lines the server hasn't had yet, and whenever the last full
 * upload is [HISTORY_EVERY_MS] old (DapTap opening, or [LogRefreshJob] every few hours) the whole 96 hours go again.
 * Lines carry sequence numbers, so `scripts/read-test-log.py` merges the overlap into one timeline.
 *
 * Wire format (one upload = up to [MAX_CHUNKS] ntfy messages, each under ntfy's 4 KB inline limit):
 *   message = SecretBox.seal(key, uploadId[8] || index[1] || total[1] || gzip(text)[slice])
 *   text = header lines, then stored lines (`<seq>\t<date time>  <text>`)
 * The server sees only ciphertext on a random topic. ntfy.sh allows 250 messages a day per visitor and bursts of 60,
 * refilling one every 5 s, so sends are paced ([pace]) and the header counts the day's messages.
 */
class LogUploader(
    context: Context,
    private val base: String,
    private val store: LogStore,
    private val trigger: StateFlow<List<String>>,
    private val enabled: Flow<Boolean>,
    private val header: () -> String,
) {
    private val prefs = context.getSharedPreferences("daptap_diag", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val sending = Mutex()

    /** `<topic id>.<key>`: what the tester hands over. Made once per install. */
    val code: String = prefs.getString(KEY_CODE, null) ?: run {
        val id = Base64Url.encode(ByteArray(16).also { random.nextBytes(it) })
        "$id.${Base64Url.encode(SecretBox.newKey())}".also { prefs.edit().putString(KEY_CODE, it).apply() }
    }
    private val topic get() = TOPIC_PREFIX + code.substringBefore('.')
    private val key get() = Base64Url.decode(code.substringAfter('.'))

    /** The last line the server has had (from either kind of upload). */
    private var uploadedSeq: Long
        get() = prefs.getLong(KEY_UPLOADED_SEQ, 0)
        set(v) { prefs.edit().putLong(KEY_UPLOADED_SEQ, v).apply() }
    /** Why the last upload failed, for the next one's header (logging it would only trigger another upload). */
    @Volatile private var lastFailure: String? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(trigger, enabled) { lines, on -> lines to on }.collectLatest { (_, on) ->
                if (!on) return@collectLatest
                // Wait for the tap to finish logging, then send. A failed send retries a few times (a new line
                // cancels this and starts over).
                delay(QUIET_MS)
                for (attempt in 0 until RETRIES) {
                    if (withContext(Dispatchers.IO) { refreshHistory() && uploadNew() }) break
                    delay(RETRY_MS * (attempt + 1))
                }
            }
        }
    }

    /** Sends the lines the server hasn't had yet. True when nothing is left to send. */
    suspend fun uploadNew(): Boolean = sending.withLock {
        withContext(Dispatchers.IO) {
            val lines = store.since(uploadedSeq)
            if (lines.isEmpty()) return@withContext true
            sendLines(lines, "new lines")
        }
    }

    /**
     * Sends the whole 96 hours again if the last full upload is old enough that ntfy may forget it soon. True when
     * nothing needed sending or it all went.
     */
    suspend fun refreshHistory(force: Boolean = false): Boolean = sending.withLock {
        withContext(Dispatchers.IO) {
            val last = prefs.getLong(KEY_HISTORY_AT, 0)
            val now = System.currentTimeMillis()
            if (!force && now - last in 0 until HISTORY_EVERY_MS) return@withContext true
            // Leave room in ntfy's daily allowance for the new lines of the next taps.
            if (sentToday() > DAILY_LIMIT - HISTORY_MAX_CHUNKS - 30) { lastFailure = "history skipped: ${sentToday()} messages sent today"; return@withContext true }
            val lines = store.all()
            if (lines.isEmpty()) return@withContext true
            val ok = sendLines(lines, "history: the last ${LogStore.KEEP_MS / 3_600_000} hours", maxChunks = HISTORY_MAX_CHUNKS)
            if (ok) prefs.edit().putLong(KEY_HISTORY_AT, now).apply()
            ok
        }
    }

    /** Splits [lines] into uploads that fit, oldest first. With [maxChunks], the oldest lines past it are left out. */
    private fun sendLines(lines: List<String>, what: String, maxChunks: Int = Int.MAX_VALUE): Boolean {
        var budget = maxChunks
        val batches = mutableListOf<Pair<List<String>, List<ByteArray>>>()
        // Newest first, so a budget cuts the oldest lines.
        var end = lines.size
        while (end > 0 && budget > 0) {
            var take = minOf(end, MAX_LINES_PER_UPLOAD)
            var chunks: List<ByteArray>
            while (true) {
                chunks = pack(what, lines.subList(end - take, end))
                if (chunks.size <= minOf(MAX_CHUNKS, budget) || take <= 1) break
                take = take * 2 / 3
            }
            if (chunks.size > budget) break
            batches += lines.subList(end - take, end) to chunks
            budget -= chunks.size
            end -= take
        }
        for ((batch, chunks) in batches.asReversed()) {
            if (!send(chunks)) return false
            LogStore.seqOf(batch.last())?.let { if (it > uploadedSeq) uploadedSeq = it }
        }
        lastFailure = null
        return true
    }

    private fun pack(what: String, lines: List<String>): List<ByteArray> {
        val head = buildString {
            append(header()).append('\n')
            append("upload: $what · ${lines.size} lines · ntfy messages sent today ${sentToday()}/$DAILY_LIMIT")
            lastFailure?.let { append(" · previous upload failed: $it") }
            append('\n')
        }
        val packed = gzip((head + lines.joinToString("\n")).toByteArray(Charsets.UTF_8))
        return packed.toList().chunked(CHUNK_BYTES).map { it.toByteArray() }
    }

    private fun send(chunks: List<ByteArray>): Boolean {
        val uploadId = ByteArray(8).also { random.nextBytes(it) }
        return chunks.withIndex().all { (i, c) ->
            val body = SecretBox.seal(key, uploadId + byteArrayOf(i.toByte(), chunks.size.toByte()) + c)
            post(body)
        }
    }

    /** One ntfy message, paced, retrying when ntfy says too many requests. */
    private fun post(body: String): Boolean {
        for (attempt in 0 until POST_TRIES) {
            pace()
            val request = Request.Builder().url(base.trimEnd('/') + "/" + topic).post(body.toRequestBody(null)).build()
            val result = runCatching { Relay.defaultClient.newCall(request).execute().use { it.code } }
            countSent()
            val code = result.getOrNull()
            if (code != null && code in 200..299) return true
            lastFailure = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "HTTP $code"
            if (code != 429) return false
            Thread.sleep(RATE_LIMITED_WAIT_MS)
        }
        return false
    }

    // A token bucket a little stricter than ntfy.sh's (60 at once, one more every 5 s), leaving room for DapTap's
    // other relay messages.
    private var tokens = BURST.toDouble()
    private var tokensAt = System.nanoTime()

    @Synchronized private fun pace() {
        while (true) {
            val now = System.nanoTime()
            tokens = minOf(BURST.toDouble(), tokens + (now - tokensAt) / 1e6 / REFILL_MS)
            tokensAt = now
            if (tokens >= 1) { tokens -= 1; return }
            Thread.sleep(((1 - tokens) * REFILL_MS).toLong().coerceAtLeast(50))
        }
    }

    private fun today() = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
    private fun sentToday(): Int = if (prefs.getString(KEY_SENT_DAY, null) == today()) prefs.getInt(KEY_SENT_COUNT, 0) else 0
    private fun countSent() { prefs.edit().putString(KEY_SENT_DAY, today()).putInt(KEY_SENT_COUNT, sentToday() + 1).apply() }

    private fun gzip(b: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

    companion object {
        private const val KEY_CODE = "log_code"
        private const val KEY_UPLOADED_SEQ = "log_uploaded_seq"
        private const val KEY_HISTORY_AT = "log_history_at"
        private const val KEY_SENT_DAY = "log_sent_day"
        private const val KEY_SENT_COUNT = "log_sent_count"
        const val TOPIC_PREFIX = "daptap-log-"
        /** Sealed + base64url, 2,900 bytes stays under Relay.MAX_BODY. */
        private const val CHUNK_BYTES = 2_900
        /** One upload's messages; the index and total are one byte each. */
        private const val MAX_CHUNKS = 24
        private const val MAX_LINES_PER_UPLOAD = 4_000
        /** The full history, at most (about 145 KB compressed, several thousand lines); past it the oldest lines are left out. */
        private const val HISTORY_MAX_CHUNKS = 50
        /** Re-send the history before ntfy's 12-hour cache drops the last copy. */
        const val HISTORY_EVERY_MS = 9 * 3_600_000L
        private const val QUIET_MS = 4_000L
        private const val RETRIES = 3
        private const val RETRY_MS = 30_000L
        private const val POST_TRIES = 3
        private const val RATE_LIMITED_WAIT_MS = 15_000L
        private const val BURST = 40
        private const val REFILL_MS = 5_500.0
        private const val DAILY_LIMIT = 250
    }
}
