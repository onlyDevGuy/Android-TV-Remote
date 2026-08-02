package com.sizwe.tvremote.core

import kotlinx.coroutines.flow.StateFlow

enum class TransportType(val label: String) {
    FAKE("Demo"),
    ADB("Wi-Fi (ADB)"),
    BLUETOOTH_HID("Bluetooth"),
}

enum class TransportCapability {
    /** Can press buttons. Every transport has this. */
    KEYS,

    /** Can type a string into a focused field. */
    TEXT_INPUT,

    /** Can start an arbitrary app by package name (`am start`). ADB only. */
    APP_LAUNCH,

    /** Can enumerate the packages installed on the TV. ADB only. */
    PACKAGE_QUERY,

    /** Works while the TV's own network stack is asleep. Bluetooth only. */
    WORKS_WHEN_TV_ASLEEP,
}

/**
 * Where a transport should connect to. Each transport only understands its own subtype; handing
 * the wrong one to [RemoteTransport.connect] is a programming error and throws.
 */
sealed interface RemoteTarget {
    val label: String

    data class Network(
        val host: String,
        val port: Int = 5555,
        override val label: String = host,
    ) : RemoteTarget {
        val address: String get() = "$host:$port"
    }

    data class BluetoothDevice(
        val macAddress: String,
        override val label: String = macAddress,
    ) : RemoteTarget
}

/**
 * The single seam the UI talks to. Phase 0 ships [com.sizwe.tvremote.transport.FakeTransport];
 * Phase 1-3 the ADB implementation; Phase 4-5 the Bluetooth HID one. The screen never changes.
 */
interface RemoteTransport {

    val type: TransportType

    val capabilities: Set<TransportCapability>

    val state: StateFlow<ConnectionState>

    /** Currently attached target, if any. Survives a transient disconnect so retry knows where to go. */
    val target: RemoteTarget?

    /**
     * Connects and completes whatever handshake the transport needs. Suspends until the transport
     * is either usable or has definitively failed; progress is reported through [state].
     */
    suspend fun connect(target: RemoteTarget): Result<Unit>

    suspend fun disconnect()

    /** A full press: down then up. */
    suspend fun sendKey(key: RemoteKey): Result<Unit>

    /** Held-button support. Transports that cannot express hold fall back to repeated presses. */
    suspend fun sendKeyDown(key: RemoteKey): Result<Unit> = sendKey(key)

    suspend fun sendKeyUp(key: RemoteKey): Result<Unit> = Result.success(Unit)

    suspend fun sendText(text: String): Result<Unit> =
        Result.failure(TransportException(TransportError.Protocol("$type cannot send text")))

    suspend fun launchApp(packageName: String, activity: String? = null): Result<Unit> =
        Result.failure(TransportException(TransportError.Protocol("$type cannot launch apps")))

    /** Package names installed on the target, for shortcut discovery. Empty when unsupported. */
    suspend fun listPackages(): Result<List<String>> = Result.success(emptyList())

    fun supports(capability: TransportCapability): Boolean = capability in capabilities

    /** Releases long-lived resources (sockets, BT proxies). The transport is unusable afterwards. */
    fun release() = Unit
}
