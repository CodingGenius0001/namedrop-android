package com.keshav.daptap.diag

import android.content.Context
import com.keshav.daptap.core.Base64Url
import com.keshav.daptap.core.SecretBox
import com.keshav.daptap.relay.Relay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream

/**
 * Testing mode: the tap log goes to a private, end-to-end encrypted ntfy topic after every burst of activity,
 * so whoever holds the testing code can read it without the phone being plugged in.
 *
 * Wire format (one upload = up to [MAX_CHUNKS] ntfy messages, each under ntfy's 4 KB inline limit):
 *   message = SecretBox.seal(key, uploadId[8] || index[1] || total[1] || gzip(log)[slice])
 * The server sees only ciphertext on a random topic; ntfy.sh drops messages after about 12 hours.
 */
class LogUploader(
    context: Context,
    private val base: String,
    private val log: StateFlow<List<String>>,
    private val enabled: kotlinx.coroutines.flow.Flow<Boolean>,
    private val header: () -> String,
) {
    private val prefs = context.getSharedPreferences("daptap_diag", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    /** `<topic id>.<key>`: what the tester hands over. Made once per install. */
    val code: String = prefs.getString(KEY_CODE, null) ?: run {
        val id = Base64Url.encode(ByteArray(16).also { random.nextBytes(it) })
        "$id.${Base64Url.encode(SecretBox.newKey())}".also { prefs.edit().putString(KEY_CODE, it).apply() }
    }
    private val topic get() = TOPIC_PREFIX + code.substringBefore('.')
    private val key get() = Base64Url.decode(code.substringAfter('.'))

    private var lastSent: String? = null
    private var lastUploadAt = 0L

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(log, enabled) { lines, on -> lines to on }.collectLatest { (lines, on) ->
                if (!on || lines.isEmpty()) return@collectLatest
                // Wait for the tap to finish logging, then send the whole log once (at most every 15 s).
                val sinceLast = android.os.SystemClock.elapsedRealtime() - lastUploadAt
                delay(maxOf(QUIET_MS, MIN_INTERVAL_MS - sinceLast))
                val last = lines.last()
                if (last == lastSent) return@collectLatest
                lastUploadAt = android.os.SystemClock.elapsedRealtime()
                if (withContext(Dispatchers.IO) { upload(lines) }) lastSent = last
            }
        }
    }

    private fun upload(lines: List<String>): Boolean {
        var take = lines.size
        while (take > 0) {
            val text = header() + "\n" + lines.takeLast(take).joinToString("\n")
            val packed = gzip(text.toByteArray(Charsets.UTF_8))
            val chunks = packed.toList().chunked(CHUNK_BYTES).map { it.toByteArray() }
            if (chunks.size <= MAX_CHUNKS) return send(chunks)
            take = take * 2 / 3
        }
        return false
    }

    private fun send(chunks: List<ByteArray>): Boolean {
        val uploadId = ByteArray(8).also { random.nextBytes(it) }
        return chunks.withIndex().all { (i, c) ->
            val body = SecretBox.seal(key, uploadId + byteArrayOf(i.toByte(), chunks.size.toByte()) + c)
            val request = Request.Builder().url(base.trimEnd('/') + "/" + topic).post(body.toRequestBody(null)).build()
            runCatching { Relay.defaultClient.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
        }
    }

    private fun gzip(b: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.toByteArray()

    companion object {
        private const val KEY_CODE = "log_code"
        const val TOPIC_PREFIX = "daptap-log-"
        /** Sealed + base64url, 2,900 bytes stays under Relay.MAX_BODY. */
        private const val CHUNK_BYTES = 2_900
        private const val MAX_CHUNKS = 24
        private const val QUIET_MS = 4_000L
        private const val MIN_INTERVAL_MS = 15_000L
    }
}
