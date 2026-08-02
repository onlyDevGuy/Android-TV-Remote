package com.sizwe.tvremote.adb

import android.util.Log
import com.sizwe.tvremote.core.TransportError
import com.sizwe.tvremote.core.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A live ADB session: one TCP socket to `adbd`, the CNXN/AUTH handshake, and a read loop that
 * demultiplexes messages onto [AdbStream]s.
 *
 * Lifecycle is single-shot. Once [close] runs (or the socket dies) the object is spent and the
 * caller creates a new one; [AdbTransport] owns that retry policy.
 */
class AdbConnection private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    val host: String,
    val port: Int,
) {

    /** Banner adbd sent back, e.g. `device::ro.product.name=sdk;ro.product.model=...`. */
    var deviceBanner: String = ""
        private set

    /** Largest payload the daemon will accept from us. */
    private var maxPayload: Int = AdbProtocol.MAX_PAYLOAD

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextStreamId = AtomicInteger(1)
    private val writeMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readLoop: Job? = null

    @Volatile
    private var closedReason: String? = null

    val isClosed: Boolean get() = closedReason != null || socket.isClosed

    /** Opens a service (`shell:...`, `sync:`, ...) as a new stream. */
    suspend fun open(destination: String, timeoutMs: Long = OPEN_TIMEOUT_MS): AdbStream {
        check(!isClosed) { "Connection to $host:$port is closed (${closedReason ?: "socket closed"})" }

        val localId = nextStreamId.getAndIncrement()
        val stream = AdbStream(localId, this)
        streams[localId] = stream

        sendMessage(AdbProtocol.open(localId, destination))

        if (!stream.awaitOpen(timeoutMs)) {
            streams.remove(localId)
            throw TransportException(
                TransportError.Protocol("The TV refused to open \"$destination\""),
            )
        }
        return stream
    }

    /**
     * Runs a shell command and returns whatever it printed. Suitable for the short one-shot
     * commands this app uses (`input keyevent`, `am start`, `pm list packages`).
     */
    suspend fun shell(command: String, timeoutMs: Long = SHELL_TIMEOUT_MS): String {
        val stream = open("shell:$command", timeoutMs)
        return try {
            stream.readAll(timeoutMs)
        } finally {
            stream.close()
        }
    }

    /**
     * Fire-and-forget shell command: opens the stream, does not wait for output. Used for key
     * events, where waiting for the (empty) output would add a round trip to every button press.
     */
    suspend fun shellNoWait(command: String) {
        val localId = nextStreamId.getAndIncrement()
        val stream = AdbStream(localId, this)
        streams[localId] = stream
        sendMessage(AdbProtocol.open(localId, "shell:$command"))
        // The daemon's OKAY/CLSE arrive on the read loop and clean the stream up.
    }

    internal suspend fun sendMessage(message: AdbMessage) {
        if (message.payload.size > maxPayload) {
            throw AdbProtocolException("Payload ${message.payload.size} exceeds device max $maxPayload")
        }
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    output.writeAdbMessage(message)
                } catch (e: IOException) {
                    closeInternal("Write failed: ${e.message}")
                    throw TransportException(TransportError.Unreachable("$host:$port", e))
                }
            }
        }
    }

    fun close() = closeInternal("Closed by app")

    private fun closeInternal(reason: String) {
        if (closedReason != null) return
        closedReason = reason
        Log.i(TAG, "Closing connection to $host:$port - $reason")
        streams.values.forEach { it.onRemoteClose() }
        streams.clear()
        runCatching { socket.close() }
        readLoop?.cancel()
        scope.cancel()
        onClosed?.invoke(reason)
    }

    /** Set by [AdbTransport] so it can kick off reconnection when the socket dies underneath us. */
    var onClosed: ((String) -> Unit)? = null

    private fun startReadLoop() {
        readLoop = scope.launch {
            try {
                while (!isClosed) {
                    val message = input.readAdbMessage(maxPayload)
                    dispatch(message)
                }
            } catch (e: Throwable) {
                if (closedReason == null) {
                    Log.w(TAG, "Read loop ended: ${e.message}")
                    closeInternal(e.message ?: e::class.java.simpleName)
                }
            }
        }
    }

    private suspend fun dispatch(message: AdbMessage) {
        when (message.command) {
            AdbProtocol.A_OKAY -> streams[message.arg1]?.onOkay(message.arg0)

            AdbProtocol.A_WRTE -> {
                val stream = streams[message.arg1]
                if (stream != null) {
                    stream.onData(message.payload)
                    // Grant the daemon credit for the next chunk.
                    sendMessage(AdbProtocol.okay(message.arg1, message.arg0))
                } else {
                    // Data for a stream we already dropped: acknowledge then close it out.
                    sendMessage(AdbProtocol.close(message.arg1, message.arg0))
                }
            }

            AdbProtocol.A_CLSE -> {
                streams.remove(message.arg1)?.onRemoteClose()
            }

            AdbProtocol.A_AUTH -> {
                // adbd re-challenging mid-session means it dropped our authorisation.
                closeInternal("The TV revoked debugging authorisation")
            }

            AdbProtocol.A_CNXN -> {
                deviceBanner = message.payloadAsString
                maxPayload = message.arg1.coerceIn(4096, AdbProtocol.MAX_PAYLOAD)
            }

            else -> Log.d(TAG, "Ignoring ${AdbProtocol.commandName(message.command)}")
        }
    }

    companion object {
        private const val TAG = "AdbConnection"
        private const val OPEN_TIMEOUT_MS = 5_000L
        private const val SHELL_TIMEOUT_MS = 8_000L

        /**
         * Connects, authenticates, and returns a ready session.
         *
         * @param onAwaitingAuthorization invoked when we have sent our public key and the TV is
         *   showing its "Allow debugging?" dialog, so the UI can tell the user to look at the TV.
         */
        suspend fun connect(
            host: String,
            port: Int,
            keyPair: KeyPair,
            connectTimeoutMs: Int = 4_000,
            handshakeTimeoutMs: Int = 30_000,
            onAwaitingAuthorization: () -> Unit = {},
        ): AdbConnection = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: SocketTimeoutException) {
                runCatching { socket.close() }
                throw TransportException(TransportError.Unreachable("$host:$port", e))
            } catch (e: ConnectException) {
                runCatching { socket.close() }
                throw TransportException(TransportError.NetworkDebuggingOff("$host:$port"))
            } catch (e: NoRouteToHostException) {
                runCatching { socket.close() }
                throw TransportException(TransportError.Unreachable("$host:$port", e))
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw TransportException(TransportError.Unreachable("$host:$port", e))
            }

            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val connection = AdbConnection(socket, input, output, host, port)

            try {
                connection.handshake(keyPair, handshakeTimeoutMs, onAwaitingAuthorization)
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }

            connection.startReadLoop()
            connection
        }
    }

    /**
     * CNXN, then the AUTH dance:
     *
     * ```
     * ->  CNXN
     * <-  AUTH TOKEN (20 random bytes)
     * ->  AUTH SIGNATURE (token signed with our key)
     * <-  AUTH TOKEN again   // daemon does not know our key
     * ->  AUTH RSAPUBLICKEY  // TV shows "Allow debugging?" here
     * <-  CNXN               // authorised
     * ```
     */
    private fun handshake(
        keyPair: KeyPair,
        timeoutMs: Int,
        onAwaitingAuthorization: () -> Unit,
    ) {
        socket.soTimeout = timeoutMs
        output.writeAdbMessage(AdbProtocol.connect())

        var sentSignature = false
        var sentPublicKey = false

        while (true) {
            val message = try {
                input.readAdbMessage()
            } catch (e: SocketTimeoutException) {
                throw TransportException(
                    if (sentPublicKey) TransportError.AuthorizationRejected
                    else TransportError.Unreachable("$host:$port", e),
                )
            }

            when (message.command) {
                AdbProtocol.A_CNXN -> {
                    deviceBanner = message.payloadAsString
                    maxPayload = message.arg1.coerceIn(4096, AdbProtocol.MAX_PAYLOAD)
                    // Back to a normal (long) timeout now that the handshake is done; the read
                    // loop must not wake up every 30s just because the TV is idle.
                    socket.soTimeout = 0
                    return
                }

                AdbProtocol.A_AUTH -> {
                    if (message.arg0 != AdbProtocol.AUTH_TYPE_TOKEN) {
                        throw TransportException(
                            TransportError.Protocol("Unexpected AUTH subtype ${message.arg0}"),
                        )
                    }
                    when {
                        !sentSignature -> {
                            val signature = AdbCrypto.signToken(
                                keyPair.private as RSAPrivateKey,
                                message.payload,
                            )
                            output.writeAdbMessage(AdbProtocol.authSignature(signature))
                            sentSignature = true
                        }

                        !sentPublicKey -> {
                            // The signature was not recognised: introduce ourselves. This is the
                            // step that raises the on-TV prompt, and it can sit here for as long
                            // as the user takes to find the remote.
                            val publicKey = AdbCrypto.encodePublicKey(keyPair.public as RSAPublicKey)
                            output.writeAdbMessage(AdbProtocol.authPublicKey(publicKey))
                            sentPublicKey = true
                            onAwaitingAuthorization()
                        }

                        else -> throw TransportException(TransportError.AuthorizationRejected)
                    }
                }

                AdbProtocol.A_STLS -> throw TransportException(TransportError.TlsRequired("$host:$port"))

                else -> throw TransportException(
                    TransportError.Protocol(
                        "Unexpected ${AdbProtocol.commandName(message.command)} during handshake",
                    ),
                )
            }
        }
    }
}
