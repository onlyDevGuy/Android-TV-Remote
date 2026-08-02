package com.sizwe.tvremote.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel(val label: String) {
    DEBUG("DBG"),
    INFO("INF"),
    WARN("WRN"),
    ERROR("ERR"),
}

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    /** Optional second line: a stack trace, a banner, a raw payload. */
    val detail: String? = null,
) {
    val time: String get() = TIME_FORMAT.format(Date(timestampMs))

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * In-app event log, readable on the phone while standing in front of the TV.
 *
 * This exists because the interesting failures in this app happen on hardware that cannot be
 * emulated, in a different room from the computer. Without it, diagnosing a failed Bluetooth
 * registration or a stalled ADB handshake means tethering the phone and reading `logcat` — which
 * is exactly the moment you are least able to do that.
 *
 * A singleton rather than a container-injected dependency on purpose: the most valuable call sites
 * are inside [com.sizwe.tvremote.adb.AdbConnection]'s companion factory and the Bluetooth callback
 * object, neither of which has anywhere natural to receive an injected logger. Everything is also
 * mirrored to logcat, so the usual tooling still works.
 */
object DiagnosticsLog {

    /** Ring buffer size. Big enough for a full connect-fail-reconnect cycle, small enough to hold. */
    private const val CAPACITY = 600

    private val nextId = AtomicLong(1)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Newest last, matching the order events happened. The UI reverses for display. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, message: String, detail: String? = null) =
        record(LogLevel.DEBUG, tag, message, detail)

    fun i(tag: String, message: String, detail: String? = null) =
        record(LogLevel.INFO, tag, message, detail)

    fun w(tag: String, message: String, detail: String? = null) =
        record(LogLevel.WARN, tag, message, detail)

    fun e(tag: String, message: String, detail: String? = null) =
        record(LogLevel.ERROR, tag, message, detail)

    /**
     * Overload for the exception case. Distinct from the [String] version rather than nullable-Any
     * so call sites stay readable; the throwable is flattened to type and message, because a full
     * stack trace is noise on a phone screen and logcat already has it.
     */
    fun e(tag: String, message: String, error: Throwable) =
        record(LogLevel.ERROR, tag, message, "${error::class.java.simpleName}: ${error.message}")

    @Synchronized
    private fun record(level: LogLevel, tag: String, message: String, detail: String?) {
        val entry = LogEntry(
            id = nextId.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            detail = detail,
        )

        val current = _entries.value
        _entries.value = if (current.size >= CAPACITY) {
            current.drop(current.size - CAPACITY + 1) + entry
        } else {
            current + entry
        }

        val logcatMessage = detail?.let { "$message | $it" } ?: message
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, logcatMessage)
            LogLevel.INFO -> Log.i(tag, logcatMessage)
            LogLevel.WARN -> Log.w(tag, logcatMessage)
            LogLevel.ERROR -> Log.e(tag, logcatMessage)
        }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    /**
     * Plain-text dump for the clipboard, oldest first so it reads as a narrative.
     * [header] is the environment snapshot, which is the half of a bug report people forget.
     */
    fun export(header: String): String = buildString {
        appendLine(header)
        appendLine()
        appendLine("--- event log (${_entries.value.size} entries) ---")
        _entries.value.forEach { entry ->
            append(entry.time)
            append("  ")
            append(entry.level.label)
            append("  ")
            append(entry.tag)
            append("  ")
            append(entry.message)
            entry.detail?.let {
                append("\n                     ")
                append(it)
            }
            appendLine()
        }
    }
}
