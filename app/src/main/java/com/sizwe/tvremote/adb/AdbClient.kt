package com.sizwe.tvremote.adb

import android.util.Log
import com.sizwe.tvremote.core.ConnectionState
import com.sizwe.tvremote.core.TransportError
import com.sizwe.tvremote.core.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns one ADB session and keeps it alive.
 *
 * Three things beyond a plain socket, all of which came out of the Phase 1 field notes:
 *
 *  - **Key persistence.** The identity comes from [AdbKeyStore], so the TV's "Always allow"
 *    actually sticks between launches.
 *  - **Reconnection.** A TV that sleeps, or that gets a new DHCP lease, drops the socket without
 *    telling us. We detect it (read loop EOF or a failed write), then retry with backoff instead
 *    of leaving the UI stuck on a dead connection.
 *  - **Health checks.** Some boxes keep the TCP session half-open after the daemon dies, so a
 *    periodic cheap shell command is the only reliable liveness signal.
 */
class AdbClient(
    private val keyStore: AdbKeyStore,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
) {

    data class Config(
        val connectTimeoutMs: Int = 4_000,
        val authTimeoutMs: Int = 60_000,
        val keepAliveIntervalMs: Long = 30_000,
        val maxReconnectAttempts: Int = 6,
        val backoffStartMs: Long = 1_000,
        val backoffMaxMs: Long = 15_000,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val connectionMutex = Mutex()

    @Volatile
    private var connection: AdbConnection? = null

    @Volatile
    private var host: String? = null

    @Volatile
    private var port: Int = DEFAULT_PORT

    /** Set while the user has explicitly disconnected, to stop the reconnect loop from fighting them. */
    @Volatile
    private var userDisconnected = false

    private var keepAliveJob: Job? = null
    private var reconnectJob: Job? = null

    val isConnected: Boolean get() = connection?.isClosed == false

    val deviceBanner: String? get() = connection?.deviceBanner

    suspend fun connect(host: String, port: Int = DEFAULT_PORT): Result<Unit> {
        this.host = host
        this.port = port
        userDisconnected = false
        reconnectJob?.cancel()
        return doConnect(announce = true)
    }

    private suspend fun doConnect(announce: Boolean): Result<Unit> = connectionMutex.withLock {
        val targetHost = host ?: return Result.failure(
            TransportException(TransportError.NotConnected("No TV address configured")),
        )
        val address = "$targetHost:$port"

        connection?.let { runCatching { it.close() } }
        connection = null

        if (announce) _state.value = ConnectionState.Connecting("Connecting to $address")

        return try {
            val keyPair = keyStore.keyPair()
            val established = AdbConnection.connect(
                host = targetHost,
                port = port,
                keyPair = keyPair,
                connectTimeoutMs = config.connectTimeoutMs,
                handshakeTimeoutMs = config.authTimeoutMs,
                onAwaitingAuthorization = {
                    _state.value = ConnectionState.AwaitingAuthorization(
                        "Check the TV: accept \"Allow debugging\" and tick \"Always allow\".",
                    )
                },
            )
            established.onClosed = { reason -> onConnectionLost(reason) }
            connection = established

            _state.value = ConnectionState.Connected(
                deviceLabel = parseDeviceLabel(established.deviceBanner) ?: targetHost,
                address = address,
            )
            startKeepAlive()
            Log.i(TAG, "Connected to $address (${established.deviceBanner})")
            Result.success(Unit)
        } catch (e: TransportException) {
            _state.value = ConnectionState.Failed(e.error)
            Result.failure(e)
        } catch (e: Throwable) {
            val error = TransportError.Unknown(e.message ?: "ADB connection failed", e)
            _state.value = ConnectionState.Failed(error)
            Result.failure(TransportException(error))
        }
    }

    suspend fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        connectionMutex.withLock {
            connection?.let { runCatching { it.close() } }
            connection = null
        }
        _state.value = ConnectionState.Disconnected("Disconnected")
    }

    /**
     * Runs a shell command, reconnecting once if the session died since the last call.
     *
     * @param waitForOutput false for fire-and-forget commands such as key events, which saves a
     *   round trip per button press and keeps the D-pad feeling immediate on a busy network.
     */
    suspend fun shell(command: String, waitForOutput: Boolean = true): Result<String> {
        repeat(2) { attempt ->
            val live = connection?.takeIf { !it.isClosed }
                ?: run {
                    if (userDisconnected || host == null) {
                        return Result.failure(
                            TransportException(TransportError.NotConnected()),
                        )
                    }
                    doConnect(announce = attempt == 0).getOrElse { return Result.failure(it) }
                    connection
                }
                ?: return Result.failure(TransportException(TransportError.NotConnected()))

            try {
                return if (waitForOutput) {
                    Result.success(live.shell(command))
                } else {
                    live.shellNoWait(command)
                    Result.success("")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Command failed (attempt ${attempt + 1}): $command", e)
                runCatching { live.close() }
                connection = null
                if (attempt == 1) {
                    val error = (e as? TransportException)?.error
                        ?: TransportError.Unknown(e.message ?: "Command failed", e)
                    _state.value = ConnectionState.Failed(error)
                    return Result.failure(TransportException(error))
                }
            }
        }
        return Result.failure(TransportException(TransportError.NotConnected()))
    }

    /** Drops the stored identity; the next connect re-triggers the on-TV authorisation prompt. */
    suspend fun resetAuthorization() {
        keyStore.reset()
        disconnect()
    }

    fun release() {
        keepAliveJob?.cancel()
        reconnectJob?.cancel()
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    // --- liveness ---

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(config.keepAliveIntervalMs)
                val live = connection ?: continue
                if (live.isClosed) continue
                // `true` is the cheapest possible shell command; we only care that it round-trips.
                val ok = runCatching { live.shell("true", timeoutMs = 4_000) }.isSuccess
                if (!ok) {
                    Log.w(TAG, "Keep-alive failed; treating the session as dead")
                    runCatching { live.close() }
                }
            }
        }
    }

    private fun onConnectionLost(reason: String) {
        if (userDisconnected) return
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            var attempt = 1
            var backoff = config.backoffStartMs
            while (isActive && attempt <= config.maxReconnectAttempts && !userDisconnected) {
                _state.value = ConnectionState.Reconnecting(attempt, backoff, reason)
                delay(backoff)
                val result = doConnect(announce = false)
                if (result.isSuccess) return@launch
                backoff = (backoff * 2).coerceAtMost(config.backoffMaxMs)
                attempt++
            }
            if (!userDisconnected) {
                _state.value = ConnectionState.Failed(
                    TransportError.Unreachable(
                        address = "${host.orEmpty()}:$port",
                    ),
                )
            }
        }
    }

    /** Pulls `ro.product.model` out of adbd's banner for a friendlier label than a bare IP. */
    private fun parseDeviceLabel(banner: String): String? {
        val model = banner.split(";")
            .firstOrNull { it.trim().startsWith("ro.product.model=") }
            ?.substringAfter("=")
            ?.trim()
        return model?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val DEFAULT_PORT = 5555
        private const val TAG = "AdbClient"
    }
}
