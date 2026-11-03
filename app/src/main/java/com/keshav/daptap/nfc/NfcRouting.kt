package com.keshav.daptap.nfc

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import com.keshav.daptap.core.Type4Tag

/**
 * Who else answers NFC tag reads on this phone. Android routes a tap by AID; DapTap's tag answers on the
 * standard NFC Forum NDEF AID, and if another app also registers it, Android only picks DapTap while DapTap is
 * on screen (its preferred service). With DapTap closed, a phone reading us through Android itself (not DapTap)
 * may then reach the other app or nothing and show "unknown tag type". DapTap-to-DapTap reads avoid this by
 * using DapTap's own AID.
 */
object NfcRouting {
    private val NDEF_AID = Type4Tag.NDEF_AID.joinToString("") { "%02X".format(it) }

    /** Labels of other apps that also claim the NDEF tag AID. */
    fun otherNdefClaimants(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        listOf(
            "android.nfc.cardemulation.action.HOST_APDU_SERVICE" to "android.nfc.cardemulation.host_apdu_service",
            "android.nfc.cardemulation.action.OFF_HOST_APDU_SERVICE" to "android.nfc.cardemulation.off_host_apdu_service",
        ).flatMap { (action, meta) ->
            pm.queryIntentServices(Intent(action), PackageManager.GET_META_DATA)
                .filter { it.serviceInfo.packageName != context.packageName }
                .filter { ri -> runCatching { ri.serviceInfo.loadXmlMetaData(pm, meta)?.use(::claimsNdef) }.getOrNull() == true }
                .map { it.loadLabel(pm).toString().ifBlank { it.serviceInfo.packageName } }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun claimsNdef(xml: XmlResourceParser): Boolean {
        while (xml.next() != XmlResourceParser.END_DOCUMENT) {
            if (xml.eventType != XmlResourceParser.START_TAG) continue
            if (xml.name != "aid-filter" && xml.name != "aid-prefix-filter") continue
            val aid = xml.getAttributeValue("http://schemas.android.com/apk/res/android", "name")?.uppercase()?.trimEnd('*') ?: continue
            if (aid == NDEF_AID || (xml.name == "aid-prefix-filter" && NDEF_AID.startsWith(aid))) return true
        }
        return false
    }
}
