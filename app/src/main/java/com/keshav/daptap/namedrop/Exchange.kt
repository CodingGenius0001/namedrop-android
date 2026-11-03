package com.keshav.daptap.namedrop

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser

/**
 * The HTTP/3 requests an iPhone makes over the NameDrop connection, as captured by namedrop-re-public
 * (scripts/asquic-receiver.py): `POST /Hello`, then `POST /Exchange` carrying its vCard; we answer each with a
 * binary plist, and our own vCard in the /Exchange reply. Pure, so it is unit-tested without a network.
 */
class Exchange(
    /** Our listener UUID, upper case: the identity the NFC handshake committed to. */
    private val listenerUuid: String,
    private val ours: () -> Card,
    private val onCard: (vcard: ByteArray, fullName: String?) -> Unit,
    private val log: (String) -> Unit,
) {
    class Card(val fullName: String, val email: String?, val phone: String?, val vcard: ByteArray)
    class Response(val status: Int, val body: ByteArray, val closeAfter: Boolean = false)

    fun handle(method: String?, path: String?, body: ByteArray): Response {
        val p = (path ?: "").trimEnd('/').lowercase()
        val req = runCatching { if (body.isEmpty()) null else PropertyListParser.parse(body) as? NSDictionary }.getOrNull()
        log("NameDrop exchange: ${method ?: "?"} ${path ?: "?"} (${body.size} B${req?.let { ", keys " + it.allKeys().joinToString(",") } ?: ""})")
        return when {
            p.endsWith("hello") -> {
                val resp = NSDictionary().apply {
                    put("id", NSDictionary().apply { put("id", listenerUuid) })
                    put("featureFlags", req?.get("featureFlags") ?: NSNumber(3))
                    put("contextType", req?.get("contextType") ?: NSDictionary().apply { put("unknown", NSDictionary()) })
                }
                Response(200, plist(resp))
            }
            p.endsWith("exchange") -> {
                val vcard = (req?.get("VCardData") as? NSData)?.bytes()
                val name = (req?.get("FullName") as? NSString)?.content
                if (vcard != null) onCard(vcard, name) else log("NameDrop exchange: the iPhone sent no card (Receive Only)")
                val card = ours()
                val handle = NSDictionary().apply {
                    when {
                        !card.email.isNullOrBlank() -> put("email", NSDictionary().apply { put("_0", card.email) })
                        !card.phone.isNullOrBlank() -> put("phone", NSDictionary().apply { put("_0", card.phone) })
                    }
                }
                val resp = NSDictionary().apply {
                    put("TransferID", req?.get("TransferID") ?: NSDictionary().apply { put("id", "") })
                    put("FullName", card.fullName)
                    put("Handle", handle)
                    put("VCardData", NSData(card.vcard))
                }
                Response(200, plist(resp), closeAfter = true)
            }
            p.endsWith("discover") -> Response(200, plist(NSDictionary().apply {
                put("ReceiverComputerName", ours().fullName)
                put("ReceiverModelName", "DapTap")
                put("IsAirDropable", true)
            }))
            p.endsWith("ask") -> Response(200, plist(NSDictionary().apply {
                put("ReceiverComputerName", ours().fullName)
                put("ReceiverModelName", "DapTap")
            }))
            else -> {
                log("NameDrop exchange: unknown path $path, answered 404")
                Response(404, ByteArray(0))
            }
        }
    }

    private fun plist(o: NSObject): ByteArray = BinaryPropertyListWriter.writeToArray(o)
}
