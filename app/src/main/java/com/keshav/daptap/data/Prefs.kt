package com.keshav.daptap.data

import android.content.Context
import android.content.SharedPreferences
import com.keshav.daptap.core.ContactCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Settings(
    /** Master switch: answer NFC taps at all. */
    val tapEnabled: Boolean = true,
    /** After a tap from an iPhone (or any non-DapTap phone), hand the card to Quick Share / AirDrop. */
    val airDropOnTap: Boolean = true,
    /** Also put the web-card link in the tag, so iPhones get a banner that opens it in Safari. */
    val webFallback: Boolean = false,
    /** Accessibility helper picks the single iPhone in Quick Share's list automatically. */
    val autoPickIphone: Boolean = false,
    /** Android 17+: while DapTap is open, poll with Apple's NameDrop frame so the iPhone plays its own NameDrop animation. */
    val nameDropBeacon: Boolean = true,
    /** Experimental: observe mode in the listen phase, to see the iPhone's reply frames on phones that only report them there. */
    val observeFrames: Boolean = false,
    /** Play the overlay animation when a tap happens while DapTap isn't open. */
    val overlay: Boolean = true,
    val onboarded: Boolean = false,
)

/**
 * Synchronous, process-wide storage. SharedPreferences (not DataStore) because the NFC service must
 * answer a reader within milliseconds on the main thread and cannot wait for an async load.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("daptap", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _card = MutableStateFlow(readCard())
    val card: StateFlow<ContactCard> = _card.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Bumped whenever the photo file changes so UI and NFC payload caches refresh. */
    private val _photoVersion = MutableStateFlow(sp.getLong(KEY_PHOTO_VERSION, 0))
    val photoVersion: StateFlow<Long> = _photoVersion.asStateFlow()

    private fun readCard(): ContactCard =
        sp.getString(KEY_CARD, null)?.let { runCatching { json.decodeFromString<ContactCard>(it) }.getOrNull() }
            ?: ContactCard()

    private fun readSettings(): Settings =
        sp.getString(KEY_SETTINGS, null)?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() }
            ?: Settings()

    fun saveCard(card: ContactCard) {
        val clean = card.sanitized()
        sp.edit().putString(KEY_CARD, json.encodeToString(clean)).apply()
        _card.value = clean
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        sp.edit().putString(KEY_SETTINGS, json.encodeToString(next)).apply()
        _settings.value = next
    }

    fun photoChanged() {
        val v = System.currentTimeMillis()
        sp.edit().putLong(KEY_PHOTO_VERSION, v).apply()
        _photoVersion.value = v
    }

    private companion object {
        const val KEY_CARD = "card"
        const val KEY_SETTINGS = "settings"
        const val KEY_PHOTO_VERSION = "photo_version"
    }
}
