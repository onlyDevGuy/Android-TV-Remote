package com.sizwe.tvremote.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.sizwe.tvremote.core.ConnectionState
import com.sizwe.tvremote.core.RemoteKey
import com.sizwe.tvremote.core.RemoteTarget
import com.sizwe.tvremote.core.RemoteTransport
import com.sizwe.tvremote.core.TransportCapability
import com.sizwe.tvremote.core.TransportError
import com.sizwe.tvremote.core.TransportException
import com.sizwe.tvremote.core.TransportType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Presents the phone to the TV as a Bluetooth HID keyboard, then sends button presses as HID
 * reports.
 *
 * The flow the user actually goes through:
 *
 *  1. [register] hands our report descriptor to the Bluetooth stack. Until this succeeds the phone
 *     is invisible as an input device - it will not appear in the TV's Bluetooth menu at all.
 *  2. The TV scans and pairs with the phone (or the user taps a bonded device here and we call
 *     [connect], which drives it from our side).
 *  3. [sendKey] pushes a press report followed by a release report on the right report ID.
 *
 * Notes from testing this against real hardware, since none of it emulates:
 *
 *  - `registerApp` is asynchronous and its failure mode is silence. Success only shows up as
 *    `onAppStatusChanged(registered = true)`, so [register] waits for that callback rather than
 *    trusting the boolean return value.
 *  - Only one HID app may be registered per process; re-registering without [unregister] first
 *    fails, which is why [release] is wired to the app lifecycle.
 *  - Some OEM stacks will not accept an incoming HID connection while the screen is off, and some
 *    drop the link on the first idle timeout. [ConnectionState] surfaces the disconnect rather
 *    than leaving the UI looking live.
 */
class BluetoothHidTransport(
    context: Context,
    private val config: Config = Config(),
) : RemoteTransport {

    data class Config(
        /** Gap between the press report and its release. Too short and some TVs miss the press. */
        val pressReleaseGapMs: Long = 30,
        val registrationTimeoutMs: Long = 8_000,
        val connectTimeoutMs: Long = 15_000,
    )

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val executor = Executors.newSingleThreadExecutor()
    private val sendMutex = Mutex()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    override val type = TransportType.BLUETOOTH_HID

    override val capabilities = setOf(
        TransportCapability.KEYS,
        TransportCapability.WORKS_WHEN_TV_ASLEEP,
    )

    override var target: RemoteTarget? = null
        private set

    @Volatile
    private var hidDevice: BluetoothHidDevice? = null

    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    private var registrationSignal: CompletableDeferred<Boolean>? = null
    private var connectionSignal: CompletableDeferred<Boolean>? = null

    val isRegistered: Boolean get() = hidDevice != null && appRegistered

    @Volatile
    private var appRegistered = false

    // --- availability ---

    fun bluetoothAvailable(): Boolean = adapter != null

    fun bluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Devices already paired with the phone, which is where the TV shows up post-pairing. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<PairedDevice> {
        if (!hasConnectPermission()) return emptyList()
        return adapter?.bondedDevices.orEmpty().map {
            PairedDevice(name = it.name ?: it.address, address = it.address)
        }
    }

    data class PairedDevice(val name: String, val address: String)

    // --- registration (Phase 4) ---

    /**
     * Registers the HID app so the phone advertises itself as a keyboard. Must succeed before the
     * TV can see us; safe to call repeatedly.
     */
    suspend fun register(): Result<Unit> {
        val bluetoothAdapter = adapter
            ?: return fail(TransportError.BluetoothUnavailable("This phone has no Bluetooth adapter."))
        if (!bluetoothAdapter.isEnabled) {
            return fail(TransportError.BluetoothUnavailable("Turn Bluetooth on to use this transport."))
        }
        if (!hasConnectPermission()) {
            return fail(TransportError.PermissionMissing(Manifest.permission.BLUETOOTH_CONNECT))
        }
        if (isRegistered) return Result.success(Unit)

        _state.value = ConnectionState.Connecting("Registering as a Bluetooth remote")

        val proxy = hidDevice ?: obtainProxy(bluetoothAdapter)
            ?: return fail(
                TransportError.BluetoothUnavailable("The HID Device profile is unavailable on this phone."),
            )

        val signal = CompletableDeferred<Boolean>()
        registrationSignal = signal

        val accepted = registerApp(proxy)
        if (!accepted) {
            return fail(TransportError.BluetoothUnavailable("The Bluetooth stack refused the HID registration."))
        }

        // registerApp() returning true only means the request was queued.
        val registered = withTimeoutOrNull(config.registrationTimeoutMs) { signal.await() } ?: false
        if (!registered) {
            return fail(
                TransportError.BluetoothUnavailable(
                    "Registration timed out. Toggle Bluetooth off and on, then try again.",
                ),
            )
        }

        _state.value = ConnectionState.AwaitingAuthorization(
            "On the TV, open Settings > Remotes & accessories > Add accessory and pick this phone.",
        )
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    private fun registerApp(proxy: BluetoothHidDevice): Boolean = runCatching {
        proxy.registerApp(
            HidDescriptors.SDP_SETTINGS,
            /* inQos = */ null,
            HidDescriptors.QOS_SETTINGS,
            executor,
            callback,
        )
    }.getOrElse {
        Log.e(TAG, "registerApp threw", it)
        false
    }

    @SuppressLint("MissingPermission")
    fun unregister() {
        runCatching { hidDevice?.unregisterApp() }
        appRegistered = false
    }

    private suspend fun obtainProxy(bluetoothAdapter: BluetoothAdapter): BluetoothHidDevice? {
        val deferred = CompletableDeferred<BluetoothHidDevice?>()
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    deferred.complete(proxy)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                    appRegistered = false
                    _state.value = ConnectionState.Disconnected("Bluetooth HID service disconnected")
                }
            }
        }

        val requested = runCatching {
            bluetoothAdapter.getProfileProxy(appContext, listener, BluetoothProfile.HID_DEVICE)
        }.getOrDefault(false)
        if (!requested) return null

        return withTimeoutOrNull(config.registrationTimeoutMs) { deferred.await() }
    }

    // --- connection (Phase 5) ---

    @SuppressLint("MissingPermission")
    override suspend fun connect(target: RemoteTarget): Result<Unit> {
        val btTarget = target as? RemoteTarget.BluetoothDevice
            ?: return Result.failure(
                TransportException(TransportError.Protocol("Bluetooth needs a MAC target, got $target")),
            )
        this.target = btTarget

        register().onFailure { return Result.failure(it) }

        val proxy = hidDevice ?: return fail(TransportError.BluetoothUnavailable("HID profile went away"))
        val device = runCatching { adapter?.getRemoteDevice(btTarget.macAddress) }.getOrNull()
            ?: return fail(TransportError.Protocol("Unknown Bluetooth address ${btTarget.macAddress}"))

        _state.value = ConnectionState.Connecting("Connecting to ${btTarget.label}")

        val signal = CompletableDeferred<Boolean>()
        connectionSignal = signal

        if (!proxy.connect(device)) {
            return fail(
                TransportError.BluetoothUnavailable(
                    "The TV did not accept the connection. Pair from the TV's Bluetooth menu first.",
                ),
            )
        }

        val connected = withTimeoutOrNull(config.connectTimeoutMs) { signal.await() } ?: false
        return if (connected) {
            Result.success(Unit)
        } else {
            fail(TransportError.BluetoothUnavailable("Timed out waiting for the TV to accept the link."))
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        val proxy = hidDevice
        val device = connectedDevice
        if (proxy != null && device != null) runCatching { proxy.disconnect(device) }
        connectedDevice = null
        _state.value = ConnectionState.Disconnected("Disconnected")
    }

    // --- sending (Phase 5) ---

    override suspend fun sendKey(key: RemoteKey): Result<Unit> {
        val usage = HidKeyMap.usage(key)
        if (usage is HidUsage.Unsupported) {
            return Result.failure(
                TransportException(TransportError.Protocol("$key has no Bluetooth equivalent")),
            )
        }
        return sendMutex.withLock {
            sendReport(HidReport.press(usage)).onFailure { return@withLock Result.failure(it) }
            delay(config.pressReleaseGapMs)
            sendReport(HidReport.release(usage))
        }
    }

    /** Holds the key down; pair with [sendKeyUp]. Used by the D-pad's press-and-hold repeat. */
    override suspend fun sendKeyDown(key: RemoteKey): Result<Unit> {
        val usage = HidKeyMap.usage(key)
        if (usage is HidUsage.Unsupported) {
            return Result.failure(
                TransportException(TransportError.Protocol("$key has no Bluetooth equivalent")),
            )
        }
        return sendMutex.withLock { sendReport(HidReport.press(usage)) }
    }

    override suspend fun sendKeyUp(key: RemoteKey): Result<Unit> {
        val usage = HidKeyMap.usage(key)
        if (usage is HidUsage.Unsupported) return Result.success(Unit)
        return sendMutex.withLock { sendReport(HidReport.release(usage)) }
    }

    @SuppressLint("MissingPermission")
    private fun sendReport(report: Pair<Byte, ByteArray>?): Result<Unit> {
        if (report == null) return Result.success(Unit)
        val proxy = hidDevice
        val device = connectedDevice
        if (proxy == null || device == null) {
            return Result.failure(TransportException(TransportError.NotConnected("No TV is linked over Bluetooth.")))
        }
        val (reportId, data) = report
        val sent = runCatching { proxy.sendReport(device, reportId.toInt(), data) }.getOrElse {
            Log.e(TAG, "sendReport threw", it)
            false
        }
        return if (sent) {
            Result.success(Unit)
        } else {
            Result.failure(
                TransportException(
                    TransportError.BluetoothUnavailable("The TV rejected the report; the link may have dropped."),
                ),
            )
        }
    }

    override fun release() {
        unregister()
        hidDevice?.let { proxy ->
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
        }
        hidDevice = null
        executor.shutdown()
    }

    // --- callbacks ---

    private val callback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "onAppStatusChanged(registered=$registered, plugged=${pluggedDevice?.address})")
            appRegistered = registered
            registrationSignal?.complete(registered)
            if (!registered) {
                connectedDevice = null
                _state.value = ConnectionState.Disconnected("Bluetooth remote unregistered")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Log.i(TAG, "onConnectionStateChanged(${device?.address}, ${stateName(state)})")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    connectionSignal?.complete(true)
                    _state.value = ConnectionState.Connected(
                        deviceLabel = runCatching { device?.name }.getOrNull() ?: "TV",
                        address = device?.address.orEmpty(),
                    )
                }

                BluetoothProfile.STATE_CONNECTING ->
                    _state.value = ConnectionState.Connecting("Linking with ${device?.address}")

                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    connectionSignal?.complete(false)
                    _state.value = ConnectionState.Disconnected("The TV dropped the Bluetooth link")
                }

                BluetoothProfile.STATE_DISCONNECTING ->
                    _state.value = ConnectionState.Disconnected("Disconnecting")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            // Hosts poll for the current state on connect. Answer with an all-released report so
            // the TV does not assume a key is stuck down.
            val proxy = hidDevice ?: return
            val empty = when (id) {
                HidDescriptors.REPORT_ID_CONSUMER -> HidReport.consumerRelease()
                else -> HidReport.keyboardRelease()
            }
            runCatching { proxy.replyReport(device, type, id, empty) }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            // Output reports (keyboard LEDs) are meaningless here; acknowledge and move on.
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) = Unit

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            Log.i(TAG, "Virtual cable unplugged by ${device?.address}")
            connectedDevice = null
            _state.value = ConnectionState.Disconnected("The TV removed this remote")
        }
    }

    private fun fail(error: TransportError): Result<Unit> {
        _state.value = ConnectionState.Failed(error)
        return Result.failure(TransportException(error))
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "state=$state"
    }

    private companion object {
        const val TAG = "BluetoothHidTransport"
    }
}
