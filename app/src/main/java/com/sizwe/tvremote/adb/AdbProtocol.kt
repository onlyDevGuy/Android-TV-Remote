package com.sizwe.tvremote.adb

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ADB wire protocol, as spoken by `adbd` on port 5555.
 *
 * Every message is a 24-byte little-endian header followed by an optional payload:
 *
 * ```
 * struct message {
 *     uint32 command;      // one of the A_* constants below
 *     uint32 arg0;
 *     uint32 arg1;
 *     uint32 data_length;  // payload byte count
 *     uint32 data_check;   // plain sum of the payload bytes
 *     uint32 magic;        // command ^ 0xffffffff
 * };
 * ```
 *
 * Reference: `packages/modules/adb/protocol.txt` in AOSP.
 */
object AdbProtocol {

    const val HEADER_LENGTH = 24

    /** Protocol version we advertise. */
    const val A_VERSION = 0x0100_0000

    /** Largest payload we are willing to receive. The device answers with its own limit. */
    const val MAX_PAYLOAD = 256 * 1024

    // Commands (ASCII, read little-endian: "CNXN" -> 0x4e584e43)
    const val A_CNXN = 0x4e58_4e43
    const val A_AUTH = 0x4854_5541
    const val A_OPEN = 0x4e45_504f
    const val A_OKAY = 0x5941_4b4f
    const val A_CLSE = 0x4553_4c43
    const val A_WRTE = 0x4554_5257
    const val A_STLS = 0x534c_5453

    // AUTH subtypes
    const val AUTH_TYPE_TOKEN = 1
    const val AUTH_TYPE_SIGNATURE = 2
    const val AUTH_TYPE_RSA_PUBLIC_KEY = 3

    /**
     * Identifies us to the device. Deliberately advertises no features: `shell_v2` would make the
     * daemon frame shell output in a second protocol layer, and all we need is fire-and-forget
     * `input keyevent`, so the plain `shell:` service is both simpler and more portable across the
     * older TV boxes this app targets.
     */
    const val CONNECT_BANNER = "host::tv-remote"

    fun commandName(command: Int): String = when (command) {
        A_CNXN -> "CNXN"
        A_AUTH -> "AUTH"
        A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"
        A_CLSE -> "CLSE"
        A_WRTE -> "WRTE"
        A_STLS -> "STLS"
        else -> "0x%08x".format(command)
    }

    /** The banner and OPEN destinations are NUL-terminated C strings on the wire. */
    private fun cString(value: String): ByteArray = value.toByteArray(Charsets.UTF_8) + 0.toByte()

    fun connect(): AdbMessage =
        AdbMessage(A_CNXN, A_VERSION, MAX_PAYLOAD, cString(CONNECT_BANNER))

    fun authSignature(signature: ByteArray): AdbMessage =
        AdbMessage(A_AUTH, AUTH_TYPE_SIGNATURE, 0, signature)

    fun authPublicKey(publicKey: ByteArray): AdbMessage =
        // adbd expects a NUL-terminated base64 blob here.
        AdbMessage(A_AUTH, AUTH_TYPE_RSA_PUBLIC_KEY, 0, publicKey + 0.toByte())

    fun open(localId: Int, destination: String): AdbMessage =
        AdbMessage(A_OPEN, localId, 0, cString(destination))

    fun okay(localId: Int, remoteId: Int): AdbMessage = AdbMessage(A_OKAY, localId, remoteId)

    fun close(localId: Int, remoteId: Int): AdbMessage = AdbMessage(A_CLSE, localId, remoteId)

    fun write(localId: Int, remoteId: Int, payload: ByteArray): AdbMessage =
        AdbMessage(A_WRTE, localId, remoteId, payload)
}

data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY,
) {
    /** Payload as text, with the trailing NUL terminator dropped if adbd sent one. */
    val payloadAsString: String
        get() = String(payload, Charsets.UTF_8).trimEnd(NUL_CHAR)

    fun encode(): ByteArray {
        val buffer = ByteBuffer
            .allocate(AdbProtocol.HEADER_LENGTH + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(payload.size)
        buffer.putInt(checksum(payload))
        buffer.putInt(command xor -1)
        buffer.put(payload)
        return buffer.array()
    }

    override fun toString(): String =
        "${AdbProtocol.commandName(command)}(arg0=$arg0, arg1=$arg1, len=${payload.size})"

    // data class over a ByteArray: hand-written so equality compares contents, not identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbMessage) return false
        return command == other.command &&
            arg0 == other.arg0 &&
            arg1 == other.arg1 &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /** The C-string terminator adbd appends to text payloads. */
        const val NUL_CHAR: Char = '\u0000'

        /** adbd's "checksum" is just the unsigned sum of the payload bytes, truncated to 32 bits. */
        fun checksum(payload: ByteArray): Int {
            var sum = 0
            for (b in payload) sum += (b.toInt() and 0xFF)
            return sum
        }
    }
}

/** Writes a message and flushes; callers are expected to serialise access to [out]. */
fun OutputStream.writeAdbMessage(message: AdbMessage) {
    write(message.encode())
    flush()
}

/**
 * Blocking read of exactly one message. Throws [AdbProtocolException] on a corrupt header, which
 * the connection treats as fatal - a desynchronised stream cannot be recovered.
 */
fun InputStream.readAdbMessage(maxPayload: Int = AdbProtocol.MAX_PAYLOAD): AdbMessage {
    val header = readFullyOrThrow(AdbProtocol.HEADER_LENGTH)
    val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

    val command = buffer.int
    val arg0 = buffer.int
    val arg1 = buffer.int
    val length = buffer.int
    val checksum = buffer.int
    val magic = buffer.int

    if (command xor -1 != magic) {
        throw AdbProtocolException(
            "Bad magic: command=0x%08x magic=0x%08x".format(command, magic),
        )
    }
    if (length < 0 || length > maxPayload) {
        throw AdbProtocolException("Payload length $length out of range (max $maxPayload)")
    }

    val payload = if (length == 0) ByteArray(0) else readFullyOrThrow(length)

    // adbd stopped filling in the checksum at protocol version 0x01000001; only verify when set.
    if (checksum != 0 && AdbMessage.checksum(payload) != checksum) {
        throw AdbProtocolException("Payload checksum mismatch on ${AdbProtocol.commandName(command)}")
    }

    return AdbMessage(command, arg0, arg1, payload)
}

private fun InputStream.readFullyOrThrow(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(bytes, read, count - read)
        if (n < 0) throw EOFException("Stream closed after $read of $count bytes")
        read += n
    }
    return bytes
}

class AdbProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
