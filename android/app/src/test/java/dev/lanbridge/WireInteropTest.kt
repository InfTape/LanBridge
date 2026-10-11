package dev.lanbridge

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Run with LANBRIDGE_TEST_PORT and LANBRIDGE_TEST_CODE against BridgeIntegration --fixture. */
class WireInteropTest {
    @Test fun androidCryptoInteroperatesWithWin32() {
        val port = System.getenv("LANBRIDGE_TEST_PORT")
        assumeTrue("Windows fixture not requested", port != null)
        val socket = Socket("127.0.0.1", port!!.toInt()); socket.soTimeout = 10000
        Wire(socket, requireNotNull(System.getenv("LANBRIDGE_TEST_CODE"))).use { channel ->
            channel.send(1, "JVM Android Wire".toByteArray()); assertEquals(1, channel.receive().first)
            val text = "安卓 ↔ Windows 😀\n互通测试".toByteArray()
            channel.send(2, text); val reply = channel.receive(); assertEquals(2, reply.first); assertArrayEquals(text, reply.second)
            assertEquals(6, channel.receive().first)
            for(size in listOf(0, 1, 262144, 262145, 1048593)) {
                val bytes = ByteArray(size) { (it*37).toByte() }; val name = "测试 $size.bin".toByteArray()
                channel.send(3, ByteBuffer.allocate(name.size+10).putShort(name.size.toShort()).put(name).putLong(size.toLong()).array())
                var position = 0
                while(position < size) { val end = minOf(position+262144, size); channel.send(4, bytes.copyOfRange(position, end)); position = end }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes); channel.send(5, digest)
                assertEquals(3, channel.receive().first)
                val output = java.io.ByteArrayOutputStream()
                while(true) { val (type, data) = channel.receive(); if(type == 4) output.write(data) else { assertEquals(5, type); assertArrayEquals(digest, data); break } }
                assertArrayEquals(bytes, output.toByteArray()); assertEquals(6, channel.receive().first)
            }
            channel.send(7); assertEquals(8, channel.receive().first)
        }
    }
}
