package com.sizwe.tvremote

import com.sizwe.tvremote.diagnostics.DiagnosticsLog
import com.sizwe.tvremote.diagnostics.LatencyProbe
import com.sizwe.tvremote.diagnostics.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The diagnostics log is the tool used to debug everything else, so its own behaviour needs to be
 * trustworthy — an event silently dropped here is a bug hunt sent down the wrong path.
 */
class DiagnosticsTest {

    @Before
    fun reset() = DiagnosticsLog.clear()

    @Test
    fun `records entries oldest first`() {
        DiagnosticsLog.i("A", "first")
        DiagnosticsLog.w("B", "second")
        DiagnosticsLog.e("C", "third")

        val entries = DiagnosticsLog.entries.value
        assertEquals(listOf("first", "second", "third"), entries.map { it.message })
        assertEquals(listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR), entries.map { it.level })
    }

    @Test
    fun `drops the oldest entries once capacity is reached`() {
        repeat(700) { DiagnosticsLog.i("Flood", "event $it") }

        val entries = DiagnosticsLog.entries.value
        assertEquals(600, entries.size)
        // The newest must survive; the oldest must not.
        assertEquals("event 699", entries.last().message)
        assertFalse(entries.any { it.message == "event 0" })
    }

    @Test
    fun `export carries the snapshot header and every entry`() {
        DiagnosticsLog.i("ADB", "connected", "banner=device::model=TestTV")

        val exported = DiagnosticsLog.export("--- header ---")

        assertTrue(exported.contains("--- header ---"))
        assertTrue(exported.contains("connected"))
        assertTrue("detail line should survive export", exported.contains("model=TestTV"))
    }

    @Test
    fun `throwable overload flattens to type and message`() {
        DiagnosticsLog.e("ADB", "write failed", java.io.IOException("broken pipe"))

        val entry = DiagnosticsLog.entries.value.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("IOException: broken pipe", entry.detail)
    }

    @Test
    fun `clear empties the buffer`() {
        DiagnosticsLog.i("A", "something")
        DiagnosticsLog.clear()
        assertTrue(DiagnosticsLog.entries.value.isEmpty())
    }

    @Test
    fun `latency median and verdict track the samples`() {
        val fast = LatencyProbe.Result(samples = listOf(20L, 30L, 40L), failures = 0)
        assertEquals(30L, fast.median)
        assertEquals(20L, fast.min)
        assertEquals(40L, fast.max)
        assertTrue(fast.verdict.contains("gesture surface is viable"))

        val slow = LatencyProbe.Result(samples = listOf(400L, 500L, 600L), failures = 0)
        assertEquals(500L, slow.median)
        assertTrue(slow.verdict.contains("sluggish"))
    }

    @Test
    fun `latency with no samples reports failure rather than a bogus zero`() {
        val nothing = LatencyProbe.Result(samples = emptyList(), failures = 5)
        assertFalse(nothing.succeeded)
        assertTrue(nothing.toReportText().contains("no samples completed"))
    }
}
