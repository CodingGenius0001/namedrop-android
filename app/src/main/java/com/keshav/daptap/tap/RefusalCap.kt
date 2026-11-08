package com.keshav.daptap.tap

/**
 * How many times a tag reader is turned away before it gets the card anyway. While DapTap beacons, a reader
 * that might be an iPhone is turned away so the iPhone moves on to NameDrop. A real iPhone takes that cue at
 * once (every logged one started NameDrop after at most one refusal), but this phone's NFC chip never reports
 * the iPhone's Apple frames, so it can't tell an iPhone from an Android phone without DapTap. An Android phone
 * just keeps asking, about once a second, so after [maxRefusals] refusals spread over at least [minSpanMs] it is
 * served. Pure, for tests: the caller passes the clock.
 */
class RefusalCap(
    private val maxRefusals: Int = 2,
    private val minSpanMs: Long = 2_000,
    /** A gap this long between reads starts a new approach (the phones were taken apart). */
    private val approachGapMs: Long = 6_000,
) {
    private var count = 0
    private var first = 0L
    private var last = 0L

    /** A reader asks for the tag at [now]; true to turn it away, false to serve it. */
    fun refuse(now: Long): Boolean {
        if (count == 0 || now - last > approachGapMs) { count = 0; first = now }
        last = now
        if (count >= maxRefusals && now - first >= minSpanMs) return false
        count++
        return true
    }

    /** An iPhone started NameDrop: the next approach starts fresh. */
    fun reset() { count = 0 }
}
