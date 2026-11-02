package com.keshav.daptap.core

/**
 * Apple Enhanced Contactless Polling (ECP) frames, as documented by reverse-engineering work in
 * github.com/kormax/apple-enhanced-contactless-polling:
 *
 *   6A | version 02 | config | type | subtype | data…        (CRC added by the NFC controller)
 *
 * NameDrop: type 05 (AirDrop family), TCI 01 00 01, data = 6-byte BLE address. An iPhone that sees it
 * plays the NameDrop "warp" animation and answers by polling with the AirDrop frame (TCI 01 00 00).
 */
object AppleEcp {
    const val HEADER: Byte = 0x6A
    const val TYPE_AIRDROP = 0x05

    enum class Kind { NAMEDROP, AIRDROP, OTHER_AIRDROP_FAMILY, TRANSIT, ACCESS, IDENTITY, IGNORE, UNKNOWN }

    /** The NameDrop polling frame (14 bytes, within Android's 16-byte annotation limit). */
    fun nameDropFrame(bleAddress: ByteArray): ByteArray {
        require(bleAddress.size == 6)
        // config 0x89: auto-presentment on, "authentication not required" off, data length 9 (TCI 3 + MAC 6)
        return byteArrayOf(HEADER, 0x02, 0x89.toByte(), TYPE_AIRDROP.toByte(), 0x00, 0x01, 0x00, 0x01) + bleAddress
    }

    fun classify(frame: ByteArray): Kind? {
        if (frame.size < 4 || frame[0] != HEADER) return null
        if (frame[1].toInt() != 0x02) return Kind.UNKNOWN // v1 frames carry no type we act on
        val type = frame[3].toInt() and 0xFF
        return when (type) {
            TYPE_AIRDROP -> {
                val tci = if (frame.size >= 8) frame.copyOfRange(5, 8) else ByteArray(0)
                when {
                    tci.contentEquals(byteArrayOf(0x01, 0x00, 0x01)) -> Kind.NAMEDROP
                    tci.contentEquals(byteArrayOf(0x01, 0x00, 0x00)) -> Kind.AIRDROP
                    else -> Kind.OTHER_AIRDROP_FAMILY
                }
            }
            0x01 -> Kind.TRANSIT
            0x02 -> Kind.ACCESS
            0x03 -> Kind.IDENTITY
            0x00 -> Kind.IGNORE
            else -> Kind.UNKNOWN
        }
    }

    /** iPhone-to-iPhone sharing frames (NameDrop/AirDrop). Payment, transit and door readers are excluded. */
    fun isIphoneSharing(frame: ByteArray): Boolean = when (classify(frame)) {
        Kind.NAMEDROP, Kind.AIRDROP, Kind.OTHER_AIRDROP_FAMILY -> true
        else -> false
    }
}
