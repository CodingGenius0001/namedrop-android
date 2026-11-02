package com.keshav.daptap.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Compact card + session envelope carried inside the NFC tag (and the web-card URL fragment).
 * Short keys keep the NFC transfer small; the web page (web/c/app.js) reads the same keys.
 */
@Serializable
data class WirePayload(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("n") val name: String = "",
    @SerialName("p") val phone: String = "",
    @SerialName("e") val email: String = "",
    @SerialName("c") val company: String = "",
    @SerialName("t") val title: String = "",
    @SerialName("w") val website: String = "",
    @SerialName("i") val instagram: String = "",
    @SerialName("l") val label: Int = 0,
    /** Relay session id (topic suffix) the receiver can reply on. */
    @SerialName("s") val session: String = "",
    /** base64url AES-256 key for replies / photo. */
    @SerialName("k") val key: String = "",
    /** Optional inline JPEG photo (base64url), only used over NFC between DapTap phones. */
    @SerialName("ph") val photo: String = "",
) {
    fun card(): ContactCard = ContactCard(name, phone, email, company, title, website, instagram, label).sanitized()

    fun photoBytes(): ByteArray? = photo.takeIf { it.isNotEmpty() }?.let { Base64Url.decodeOrNull(it) }

    fun encode(): String = Base64Url.encode(json.encodeToString(this).toByteArray())

    companion object {
        const val VERSION = 1
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        fun of(card: ContactCard, session: String = "", key: String = "", photo: ByteArray? = null) = WirePayload(
            name = card.name, phone = card.phone, email = card.email, company = card.company,
            title = card.title, website = card.website, instagram = card.instagram, label = card.label,
            session = session, key = key, photo = photo?.let { Base64Url.encode(it) } ?: "",
        )

        fun decode(encoded: String): WirePayload? = runCatching {
            val p = json.decodeFromString<WirePayload>(String(Base64Url.decode(encoded)))
            if (p.version > VERSION + 10) null else p
        }.getOrNull()

        /** Accepts the full web URL (payload in the fragment) or just the encoded payload. */
        fun fromUrl(url: String): WirePayload? {
            val fragment = url.substringAfter('#', missingDelimiterValue = "")
            return if (fragment.isNotEmpty()) decode(fragment) else null
        }

        fun webUrl(base: String, payload: WirePayload): String = base.trimEnd('/') + "/c/#" + payload.encode()
    }
}

/** Encrypted messages exchanged over the relay. */
@Serializable
data class RelayMessage(
    /** "card" (a reply card, optional photo) or "photo" (the sharer's avatar for the web card). */
    @SerialName("t") val type: String,
    @SerialName("card") val card: WirePayload? = null,
    @SerialName("photo") val photo: String = "",
    /** Random id so a phone can ignore its own messages on a shared topic. */
    @SerialName("from") val from: String = "",
) {
    fun encode(): ByteArray = WirePayload.json.encodeToString(this).toByteArray()

    companion object {
        fun decode(bytes: ByteArray): RelayMessage? =
            runCatching { WirePayload.json.decodeFromString<RelayMessage>(String(bytes)) }.getOrNull()
    }
}
