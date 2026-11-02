package com.keshav.daptap.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import com.keshav.daptap.core.ContactCard

/** Reads the phone's own "My profile" card (needs READ_CONTACTS). Returns null when there isn't one. */
object ProfileReader {
    /** The profile's photo (full size when the phone has one), or null. */
    fun photo(context: Context): ByteArray? = runCatching {
        ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, ContactsContract.Profile.CONTENT_URI, true)
            ?.use { it.readBytes() }
    }.getOrNull()?.takeIf { it.size > 64 }

    fun read(context: Context): ContactCard? = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI, ContactsContract.Contacts.Data.CONTENT_DIRECTORY)
        var name = ""; var phone = ""; var email = ""; var company = ""; var title = ""; var website = ""
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA4),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(0) ?: continue
                val d1 = c.getString(1).orEmpty()
                val d4 = c.getString(2).orEmpty()
                when (mime) {
                    CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> if (name.isBlank()) name = d1
                    CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (phone.isBlank()) phone = d1
                    CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (email.isBlank()) email = d1
                    CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> { if (company.isBlank()) company = d1; if (title.isBlank()) title = d4 }
                    CommonDataKinds.Website.CONTENT_ITEM_TYPE -> if (website.isBlank()) website = d1
                }
            }
        }
        ContactCard(name, phone, email, company, title, website).sanitized().takeUnless { it.isEmpty }
    }.getOrNull()
}
