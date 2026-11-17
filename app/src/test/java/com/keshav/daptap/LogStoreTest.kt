package com.keshav.daptap

import com.keshav.daptap.diag.LogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LogStoreTest {
    private fun dir(): File = Files.createTempDirectory("logstore").toFile()

    @Test fun numbersLinesAndSplitsMultiLineEntries() {
        val store = LogStore(dir())
        store.append("one")
        store.append("two\nthree")
        val all = store.all()
        assertEquals(listOf(1L, 2L, 3L), all.map { LogStore.seqOf(it) })
        assertTrue(all[2].endsWith("  three"))
        assertEquals(listOf("three"), store.since(2).map { it.substringAfter("  ") })
    }

    @Test fun sequenceContinuesAfterRestart() {
        val d = dir()
        LogStore(d).apply { append("a"); append("b"); all() }
        val again = LogStore(d)
        again.append("c")
        assertEquals(listOf(1L, 2L, 3L), again.all().map { LogStore.seqOf(it) })
    }

    @Test fun dropsLinesOlderThanFourDays() {
        val d = dir()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        val old = LocalDateTime.now().minusHours(97).format(fmt)
        val recent = LocalDateTime.now().minusHours(95).format(fmt)
        File(d, "tap-log.txt").writeText("1\t$old  too old\n2\t$recent  kept\n")
        val store = LogStore(d)
        store.append("new")
        val all = store.all()
        assertEquals(listOf("kept", "new"), all.map { it.substringAfter("  ") })
        assertEquals(listOf(2L, 3L), all.map { LogStore.seqOf(it) })
    }
}
