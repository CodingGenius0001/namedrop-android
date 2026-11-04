package com.keshav.daptap

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkDeltaTest {
    private val first = "tx 100 txdrop 0 txerr 0 rx 90 rxdrop 0 ns 2 na 2 discards 0 | peer fe80::1 lladdr aa:bb router REACHABLE | 9 neighbours"

    @Test fun `first sample is shown in full`() =
        assertEquals("tx 100 txdrop 0 txerr 0 rx 90 rxdrop 0 ns 2 na 2 discards 0 | peer fe80::1 lladdr aa:bb router REACHABLE | 9 neighbours",
            DapTapApp.linkDelta(null, first))

    @Test fun `quiet second shows only the counts`() =
        assertEquals("tx+5 rx+4", DapTapApp.linkDelta(first,
            "tx 105 txdrop 0 txerr 0 rx 94 rxdrop 0 ns 2 na 2 discards 0 | peer fe80::1 lladdr aa:bb router REACHABLE | 9 neighbours"))

    @Test fun `drops, neighbour probes and a changed entry all show`() =
        assertEquals("tx+1 rx+0 txdrop+3 txerr+0 rxdrop+0 discards+0 ns+3 na+0 | peer fe80::1 lladdr aa:bb router FAILED | 9 neighbours",
            DapTapApp.linkDelta(first,
                "tx 101 txdrop 3 txerr 0 rx 90 rxdrop 0 ns 5 na 2 discards 0 | peer fe80::1 lladdr aa:bb router FAILED | 9 neighbours"))
}
