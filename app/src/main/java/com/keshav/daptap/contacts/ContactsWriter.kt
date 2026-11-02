package com.keshav.daptap.contacts

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.core.VCard
import com.keshav.daptap.data.Photos

object ContactsWriter {

    /** Inserts the contact directly (needs WRITE_CONTACTS). Returns the new contact's lookup URI or null. */
    fun insert(context: Context, card: ContactCard, photo: ByteArray?): Uri? = runCatching {
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        fun data(mime: String, vararg values: Pair<String, Any?>) {
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, mime)
            values.forEach { (k, v) -> b.withValue(k, v) }
            ops += b.build()
        }
        data(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.StructuredName.DISPLAY_NAME to card.name)
        if (card.phone.isNotBlank()) data(CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            CommonDataKinds.Phone.NUMBER to card.phone, CommonDataKinds.Phone.TYPE to CommonDataKinds.Phone.TYPE_MOBILE)
        if (card.email.isNotBlank()) data(CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            CommonDataKinds.Email.ADDRESS to card.email, CommonDataKinds.Email.TYPE to CommonDataKinds.Email.TYPE_OTHER)
        if (card.company.isNotBlank() || card.title.isNotBlank()) data(CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            CommonDataKinds.Organization.COMPANY to card.company.ifBlank { null },
            CommonDataKinds.Organization.TITLE to card.title.ifBlank { null },
            CommonDataKinds.Organization.TYPE to CommonDataKinds.Organization.TYPE_WORK)
        if (card.website.isNotBlank()) data(CommonDataKinds.Website.CONTENT_ITEM_TYPE,
            CommonDataKinds.Website.URL to VCard.normalizeUrl(card.website),
            CommonDataKinds.Website.TYPE to CommonDataKinds.Website.TYPE_HOMEPAGE)
        if (card.instagram.isNotBlank()) data(CommonDataKinds.Website.CONTENT_ITEM_TYPE,
            CommonDataKinds.Website.URL to "https://instagram.com/${card.instagram}",
            CommonDataKinds.Website.TYPE to CommonDataKinds.Website.TYPE_PROFILE)
        contactPhoto(photo)?.let { data(CommonDataKinds.Photo.CONTENT_ITEM_TYPE, CommonDataKinds.Photo.PHOTO to it) }

        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawUri = results.firstOrNull()?.uri ?: return null
        ContactsContract.RawContacts.getContactLookupUri(context.contentResolver, rawUri)
    }.getOrNull()

    /** Fallback that needs no permission: opens the Contacts editor pre-filled. */
    fun insertIntent(card: ContactCard, photo: ByteArray?): Intent {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        intent.putExtra(ContactsContract.Intents.Insert.NAME, card.name)
        if (card.phone.isNotBlank()) {
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, card.phone)
            intent.putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, CommonDataKinds.Phone.TYPE_MOBILE)
        }
        if (card.email.isNotBlank()) intent.putExtra(ContactsContract.Intents.Insert.EMAIL, card.email)
        if (card.company.isNotBlank()) intent.putExtra(ContactsContract.Intents.Insert.COMPANY, card.company)
        if (card.title.isNotBlank()) intent.putExtra(ContactsContract.Intents.Insert.JOB_TITLE, card.title)
        val rows = ArrayList<ContentValues>()
        if (card.website.isNotBlank()) rows += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, CommonDataKinds.Website.CONTENT_ITEM_TYPE)
            put(CommonDataKinds.Website.URL, VCard.normalizeUrl(card.website))
            put(CommonDataKinds.Website.TYPE, CommonDataKinds.Website.TYPE_HOMEPAGE)
        }
        if (card.instagram.isNotBlank()) rows += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, CommonDataKinds.Website.CONTENT_ITEM_TYPE)
            put(CommonDataKinds.Website.URL, "https://instagram.com/${card.instagram}")
            put(CommonDataKinds.Website.TYPE, CommonDataKinds.Website.TYPE_PROFILE)
        }
        // Binder transactions cap at ~1 MB; a 256 px photo stays far below that.
        Photos.thumbnail(photo ?: ByteArray(0), 256, 60_000)?.let { small ->
            rows += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                put(CommonDataKinds.Photo.PHOTO, small)
            }
        }
        if (rows.isNotEmpty()) intent.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, rows)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun contactPhoto(photo: ByteArray?): ByteArray? =
        photo?.let { Photos.thumbnail(it, 720, 400_000) }
}
