package com.sizwe.tvremote.transport

import android.util.Log
import com.sizwe.tvremote.adb.AdbTransport
import com.sizwe.tvremote.bluetooth.BluetoothHidTransport
import com.sizwe.tvremote.core.ConnectionState
import com.sizwe.tvremote.core.RemoteKey
import com.sizwe.tvremote.core.RemoteTarget
import com.sizwe.tvremote.core.RemoteTransport
import com.sizwe.tvremote.core.TransportCapability
import com.sizwe.tvremote.core.TransportError
import com.sizwe.tvremote.core.TransportException
import com.sizwe.tvremote.core.TransportType
import com.sizwe.tvremote.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * The single object the UI drives. Owns every transport, decides which one is live, and hides the
 * switch from the rest of the app: [sendKey] behaves the same whether the bytes leave over Wi-Fi
 * or Bluetooth.
 *
 * Auto-fallback is the point of this class. If the active transport fails a command - Wi-Fi
 * dropped, the TV went to sleep, Bluetooth link died - and the other one is usable, the press is
 * retried there and the user is told once, rather than the UI freezing on a dead transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportManager(
    private val fake: FakeTransport,
    private val adb: AdbTransport,
    private val bluetooth: BluetoothHidTransport,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {

    private val _activeType = MutableStateFlow(TransportType.FAKE)
    val activeType: StateFlow<TransportType> = _activeType.asStateFlow()

    /** One-shot notices for the UI: fallbacks, unsupported buttons, dropped links. */
    private val _notices = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notices = _notices.asSharedFlow()

    val active: RemoteTransport get() = transportFor(_activeType.value)

    /** Connection state of whichever transport is currently active. */
    val state: StateFlow<ConnectionState> = _activeType
        .flatMapLatest { transportFor(it).state }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Idle)

    fun transportFor(type: TransportType): RemoteTransport = when (type) {
        TransportType.FAKE -> fake
        TransportType.ADB -> adb
        TransportType.BLUETOOTH_HID -> bluetooth
    }

    fun stateOf(type: TransportType): StateFlow<ConnectionState> = transportFor(type).state

    // --- selection ---

    suspend fun select(type: TransportType) {
        if (_activeType.value == type) return
        _activeType.value = type
        settings.setPreferredTransport(type)
        Log.i(TAG, "Active transport is now $type")
    }

    /**
     * Restores the last session: preferred transport, remembered address, connect if allowed.
     * Called once at startup; failures are surfaced through [state], not thrown.
     */
    suspend fun restoreSession() {
        val saved = settings.settings.first()
        _activeType.value = saved.preferredTransport

        if (!saved.autoConnectOnLaunch) return

        when (saved.preferredTransport) {
            TransportType.ADB -> {
                val host = saved.lastHost ?: return
                adb.connect(RemoteTarget.Network(host, saved.lastPort, saved.lastDeviceLabel ?: host))
            }

            TransportType.BLUETOOTH_HID -> {
                val mac = saved.bluetoothMac ?: return
                bluetooth.connect(RemoteTarget.BluetoothDevice(mac))
            }

            TransportType.FAKE -> fake.connect(RemoteTarget.Network("demo", 0, "Demo device"))
        }
    }

    suspend fun connect(type: TransportType, target: RemoteTarget): Result<Unit> {
        _activeType.value = type
        settings.setPreferredTransport(type)

        val result = transportFor(type).connect(target)
        if (result.isSuccess) {
            when (target) {
                is RemoteTarget.Network ->
                    settings.setLastDevice(target.host, target.port, target.label)

                is RemoteTarget.BluetoothDevice -> settings.setBluetoothMac(target.macAddress)
            }
        }
        return result
    }

    suspend fun disconnect() = active.disconnect()

    suspend fun reconnect(): Result<Unit> {
        val current = active
        val currentTarget = current.target
            ?: return Result.failure(
                TransportException(TransportError.NotConnected("No TV selected yet.")),
            )
        current.disconnect()
        return current.connect(currentTarget)
    }

    // --- commands ---

    suspend fun sendKey(key: RemoteKey): Result<Unit> = withFallback("key ${key.name}") {
        it.sendKey(key)
    }

    suspend fun sendKeyDown(key: RemoteKey): Result<Unit> = withFallback("hold ${key.name}") {
        it.sendKeyDown(key)
    }

    suspend fun sendKeyUp(key: RemoteKey): Result<Unit> = withFallback("release ${key.name}") {
        it.sendKeyUp(key)
    }

    suspend fun sendText(text: String): Result<Unit> {
        val transport = active
        if (!transport.supports(TransportCapability.TEXT_INPUT)) {
            _notices.tryEmit("${transport.type.label} cannot type text. Switch to Wi-Fi for the keyboard.")
            return Result.failure(
                TransportException(TransportError.Protocol("Text input needs the Wi-Fi transport")),
            )
        }
        return transport.sendText(text)
    }

    suspend fun launchApp(packageName: String, activity: String? = null): Result<Unit> {
        val transport = active
        if (!transport.supports(TransportCapability.APP_LAUNCH)) {
            _notices.tryEmit("Shortcuts need the Wi-Fi transport; Bluetooth can only send buttons.")
            return Result.failure(
                TransportException(TransportError.Protocol("App launch needs the Wi-Fi transport")),
            )
        }
        return transport.launchApp(packageName, activity)
    }

    /**
     * Runs [block] on the active transport, and on failure retries once on the other one if the
     * user has fallback enabled and that transport is currently usable.
     */
    private suspend fun withFallback(
        description: String,
        block: suspend (RemoteTransport) -> Result<Unit>,
    ): Result<Unit> {
        val primary = active
        val result = block(primary)
        if (result.isSuccess) return result

        val allowFallback = settings.settings.first().autoFallbackTransport
        if (!allowFallback) return result

        val alternate = alternateFor(primary.type) ?: return result
        if (!alternate.state.value.isUsable) return result

        Log.w(TAG, "Falling back from ${primary.type} to ${alternate.type} for $description")
        val fallbackResult = block(alternate)
        if (fallbackResult.isSuccess) {
            _activeType.value = alternate.type
            _notices.tryEmit("${primary.type.label} dropped - switched to ${alternate.type.label}.")
        }
        return fallbackResult
    }

    private fun alternateFor(type: TransportType): RemoteTransport? = when (type) {
        TransportType.ADB -> bluetooth
        TransportType.BLUETOOTH_HID -> adb
        TransportType.FAKE -> null
    }

    fun release() {
        adb.release()
        bluetooth.release()
    }

    private companion object {
        const val TAG = "TransportManager"
    }
}
