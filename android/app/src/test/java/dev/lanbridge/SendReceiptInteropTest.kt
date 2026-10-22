package dev.lanbridge

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Production receipt gate + encrypted Wire against the native Windows server. */
class SendReceiptInteropTest {
    @Test fun confirmsTextThenConsecutiveFilesUsingWindowsReceipts() = runBlocking(Dispatchers.IO) {
        val port = System.getenv("LANBRIDGE_TEST_PORT")
        assumeTrue("Windows fixture not requested", port != null)
        val socket = Socket("127.0.0.1", port!!.toInt()).apply { soTimeout = 10_000 }
        Wire(socket, requireNotNull(System.getenv("LANBRIDGE_TEST_CODE"))).use { channel ->
            channel.send(1, "Receipt gate test".toByteArray())
            assertEquals(1, channel.receive().first)
            val gate = SendReceiptGate()
            val text = "确认收到之后才成功 😀".toByteArray()
            val textReceipt = async {
                val echo = channel.receive()
                assertEquals(2, echo.first); assertArrayEquals(text, echo.second)
                assertEquals(6, channel.receive().first)
                gate.confirm(channel)
            }
            gate.send(channel, write = { channel.send(2, text) }, onWritten = {})
            textReceipt.await()
            for(size in listOf(0, 262145)) {
                val bytes = ByteArray(size) { (it * 37).toByte() }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                val name = "receipt-$size.bin".toByteArray()
                val fileReceipt = async {
                    assertEquals(3, channel.receive().first)
                    val output = java.io.ByteArrayOutputStream()
                    while(true) {
                        val (type, data) = channel.receive()
                        if(type == 4) output.write(data)
                        else { assertEquals(5, type); assertArrayEquals(hash, data); break }
                    }
                    assertArrayEquals(bytes, output.toByteArray())
                    assertEquals(6, channel.receive().first)
                    gate.confirm(channel)
                }
                gate.send(channel, write = {
                    channel.send(3, ByteBuffer.allocate(name.size + 10).putShort(name.size.toShort()).put(name).putLong(size.toLong()).array())
                    var position = 0
                    while(position < size) {
                        val end = minOf(position + 262144, size)
                        channel.send(4, bytes.copyOfRange(position, end)); position = end
                    }
                    channel.send(5, hash)
                }, onWritten = {})
                fileReceipt.await()
            }
            channel.send(7)
            assertEquals(8, channel.receive().first)
        }
    }
}
