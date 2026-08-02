package com.sizwe.tvremote.diagnostics

import com.sizwe.tvremote.adb.AdbClient
import kotlinx.coroutines.delay

/**
 * Measures how long a command actually takes to reach the TV and come back.
 *
 * Why it matters beyond curiosity: key events are sent fire-and-forget, so nothing in normal use
 * ever reveals the round trip. This is the only place the number is visible, and it settles two
 * real questions — whether button presses feel instant on a congested network, and whether a
 * swipe/gesture surface is viable at all (it is not, if a press takes a third of a second).
 *
 * The probe runs `true`, the cheapest possible shell command, and waits for the stream to close.
 * That makes each sample an **upper bound**: a real key press skips the wait for output, so actual
 * button latency sits below these figures.
 */
class LatencyProbe(private val client: AdbClient) {

    data class Result(
        val samples: List<Long>,
        val failures: Int,
    ) {
        val min: Long get() = samples.minOrNull() ?: 0
        val max: Long get() = samples.maxOrNull() ?: 0

        val median: Long
            get() = if (samples.isEmpty()) 0 else samples.sorted()[samples.size / 2]

        val succeeded: Boolean get() = samples.isNotEmpty()

        /** Plain-language read on whether the remote will feel responsive. */
        val verdict: String
            get() = when {
                !succeeded -> "No samples completed - the TV is not answering."
                median < 60 -> "Excellent. Presses will feel instant; a gesture surface is viable."
                median < 150 -> "Good. Presses feel immediate; gestures would be usable."
                median < 350 -> "Noticeable lag. Fine for buttons, too slow for a gesture surface."
                else -> "Poor. Expect presses to feel sluggish; check for Wi-Fi congestion."
            }

        fun toReportText(): String = if (!succeeded) {
            "Latency: no samples completed ($failures failures)"
        } else {
            "Latency: median ${median}ms (min ${min}ms, max ${max}ms) " +
                "over ${samples.size} samples, $failures failures"
        }
    }

    /**
     * Runs [count] round trips, discarding the first — it pays for opening the session and would
     * skew the median on an otherwise healthy connection.
     */
    suspend fun run(count: Int = 6): Result {
        val samples = mutableListOf<Long>()
        var failures = 0

        repeat(count) { index ->
            val started = System.nanoTime()
            val result = client.shell("true", waitForOutput = true)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            if (result.isSuccess) {
                if (index > 0) samples += elapsedMs
            } else {
                failures++
            }
            delay(INTER_SAMPLE_DELAY_MS)
        }

        val outcome = Result(samples, failures)
        DiagnosticsLog.i("Latency", outcome.toReportText(), outcome.verdict)
        return outcome
    }

    private companion object {
        const val INTER_SAMPLE_DELAY_MS = 120L
    }
}
