package com.keshav.daptap.tap

import android.os.SystemClock

/** Shared flag between the tap engine and the accessibility helper: "the next Quick Share sheet is ours". */
object AutoPick {
    @Volatile private var armedUntil = 0L

    /** The running accessibility service, told to start or stop listening so it costs nothing at rest. */
    @Volatile var listener: ((Boolean) -> Unit)? = null

    fun arm(windowMs: Long = 25_000) {
        armedUntil = SystemClock.elapsedRealtime() + windowMs
        listener?.invoke(true)
    }

    fun disarm() {
        armedUntil = 0
        listener?.invoke(false)
    }

    val armed: Boolean get() = SystemClock.elapsedRealtime() < armedUntil
}
