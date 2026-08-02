package com.sizwe.tvremote

import com.sizwe.tvremote.adb.AdbMessage
import com.sizwe.tvremote.adb.AdbProtocol
import com.sizwe.tvremote.adb.readAdbMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Framing tests. These run on the JVM without a device, which matters because a header bug shows
 * up on real hardware as a silent hang rather than an error.
 */
class AdbProtocolTest {

    @Test
    fun `header is little-endian with the documented layout`() {
        val payload = "shell:input keyevent 24".toByteArray()
        val encoded = AdbMessage(AdbProtocol.A_OPEN, 7, 0, payload).encode()

        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbProtocol.A_OPEN, buffer.int)
        assertEquals(7, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(payload.size, buffer.int)
        assertEquals(payload.sumOf { it.toInt() and 0xFF }, buffer.int)
        assertEquals(AdbProtocol.A_OPEN xor -1, buffer.int)
    }

    @Test
    fun `round trips through the reader`() {
        val original = AdbMessage(AdbProtocol.A_WRTE, 3, 9, byteArrayOf(1, 2, 3, 4, 5))
        val decoded = ByteArrayInputStream(original.encode()).readAdbMessage()

        assertEquals(original.command, decoded.command)
        assertEquals(original.arg0, decoded.arg0)
        assertEquals(original.arg1, decoded.arg1)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test
    fun `connect banner and open destinations are NUL terminated`() {
        val connect = AdbProtocol.connect()
        assertEquals(0.toByte(), connect.payload.last())

        val open = AdbProtocol.open(1, "shell:true")
        assertEquals(0.toByte(), open.payload.last())
        assertEquals("shell:true", String(open.payload.dropLast(1).toByteArray()))
    }

    @Test
    fun `command names decode to the ascii adbd sends`() {
        assertEquals("CNXN", AdbProtocol.commandName(AdbProtocol.A_CNXN))
        assertEquals("AUTH", AdbProtocol.commandName(AdbProtocol.A_AUTH))
        assertEquals("WRTE", AdbProtocol.commandName(AdbProtocol.A_WRTE))
    }
}
