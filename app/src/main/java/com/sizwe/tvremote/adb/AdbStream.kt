package com.sizwe.tvremote.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One logical ADB stream, i.e. one `shell:` invocation, multiplexed over the shared socket.
 *
 * Flow control follows the protocol: the daemon will not send another `WRTE` until we answer the
 * previous one with `OKAY`, and we must not send another `WRTE` until it answers ours. The
 * [writeTokens] channel models our side of that window.
 */
class AdbStream internal constructor(
    val localId: Int,
    private val connection: AdbConnection,
) {

    @Volatile
    internal var remoteId: Int = 0
        private set

    private val opened = CompletableDeferred<Boolean>()
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeTokens = Channel<Unit>(Channel.CONFLATED)
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    /** Suspends until the daemon accepts (`OKAY`) or rejects (`CLSE`) the OPEN. */
    internal suspend fun awaitOpen(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { opened.await() } ?: false

    /** Reads the next chunk the daemon wrote, or null once the stream is finished. */
    suspend fun read(timeoutMs: Long = DEFAULT_READ_TIMEOUT_MS): ByteArray? =
        withTimeoutOrNull(timeoutMs) {
            incoming.receiveCatching().getOrNull()
        }

    /**
     * Drains everything the command prints until it exits (or [timeoutMs] elapses). Shell commands
     * we run are short and one-shot, so buffering the whole output is fine.
     */
    suspend fun readAll(timeoutMs: Long = DEFAULT_READ_TIMEOUT_MS): String {
        val builder = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val chunk = read(remaining) ?: break
            builder.append(String(chunk, Charsets.UTF_8))
        }
        return builder.toString()
    }

    suspend fun write(bytes: ByteArray, timeoutMs: Long = DEFAULT_READ_TIMEOUT_MS) {
        if (isClosed) throw AdbProtocolException("Stream $localId is closed")
        // Wait for the credit the daemon granted with its OKAY before pushing more data.
        withTimeoutOrNull(timeoutMs) { writeTokens.receive() }
        connection.sendMessage(AdbProtocol.write(localId, remoteId, bytes))
    }

    suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { connection.sendMessage(AdbProtocol.close(localId, remoteId)) }
            finish()
        }
    }

    // --- callbacks from the connection's read loop ---

    internal fun onOkay(remoteId: Int) {
        this.remoteId = remoteId
        opened.complete(true)
        writeTokens.trySend(Unit)
    }

    internal suspend fun onData(payload: ByteArray) {
        incoming.send(payload)
    }

    internal fun onRemoteClose() {
        closed.set(true)
        // A CLSE before any OKAY means the daemon refused the service outright.
        opened.complete(false)
        finish()
    }

    private fun finish() {
        incoming.close()
        writeTokens.close()
    }

    private companion object {
        const val DEFAULT_READ_TIMEOUT_MS = 5_000L
    }
}
