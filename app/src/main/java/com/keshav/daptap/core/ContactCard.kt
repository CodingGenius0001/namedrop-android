package com.keshav.daptap.core

import kotlinx.serialization.Serializable

/**
 * The contact card a person shares. Text only; the photo travels separately as JPEG bytes
 * because it is large and optional.
 */
@Serializable
data class ContactCard(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val company: String = "",
    val title: String = "",
    val website: String = "",
    val instagram: String = "",
    /** Index into the card label palette (the colour of this person's card). */
    val label: Int = 0,
) {
    val isEmpty: Boolean get() = name.isBlank() && phone.isBlank() && email.isBlank()

    /** Card with every field trimmed and length-capped so hostile payloads can't blow up the UI. */
    fun sanitized(): ContactCard = ContactCard(
        name = name.clean(80),
        phone = phone.clean(40),
        email = email.clean(120),
        company = company.clean(80),
        title = title.clean(80),
        website = website.clean(200),
        instagram = instagram.clean(60).removePrefix("@"),
        label = label.coerceIn(0, LABEL_COUNT - 1),
    )

    val firstName: String get() = name.trim().substringBefore(' ')

    val initials: String
        get() = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .let { parts ->
                when {
                    parts.isEmpty() -> "?"
                    parts.size == 1 -> parts[0].take(1)
                    else -> parts.first().take(1) + parts.last().take(1)
                }
            }.uppercase()

    /** "Title · Company", whichever exist. */
    val roleLine: String get() = listOf(title.trim(), company.trim()).filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        const val LABEL_COUNT = 6
        private fun String.clean(max: Int): String =
            replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").trim().take(max)
    }
}
