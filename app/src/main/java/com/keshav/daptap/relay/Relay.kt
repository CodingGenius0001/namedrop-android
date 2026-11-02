package com.keshav.daptap.relay

import com.keshav.daptap.core.Base64Url
import com.keshav.daptap.core.RelayMessage
import com.keshav.daptap.core.SecretBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * End-to-end encrypted mailbox on a public ntfy server. Only used by the web-card fallback and the
 * "share back" hop between DapTap phones; the server only ever sees AES-GCM ciphertext on a random topic.
 */
class Relay(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient,
) {
    data class Session(val id: String, val key: ByteArray) {
        val topic: String get() = TOPIC_PREFIX + id
        val keyText: String get() = Base64Url.encode(key)
    }

    suspend fun publish(session: Session, message: RelayMessage): Boolean = withContext(Dispatchers.IO) {
        val body = SecretBox.seal(session.key, message.encode())
        require(body.length <= MAX_BODY) { "relay message too large: ${body.length}" }
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/" + session.topic)
            .post(body.toRequestBody(null)).build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /**
     * Streams decrypted messages for [session] until the collector is cancelled. Includes messages posted
     * before subscribing (ntfy caches them), reconnects with backoff, and never repeats a message id.
     */
    fun subscribe(session: Session): Flow<RelayMessage> = flow {
        val seen = HashSet<String>()
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            try {
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/" + session.topic + "/json?since=all")
                    .build()
                streamingClient(client).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    backoff = 1_000L
                    val source = response.body?.source() ?: throw java.io.IOException("no body")
                    while (currentCoroutineContext().isActive) {
                        val line = source.readUtf8Line() ?: break
                        val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                        if (obj["event"]?.jsonPrimitive?.contentOrNull != "message") continue
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (!seen.add(id)) continue
                        val text = obj["message"]?.jsonPrimitive?.contentOrNull ?: continue
                        val plain = SecretBox.open(session.key, text) ?: continue
                        RelayMessage.decode(plain)?.let { emit(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // network hiccup: fall through to backoff
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val TOPIC_PREFIX = "daptap-"
        /** ntfy.sh turns bodies over 4096 bytes into attachments; stay under it. */
        const val MAX_BODY = 4000
        private val random = SecureRandom()

        fun newSession(): Session {
            val id = ByteArray(16).also { random.nextBytes(it) }
            return Session(Base64Url.encode(id), SecretBox.newKey())
        }

        fun sessionOf(id: String, key: String): Session? {
            if (id.length !in 16..64 || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
            val k = Base64Url.decodeOrNull(key) ?: return null
            return if (k.size == 32) Session(id, k) else null
        }

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private fun streamingClient(base: OkHttpClient) = base.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS) // ntfy sends keepalives every ~45 s
            .build()
    }
}
