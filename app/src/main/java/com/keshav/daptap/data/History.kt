package com.keshav.daptap.data

import android.content.Context
import com.keshav.daptap.core.ContactCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Person(
    val id: String = UUID.randomUUID().toString(),
    val card: ContactCard,
    /** File name inside filesDir/people, if they shared a photo. */
    val photo: String? = null,
    val at: Long = System.currentTimeMillis(),
    /** How their card reached us: "tap" (DapTap phone), "airdrop" (vCard via Quick Share), "web" (web card reply), "share". */
    val via: String = "tap",
    val saved: Boolean = false,
)

/** People whose cards you've received, newest first. Stored on-device only. */
class History(private val context: Context) {
    private val file = File(context.filesDir, "people.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _people = MutableStateFlow(load())
    val people: StateFlow<List<Person>> = _people.asStateFlow()

    private fun load(): List<Person> = runCatching {
        if (file.exists()) json.decodeFromString<List<Person>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    /** Adds a person. A repeat of the same card within 2 minutes (the same tap read twice) is merged, not duplicated. */
    fun add(card: ContactCard, photo: ByteArray?, via: String): Person = synchronized(lock) {
        val now = System.currentTimeMillis()
        val dup = _people.value.firstOrNull { it.card == card && now - it.at < 120_000 }
        val photoName = photo?.let { bytes ->
            val name = (dup?.id ?: UUID.randomUUID().toString()) + ".jpg"
            File(Photos.peopleDir(context), name).writeBytes(bytes)
            name
        } ?: dup?.photo
        val person = dup?.copy(photo = photoName, at = now) ?: Person(card = card, photo = photoName, via = via, at = now)
        val list = listOf(person) + _people.value.filter { it.id != person.id }
        persist(list.take(MAX))
        person
    }

    fun markSaved(id: String) = synchronized(lock) {
        persist(_people.value.map { if (it.id == id) it.copy(saved = true) else it })
    }

    fun remove(id: String) = synchronized(lock) {
        _people.value.firstOrNull { it.id == id }?.photo?.let { File(Photos.peopleDir(context), it).delete() }
        persist(_people.value.filter { it.id != id })
    }

    fun photoBytes(person: Person): ByteArray? =
        person.photo?.let { File(Photos.peopleDir(context), it) }?.takeIf { it.exists() }?.readBytes()

    private fun persist(list: List<Person>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(list))
        tmp.renameTo(file)
        _people.value = list
    }

    private companion object { const val MAX = 300 }
}
