package dev.lanbridge

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest

class FolderInteropTest {
    @Test fun androidFolderProtocolInteroperatesWithWin32BothWays() {
        val port = System.getenv("LANBRIDGE_FOLDER_TEST_PORT")
        assumeTrue("Windows folder fixture not requested",port != null)
        val root = Files.createTempDirectory("lanbridge-folder-interop-")
        try {
            Socket("127.0.0.1",port!!.toInt()).also { it.soTimeout = 10000 }.let { socket ->
                Wire(socket,requireNotNull(System.getenv("LANBRIDGE_TEST_CODE"))).use { channel ->
                    channel.send(1,"JVM Android\n${FolderProtocol.CAPABILITY}".toByteArray()); assertEquals(1,channel.receive().first)
                    val content = ByteArray(262145) { (it*37).toByte() }
                    val header = FolderProtocol.Header(42,"旅行😀",2,3,content.size.toLong())
                    channel.send(9,FolderProtocol.encode(header))
                    val ready = channel.receive(); assertEquals(14,ready.first); assertEquals(42,FolderProtocol.reply(ready.second).id); assertTrue(FolderProtocol.reply(ready.second).accepted)
                    for(name in listOf("照片","照片/空目录","另一个空目录")) channel.send(10,name.toByteArray())
                    channel.send(11,FolderProtocol.fileHeader("照片/内容.bin",content.size.toLong()))
                    channel.send(4,content.copyOfRange(0,262144)); channel.send(4,content.copyOfRange(262144,content.size)); channel.send(5,MessageDigest.getInstance("SHA-256").digest(content))
                    channel.send(11,FolderProtocol.fileHeader("空文件.txt",0)); channel.send(5,MessageDigest.getInstance("SHA-256").digest())
                    channel.send(12,FolderProtocol.id(42)); val complete = channel.receive(); assertEquals(15,complete.first); assertTrue(FolderProtocol.reply(complete.second).accepted)
                    val start = channel.receive(); assertEquals(9,start.first); val incoming = FolderProtocol.decode(start.second)
                    assertEquals(header.name,incoming.name); assertEquals(header.bytes,incoming.bytes); assertEquals(header.files,incoming.files); assertEquals(header.directories,incoming.directories)
                    channel.send(14,FolderProtocol.reply(incoming.id,true))
                    val storage = TestFolderStorage(root)
                    FolderReceiver(incoming,storage).use { receiver ->
                        while(true) {
                            val (type,data) = channel.receive()
                            if(type == 12) { assertEquals(incoming.id,FolderProtocol.id(data)); receiver.commit(); channel.send(15,FolderProtocol.reply(incoming.id,true)); break }
                            receiver.consume(type,data)
                        }
                    }
                    val published = requireNotNull(storage.published)
                    assertArrayEquals(content,Files.readAllBytes(published.resolve("照片/内容.bin")))
                    assertEquals(0,Files.size(published.resolve("空文件.txt")))
                    assertTrue(Files.isDirectory(published.resolve("照片/空目录"))); assertTrue(Files.isDirectory(published.resolve("另一个空目录")))
                    channel.send(7); assertEquals(8,channel.receive().first)
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
