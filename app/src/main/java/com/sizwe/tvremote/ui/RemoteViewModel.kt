package com.sizwe.tvremote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sizwe.tvremote.AppContainer
import com.sizwe.tvremote.core.ConnectionState
import com.sizwe.tvremote.core.RemoteKey
import com.sizwe.tvremote.core.RemoteTarget
import com.sizwe.tvremote.core.TransportCapability
import com.sizwe.tvremote.core.TransportType
import com.sizwe.tvremote.data.SettingsRepository
import com.sizwe.tvremote.diagnostics.DiagnosticsLog
import com.sizwe.tvremote.diagnostics.DiagnosticsSnapshot
import com.sizwe.tvremote.diagnostics.LatencyProbe
import com.sizwe.tvremote.diagnostics.LogEntry
import com.sizwe.tvremote.discovery.DiscoveredDevice
import com.sizwe.tvremote.discovery.DiscoverySource
import com.sizwe.tvremote.shortcuts.AppShortcut
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Single view model for the whole app - the surface is small enough that splitting it per screen
 * would only add plumbing between the connection flow and the remote itself.
 */
class RemoteViewModel(
    private val container: AppContainer,
) : ViewModel() {

    data class UiState(
        val connection: ConnectionState = ConnectionState.Idle,
        val activeTransport: TransportType = TransportType.FAKE,
        val settings: SettingsRepository.Settings = SettingsRepository.Settings(),
        val shortcuts: List<AppShortcut> = emptyList(),
        val discovered: List<DiscoveredDevice> = emptyList(),
        val isScanning: Boolean = false,
        val scanFinishedEmpty: Boolean = false,
        val pairedBluetoothDevices: List<BluetoothHidTransportDevice> = emptyList(),
        val notice: String? = null,
    ) {
        val canLaunchApps: Boolean
            get() = activeTransport != TransportType.BLUETOOTH_HID

        val statusLine: String
            get() = when (val c = connection) {
                is ConnectionState.Connected -> "${c.deviceLabel} - ${c.address}"
                is ConnectionState.Connecting -> c.detail
                is ConnectionState.AwaitingAuthorization -> c.detail
                is ConnectionState.Reconnecting ->
                    "Reconnecting (attempt ${c.attempt})..."
                is ConnectionState.Disconnected -> c.reason ?: "Disconnected"
                is ConnectionState.Failed -> c.error.message
                ConnectionState.Idle -> "Not connected"
            }
    }

    /** Mirrors [com.sizwe.tvremote.bluetooth.BluetoothHidTransport.PairedDevice] for the UI layer. */
    data class BluetoothHidTransportDevice(val name: String, val address: String)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var repeatJob: Job? = null

    init {
        combine(
            container.transports.state,
            container.transports.activeType,
            container.settings.settings,
        ) { connection, activeType, settings ->
            Triple(connection, activeType, settings)
        }.onEach { (connection, activeType, settings) ->
            _uiState.value = _uiState.value.copy(
                connection = connection,
                activeTransport = activeType,
                settings = settings,
            )
            if (connection is ConnectionState.Connected) onConnected()
        }.launchIn(viewModelScope)

        container.transports.notices
            .onEach { message -> _uiState.value = _uiState.value.copy(notice = message) }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(shortcuts = container.shortcuts.cached())
            container.transports.restoreSession()
        }
    }

    // --- buttons ---

    fun press(key: RemoteKey) {
        viewModelScope.launch {
            container.transports.sendKey(key).onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    notice = error.message ?: "That button did not go through.",
                )
            }
        }
    }

    /**
     * Press-and-hold. Bluetooth can genuinely hold a key down, so it gets a real down/up pair;
     * ADB has no such concept, so the repeat is synthesised by resending the press.
     */
    fun startRepeat(key: RemoteKey) {
        if (!key.isRepeatable) {
            press(key)
            return
        }
        repeatJob?.cancel()
        repeatJob = viewModelScope.launch {
            container.transports.sendKey(key)
            kotlinx.coroutines.delay(INITIAL_REPEAT_DELAY_MS)
            while (true) {
                container.transports.sendKey(key)
                kotlinx.coroutines.delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    fun stopRepeat(key: RemoteKey) {
        repeatJob?.cancel()
        repeatJob = null
        viewModelScope.launch { container.transports.sendKeyUp(key) }
    }

    fun sendText(text: String) {
        viewModelScope.launch { container.transports.sendText(text) }
    }

    fun launchShortcut(shortcut: AppShortcut) {
        viewModelScope.launch {
            container.transports.launchApp(shortcut.packageName, shortcut.activity)
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        notice = "${shortcut.label} would not start. Check the package name in settings.",
                    )
                }
        }
    }

    // --- connection ---

    fun startScan() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isScanning = true,
            scanFinishedEmpty = false,
            discovered = emptyList(),
        )
        scanJob = viewModelScope.launch {
            val found = mutableListOf<DiscoveredDevice>()
            container.discovery.scan()
                .onEach { device ->
                    found += device
                    _uiState.value = _uiState.value.copy(discovered = found.toList())
                }
                .launchIn(this)
                .join()

            _uiState.value = _uiState.value.copy(
                isScanning = false,
                // Drives the "device not found" fallback that offers manual IP entry.
                scanFinishedEmpty = found.isEmpty(),
            )
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(isScanning = false)
    }

    fun connectTo(device: DiscoveredDevice) {
        connectToAddress(device.host, device.port, device.name)
    }

    fun connectToAddress(host: String, port: Int, label: String? = null) {
        viewModelScope.launch {
            container.transports.connect(
                TransportType.ADB,
                RemoteTarget.Network(host.trim(), port, label ?: host.trim()),
            )
        }
    }

    fun connectManual(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(notice = "Enter the TV's IP address first.")
            return
        }
        val host = trimmed.substringBefore(':')
        val port = trimmed.substringAfter(':', "").toIntOrNull() ?: DEFAULT_ADB_PORT
        connectToAddress(host, port, null)
    }

    fun disconnect() {
        viewModelScope.launch { container.transports.disconnect() }
    }

    fun reconnect() {
        viewModelScope.launch { container.transports.reconnect() }
    }

    fun selectTransport(type: TransportType) {
        viewModelScope.launch { container.transports.select(type) }
    }

    /** Clears the stored ADB key so the TV asks for authorisation again on the next connect. */
    fun forgetAuthorization() {
        viewModelScope.launch {
            container.adbTransport.resetAuthorization()
            _uiState.value = _uiState.value.copy(
                notice = "Authorisation cleared. Reconnect and accept the prompt on the TV.",
            )
        }
    }

    // --- bluetooth ---

    fun registerBluetooth() {
        viewModelScope.launch {
            container.bluetoothTransport.register().onFailure {
                _uiState.value = _uiState.value.copy(notice = it.message)
            }
            refreshPairedDevices()
        }
    }

    fun refreshPairedDevices() {
        _uiState.value = _uiState.value.copy(
            pairedBluetoothDevices = container.bluetoothTransport.bondedDevices().map {
                BluetoothHidTransportDevice(it.name, it.address)
            },
        )
    }

    fun connectBluetooth(address: String, label: String? = null) {
        viewModelScope.launch {
            container.transports.connect(
                TransportType.BLUETOOTH_HID,
                RemoteTarget.BluetoothDevice(address, label ?: address),
            )
        }
    }

    // --- settings ---

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoConnect(enabled) }
    }

    fun setAutoFallback(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoFallback(enabled) }
    }

    fun setHaptics(enabled: Boolean) {
        viewModelScope.launch { container.settings.setHaptics(enabled) }
    }

    fun refreshShortcuts() {
        viewModelScope.launch {
            val resolved = container.shortcuts.refresh(container.transports.active)
            _uiState.value = _uiState.value.copy(shortcuts = resolved)
        }
    }

    fun dismissNotice() {
        _uiState.value = _uiState.value.copy(notice = null)
    }

    // --- diagnostics ---

    /** Live event log, straight from the singleton the transports write to. */
    val diagnosticsLog: StateFlow<List<LogEntry>> = DiagnosticsLog.entries

    private val _diagnostics = MutableStateFlow(DiagnosticsUiState())
    val diagnostics: StateFlow<DiagnosticsUiState> = _diagnostics.asStateFlow()

    data class DiagnosticsUiState(
        val snapshot: DiagnosticsSnapshot? = null,
        val latency: LatencyProbe.Result? = null,
        val isMeasuringLatency: Boolean = false,
    )

    fun refreshDiagnostics() {
        viewModelScope.launch {
            _diagnostics.value = _diagnostics.value.copy(
                snapshot = container.diagnostics.snapshot(),
            )
        }
    }

    /** Latency is only meaningful over ADB, and only while the session is live. */
    val canMeasureLatency: Boolean
        get() = _uiState.value.activeTransport == TransportType.ADB &&
            _uiState.value.connection.isUsable

    fun runLatencyTest() {
        if (_diagnostics.value.isMeasuringLatency) return
        _diagnostics.value = _diagnostics.value.copy(isMeasuringLatency = true)
        viewModelScope.launch {
            val result = container.latencyProbe.run()
            _diagnostics.value = _diagnostics.value.copy(
                latency = result,
                isMeasuringLatency = false,
            )
        }
    }

    fun clearDiagnosticsLog() = DiagnosticsLog.clear()

    private fun onConnected() {
        viewModelScope.launch {
            if (container.transports.active.supports(TransportCapability.PACKAGE_QUERY)) {
                val resolved = container.shortcuts.refresh(container.transports.active)
                if (resolved.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(shortcuts = resolved)
                }
            }
        }
    }

    /** Manual entry always produces a [DiscoverySource.MANUAL] entry so the list explains itself. */
    fun manualEntry(host: String, port: Int) = DiscoveredDevice(
        host = host,
        port = port,
        source = DiscoverySource.MANUAL,
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RemoteViewModel(container) as T
    }

    private companion object {
        const val INITIAL_REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 120L
        const val DEFAULT_ADB_PORT = 5555
    }
}
