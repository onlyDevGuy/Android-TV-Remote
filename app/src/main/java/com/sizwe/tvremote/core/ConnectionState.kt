package com.sizwe.tvremote.core

/**
 * Lifecycle of a single transport. The UI renders this directly, so every state carries
 * enough text to explain itself without the screen having to know transport internals.
 */
sealed interface ConnectionState {

    data object Idle : ConnectionState

    data class Connecting(val detail: String) : ConnectionState

    /**
     * ADB: the TV is showing the "Allow USB debugging?" dialog and we are waiting on the user.
     * Bluetooth: the TV is showing the pairing prompt.
     */
    data class AwaitingAuthorization(val detail: String) : ConnectionState

    data class Connected(
        val deviceLabel: String,
        val address: String,
    ) : ConnectionState

    /** Transport dropped but we intend to come back. [attempt] is 1-based. */
    data class Reconnecting(
        val attempt: Int,
        val nextRetryInMs: Long,
        val cause: String,
    ) : ConnectionState

    data class Disconnected(val reason: String? = null) : ConnectionState

    data class Failed(
        val error: TransportError,
    ) : ConnectionState

    val isUsable: Boolean get() = this is Connected
}

/**
 * Errors are modelled rather than stringly-typed because the UI has to make decisions on them:
 * [AuthorizationRejected] gets a "check the TV screen" hint, [NetworkDebuggingOff] links to the
 * setup instructions, [Unreachable] offers a rescan, and so on.
 */
sealed class TransportError(
    open val message: String,
    open val cause: Throwable? = null,
) {
    /** Nothing is listening on the port. Usually: network debugging is off, or the TV is asleep. */
    data class Unreachable(
        val address: String,
        override val cause: Throwable? = null,
    ) : TransportError("Could not reach $address. Is the TV awake and on this network?", cause)

    /** Port refused the connection — the ADB daemon is not listening. */
    data class NetworkDebuggingOff(
        val address: String,
    ) : TransportError(
        "$address refused the connection. Turn on Developer options → " +
            "Network/Wireless debugging on the TV, then try again.",
    )

    /** The user hit "Deny" (or never answered) the debugging prompt on the TV. */
    data object AuthorizationRejected : TransportError(
        "The TV declined the debugging request. Accept the prompt on the TV " +
            "and tick \"Always allow from this computer\".",
    )

    /**
     * The daemon insisted on ADB-over-TLS (Android 11+ wireless debugging). That path needs the
     * pairing-code flow, which this app does not implement — see README, "Android 11+ TLS".
     */
    data class TlsRequired(val address: String) : TransportError(
        "$address requires ADB over TLS. Use the legacy \"Network debugging\" toggle " +
            "on port 5555, or switch to the Bluetooth transport.",
    )

    data class Protocol(
        override val message: String,
        override val cause: Throwable? = null,
    ) : TransportError(message, cause)

    data class BluetoothUnavailable(
        override val message: String,
    ) : TransportError(message)

    data class PermissionMissing(
        val permission: String,
    ) : TransportError("Missing permission: $permission")

    data class NotConnected(
        override val message: String = "Not connected.",
    ) : TransportError(message)

    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null,
    ) : TransportError(message, cause)
}

class TransportException(val error: TransportError) : Exception(error.message, error.cause)
