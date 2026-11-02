package com.keshav.daptap.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.keshav.daptap.core.ContactCard
import com.keshav.daptap.core.VCard
import java.io.File

/**
 * Hands the user's card to the phone's own nearby-share stack. On the Galaxy S26 that's Samsung Quick
 * Share, which speaks AirDrop: the iPhone then shows its built-in AirDrop contact request.
 */
object QuickShare {
    const val SAMSUNG_PACKAGE = "com.samsung.android.app.sharelive"
    const val GMS_PACKAGE = "com.google.android.gms"
    const val VCARD_MIME = "text/x-vcard"

    data class Target(val label: String, val component: ComponentName?)

    /** Writes "<Name>.vcf" into cache/share and returns a content:// URI other apps can read. */
    fun vcardUri(context: Context, card: ContactCard, photo: ByteArray?): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safe = card.name.ifBlank { "Contact" }.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(60).ifBlank { "Contact" }
        val file = File(dir, "$safe.vcf")
        file.writeText(VCard.write(card, photo))
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    private fun sendIntent(uri: Uri, card: ContactCard) = Intent(Intent.ACTION_SEND).apply {
        type = VCARD_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, card.name)
        clipData = ClipData.newUri(null, card.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The best direct Quick Share entry on this phone, or null if only the generic share sheet exists. */
    fun findTarget(context: Context): Target? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_SEND).setType(VCARD_MIME)
        // Samsung Quick Share: its own package handles ACTION_SEND directly (AirDrop-capable on S26+).
        resolve(pm, Intent(probe).setPackage(SAMSUNG_PACKAGE))?.let { return Target("Quick Share", it) }
        // Google Quick Share (Pixel and other Android): lives inside Play services.
        resolve(pm, Intent(probe).setPackage(GMS_PACKAGE)) { name ->
            name.contains("nearby", true) || name.contains("sharing", true)
        }?.let { return Target("Quick Share", it) }
        return null
    }

    private fun resolve(pm: PackageManager, intent: Intent, accept: (String) -> Boolean = { true }): ComponentName? =
        runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo }
                .firstOrNull { it.exported && accept(it.name) }
                ?.let { ComponentName(it.packageName, it.name) }
        }.getOrNull()

    /** Builds the intent that opens Quick Share (or the share sheet) with the user's card attached. */
    fun buildIntent(context: Context, card: ContactCard, photo: ByteArray?): Intent {
        val uri = vcardUri(context, card, photo)
        val send = sendIntent(uri, card)
        val target = findTarget(context)
        val intent = if (target?.component != null) Intent(send).setComponent(target.component)
        else Intent.createChooser(send, "Send your card")
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Opens Quick Share with the card. Returns false if nothing could be launched. */
    fun launch(context: Context, card: ContactCard, photo: ByteArray?): Boolean = runCatching {
        context.startActivity(buildIntent(context, card, photo))
        true
    }.getOrElse {
        // Direct component refused (e.g. not exported on this build): fall back to the system sheet.
        runCatching {
            val uri = vcardUri(context, card, photo)
            context.startActivity(Intent.createChooser(sendIntent(uri, card), "Send your card")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            true
        }.getOrDefault(false)
    }
}
