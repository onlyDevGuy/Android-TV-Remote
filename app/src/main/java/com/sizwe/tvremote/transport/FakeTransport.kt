package com.sizwe.tvremote.transport

import android.util.Log
import com.sizwe.tvremote.core.ConnectionState
import com.sizwe.tvremote.core.RemoteKey
import com.sizwe.tvremote.core.RemoteTarget
import com.sizwe.tvremote.core.RemoteTransport
import com.sizwe.tvremote.core.TransportCapability
import com.sizwe.tvremote.core.TransportType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 0 transport. Reaches no hardware — it logs every press to logcat under [TAG] and keeps a
 * short in-memory history the debug panel renders, so the UI can be built and demoed before any
 * TV is in the room. Also stays useful afterwards as the "no device configured" placeholder and
 * as a fixture for UI tests.
 */
class FakeTransport(
    private val artificialLatencyMs: Long = 40L,
) : RemoteTransport {

    override val type = TransportType.FAKE

    override val capabilities = setOf(
        TransportCapability.KEYS,
        TransportCapability.TEXT_INPUT,
        TransportCapability.APP_LAUNCH,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    override var target: RemoteTarget? = null
        private set

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** Rolling log of what the UI asked for, newest first. Rendered by the debug sheet. */
    val eventLog: StateFlow<List<String>> = _log.asStateFlow()

    override suspend fun connect(target: RemoteTarget): Result<Unit> {
        this.target = target
        _state.value = ConnectionState.Connecting("Pretending to reach ${target.label}")
        delay(300)
        _state.value = ConnectionState.Connected("Demo device", target.label)
        record("connect(${target.label})")
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        record("disconnect()")
        _state.value = ConnectionState.Disconnected("Demo transport stopped")
    }

    override suspend fun sendKey(key: RemoteKey): Result<Unit> {
        delay(artificialLatencyMs)
        record("key ${key.name}")
        return Result.success(Unit)
    }

    override suspend fun sendKeyDown(key: RemoteKey): Result<Unit> {
        record("key ${key.name} DOWN")
        return Result.success(Unit)
    }

    override suspend fun sendKeyUp(key: RemoteKey): Result<Unit> {
        record("key ${key.name} UP")
        return Result.success(Unit)
    }

    override suspend fun sendText(text: String): Result<Unit> {
        delay(artificialLatencyMs)
        record("text \"$text\"")
        return Result.success(Unit)
    }

    override suspend fun launchApp(packageName: String, activity: String?): Result<Unit> {
        delay(artificialLatencyMs)
        record("launch $packageName${activity?.let { "/$it" }.orEmpty()}")
        return Result.success(Unit)
    }

    override suspend fun listPackages(): Result<List<String>> = Result.success(
        listOf(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
        ),
    )

    private fun record(event: String) {
        Log.d(TAG, event)
        _log.value = (listOf(stamp() + "  " + event) + _log.value).take(MAX_LOG)
    }

    private fun stamp(): String {
        val now = System.currentTimeMillis()
        val s = (now / 1000) % 60
        val m = (now / 60_000) % 60
        return "%02d:%02d.%03d".format(m, s, now % 1000)
    }

    private companion object {
        const val TAG = "FakeTransport"
        const val MAX_LOG = 60
    }
}
