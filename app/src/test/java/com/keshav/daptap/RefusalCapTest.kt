package com.keshav.daptap

import com.keshav.daptap.tap.RefusalCap
import org.junit.Assert.assertEquals
import org.junit.Test

class RefusalCapTest {
    private fun run(cap: RefusalCap, vararg at: Long) = at.map { cap.refuse(it) }

    @Test fun androidReaderIsServedOnItsThirdTry() {
        // The 6:58 PM log: an Android phone without DapTap asked at +0, +1.07, +2.46 and +3.87 s.
        assertEquals(listOf(true, true, false, false), run(RefusalCap(), 0, 1_070, 2_460, 3_870))
    }

    @Test fun iphoneTurnedAwayOnceStillGetsItsRefusal() {
        // A real iPhone asks once, then starts NameDrop.
        assertEquals(listOf(true), run(RefusalCap(), 10_000))
    }

    @Test fun twoQuickTriesAreNotEnough() {
        // Two refusals inside 2 s aren't proof: the third, still inside 2 s, is turned away too.
        assertEquals(listOf(true, true, true, false), run(RefusalCap(), 0, 400, 900, 2_100))
    }

    @Test fun aNewApproachStartsOver() {
        val cap = RefusalCap()
        run(cap, 0, 1_000, 2_500)
        assertEquals(listOf(true, true), run(cap, 20_000, 21_000))
    }

    @Test fun nameDropResetsTheCount() {
        val cap = RefusalCap()
        run(cap, 0, 1_000)
        cap.reset()
        assertEquals(listOf(true), run(cap, 2_500))
    }
}
