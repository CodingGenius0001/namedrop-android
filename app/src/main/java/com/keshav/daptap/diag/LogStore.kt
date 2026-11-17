package com.keshav.daptap.diag

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Testing mode: the tap log on disk, so it outlives DapTap's process and ntfy's 12-hour cache. Every line gets a
 * sequence number that never repeats, so uploads can overlap (a new tap's lines, and the full history re-sent before
 * ntfy forgets it) and the reader still merges them into one timeline. Lines older than [KEEP_MS] are dropped.
 *
 * File format, one line each: `<seq>\t<yyyy-MM-dd HH:mm:ss.SSS>  <text>`. A multi-line entry is stored as several
 * lines with the same time.
 */
class LogStore(dir: File) {
    private val file = File(dir, "tap-log.txt")
    /** All file access happens here, so a log call from the main thread (HCE) never waits on storage. */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "daptap-logstore").apply { isDaemon = true } }
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var nextSeq = -1L
    @Volatile var lastSeq = 0L; private set
    private var appendsSincePrune = 0

    init { io.execute { load() } }

    /** Saves [line] (called from any thread). */
    fun append(line: String) {
        val stamp = LocalDateTime.now().format(timeFmt)
        io.execute {
            runCatching {
                val text = line.lines().joinToString("") { part -> "${nextSeq++}\t$stamp  $part\n" }
                file.appendText(text)
                lastSeq = nextSeq - 1
                if (++appendsSincePrune >= PRUNE_EVERY) { appendsSincePrune = 0; prune() }
            }
        }
    }

    /** Stored lines with a sequence number above [afterSeq], oldest first (blocking: call off the main thread). */
    fun since(afterSeq: Long): List<String> = onIo {
        runCatching { file.readLines() }.getOrDefault(emptyList()).filter { (seqOf(it) ?: -1) > afterSeq }
    }

    /** Everything kept (the last 96 hours), oldest first, after dropping what's too old (blocking). */
    fun all(): List<String> = onIo { prune(); runCatching { file.readLines() }.getOrDefault(emptyList()) }

    private fun <T> onIo(block: () -> T): T = io.submit(block).get()

    private fun load() {
        runCatching {
            prune()
            val last = if (file.exists()) file.readLines().lastOrNull()?.let(::seqOf) else null
            nextSeq = (last ?: 0L) + 1
            lastSeq = nextSeq - 1
        }.onFailure { nextSeq = 1 }
    }

    /** Drop lines older than [KEEP_MS], and the oldest beyond [MAX_BYTES]. Runs on [io]. */
    private fun prune() {
        if (!file.exists()) return
        val lines = file.readLines()
        val cutoff = LocalDateTime.now().minusNanos(KEEP_MS * 1_000_000).format(timeFmt)
        // Times sort as text; a line whose time can't be read is kept with its neighbours.
        var keep = lines.dropWhile { l -> timeOf(l)?.let { it < cutoff } ?: false }
        var bytes = keep.sumOf { it.length + 1L }
        if (bytes > MAX_BYTES) {
            var drop = 0
            while (bytes > MAX_BYTES * 3 / 4 && drop < keep.size) { bytes -= keep[drop].length + 1; drop++ }
            keep = keep.drop(drop)
        }
        if (keep.size == lines.size) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(keep.joinToString("") { "$it\n" })
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        /** How long the phone keeps the tap log: four days, so a test can be read days later. */
        const val KEEP_MS = 96 * 3_600_000L
        /** A ceiling for a log that's busy for four days (about 25,000 lines). */
        const val MAX_BYTES = 4L * 1024 * 1024
        private const val PRUNE_EVERY = 2_000

        fun seqOf(line: String): Long? = line.substringBefore('\t', "").toLongOrNull()
        private fun timeOf(line: String): String? = line.substringAfter('\t', "").takeIf { it.length >= 23 }?.substring(0, 23)
    }
}
