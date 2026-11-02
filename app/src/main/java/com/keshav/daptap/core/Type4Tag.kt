package com.keshav.daptap.core

/**
 * NFC Forum Type 4 Tag (T4T 2.0) emulation as a pure state machine. The HostApduService feeds every
 * command APDU here. The iPhone's background tag reader and Android's tag dispatcher both speak this.
 *
 * Flow a reader performs:
 *   SELECT AID D2760000850101 -> SELECT CC (E103) -> READ CC -> SELECT NDEF (E104) -> READ NLEN -> READ body
 */
class Type4Tag(
    /** Called on application select; returns the NDEF message to serve for this read session. */
    private val messageProvider: () -> ByteArray,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        /** A reader selected our NDEF application: something is touching the phone. */
        fun onReaderSelected() {}
        /** The reader has read the entire NDEF message. */
        fun onMessageRead() {}
    }

    private enum class File { NONE, CC, NDEF }

    private var appSelected = false
    private var file = File.NONE
    private var ndefFile = ByteArray(0)
    private var cc = ByteArray(0)
    private var readBodyEnd = false
    private var readComplete = false

    fun reset() {
        appSelected = false
        file = File.NONE
        readBodyEnd = false
        readComplete = false
    }

    fun process(apdu: ByteArray?): ByteArray {
        if (apdu == null || apdu.size < 4) return SW_WRONG_LENGTH
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        return when (ins) {
            0xA4 -> select(apdu, p1, p2)
            0xB0 -> readBinary(apdu, p1, p2)
            0xD6 -> SW_SECURITY_NOT_SATISFIED // read-only tag
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    private fun select(apdu: ByteArray, p1: Int, p2: Int): ByteArray {
        val data = commandData(apdu) ?: return SW_WRONG_LENGTH
        return when (p1) {
            0x04 -> if (data.contentEquals(NDEF_AID)) {
                appSelected = true
                file = File.NONE
                readBodyEnd = false
                readComplete = false
                val message = messageProvider()
                ndefFile = byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()) + message
                cc = capabilityContainer(ndefFile.size)
                listener.onReaderSelected()
                SW_OK
            } else SW_FILE_NOT_FOUND
            0x00 -> {
                if (!appSelected) return SW_FILE_NOT_FOUND
                when {
                    data.contentEquals(CC_FILE_ID) -> { file = File.CC; SW_OK }
                    data.contentEquals(NDEF_FILE_ID) -> { file = File.NDEF; SW_OK }
                    else -> SW_FILE_NOT_FOUND
                }
            }
            else -> SW_INCORRECT_P1P2
        }
    }

    private fun readBinary(apdu: ByteArray, p1: Int, p2: Int): ByteArray {
        if (p1 and 0x80 != 0) return SW_INCORRECT_P1P2
        val offset = (p1 shl 8) or p2
        val content = when (file) {
            File.CC -> cc
            File.NDEF -> ndefFile
            File.NONE -> return SW_NO_CURRENT_EF
        }
        if (offset > content.size) return SW_WRONG_OFFSET
        val le = expectedLength(apdu)
        val length = minOf(le, content.size - offset, MAX_LE)
        val chunk = content.copyOfRange(offset, offset + length)
        if (file == File.NDEF && offset + length >= content.size && content.size > 2 && offset + length > 2) {
            readBodyEnd = true
        }
        if (readBodyEnd && !readComplete) {
            readComplete = true
            listener.onMessageRead()
        }
        return chunk + SW_OK
    }

    /** Lc-prefixed data for a case-3/4 short APDU. */
    private fun commandData(apdu: ByteArray): ByteArray? {
        if (apdu.size < 5) return null
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return null
        return apdu.copyOfRange(5, 5 + lc)
    }

    /** Le of a case-2 short APDU (00 means 256). Missing Le means "as much as you have". */
    private fun expectedLength(apdu: ByteArray): Int = when {
        apdu.size == 5 -> (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
        apdu.size == 7 && apdu[4].toInt() == 0 -> // extended Le
            ((apdu[5].toInt() and 0xFF) shl 8 or (apdu[6].toInt() and 0xFF)).let { if (it == 0) 65536 else it }
        else -> MAX_LE
    }

    private fun capabilityContainer(ndefFileSize: Int): ByteArray {
        val max = maxOf(ndefFileSize, 0x0080)
        return byteArrayOf(
            0x00, 0x0F, // CCLEN
            0x20, // mapping version 2.0
            (MAX_LE ushr 8).toByte(), MAX_LE.toByte(), // MLe
            0x00, 0xFF.toByte(), // MLc
            0x04, 0x06, // NDEF File Control TLV
            0xE1.toByte(), 0x04, // file id
            (max ushr 8).toByte(), max.toByte(), // max NDEF file size
            0x00, // read access: granted
            0xFF.toByte(), // write access: none
        )
    }

    companion object {
        /** Max bytes per READ BINARY response. Short-APDU friendly, and plenty fast. */
        const val MAX_LE = 0xF0

        val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03)
        val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04)

        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val SW_WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        val SW_INCORRECT_P1P2 = byteArrayOf(0x6A, 0x86.toByte())
        val SW_WRONG_OFFSET = byteArrayOf(0x6B, 0x00)
        val SW_NO_CURRENT_EF = byteArrayOf(0x69, 0x86.toByte())
        val SW_SECURITY_NOT_SATISFIED = byteArrayOf(0x69, 0x82.toByte())
        val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    }
}
