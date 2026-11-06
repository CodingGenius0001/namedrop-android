package com.keshav.daptap.applink

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Per-second datagram counts on the Apple link for the current swap (testing-mode diagnostics): which way a stall
 * went, in or out, shows at a glance.
 */
class LinkTraffic {
    private val received = AtomicIntegerArray(SECONDS)
    private val sent = AtomicIntegerArray(SECONDS)
    @Volatile private var start = SystemClock.elapsedRealtime()

    fun reset() {
        for (i in 0 until SECONDS) { received.set(i, 0); sent.set(i, 0) }
        start = SystemClock.elapsedRealtime()
    }

    fun received() = count(received)
    fun sent() = count(sent)

    private fun count(arr: AtomicIntegerArray) {
        val s = ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
        if (s in 0 until SECONDS) arr.incrementAndGet(s)
    }

    /** "+0s 4/3 · +1s 12/9 …" up to the last second with traffic, or null if none. */
    fun summary(): String? {
        val last = (SECONDS - 1 downTo 0).firstOrNull { received.get(it) + sent.get(it) > 0 } ?: return null
        return (0..last).joinToString(" · ") { "+${it}s ${received.get(it)}/${sent.get(it)}" }
    }

    private companion object {
        const val SECONDS = 90
    }
}
