package dev.lanbridge

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class TestFolderStorage(val destination: Path) : FolderStorage {
    val staging: Path = Files.createTempDirectory(destination,".incoming-")
    var published: Path? = null
    override fun directory(path: String) { Files.createDirectory(staging.resolve(path)) }
    override fun file(path: String) = Files.newOutputStream(staging.resolve(path))
    override fun publish(name: String): String {
        var index = 0; var result = destination.resolve(name)
        while(Files.exists(result)) result = destination.resolve("$name (${++index})")
        Files.move(staging,result); published = result; return result.toString()
    }
    override fun abort() { staging.toFile().deleteRecursively() }
}
class FolderTransferTest {
    private fun rejected(block: () -> Unit) { try { block(); fail("Should reject") } catch(_: IllegalArgumentException) {} }
    @Test fun portablePathsRejectTraversalAliasesAndMalformedUtf8() {
        for(path in listOf("../escape","/absolute","C:/escape","a\\b","a//b","a/./b","CON/x","x/LPT¹.txt","a/b.","a/b ","a/\u0000", "a/")) rejected { FolderProtocol.path(path) }
        rejected { FolderProtocol.path((1..65).joinToString("/") { "a" }) }
        rejected { FolderProtocol.path("a".repeat(181)) }
        rejected { FolderProtocol.path("中".repeat(86)) }
        try { FolderProtocol.text(byteArrayOf(0xc0.toByte(),0xaf.toByte())); fail() } catch(_: java.nio.charset.CharacterCodingException) {}
        assertEquals(listOf("旅行","中文😀.txt"),FolderProtocol.path("旅行/中文😀.txt"))
        assertEquals(1,FolderProtocol.path(FolderProtocol.collisionName("中".repeat(85),10000)).size)
    }
    @Test fun headerRoundTripAndInvalidTotals() {
        val header = FolderProtocol.Header(42,"照片😀",2,3,512)
        assertEquals(header,FolderProtocol.decode(FolderProtocol.encode(header)))
        rejected { FolderProtocol.decode(FolderProtocol.encode(header).dropLast(1).toByteArray()) }
        rejected { FolderProtocol.encode(header.copy(files = -1)) }
        rejected { FolderProtocol.encode(header.copy(directories = 10000)) }
        rejected { FolderProtocol.encode(header.copy(id = 0)) }
    }
    @Test fun publishesWholeFolderWithEmptyDirectoriesAndNoOverwrite() {
        val root = Files.createTempDirectory("lanbridge-folders-")
        try {
            Files.createDirectory(root.resolve("照片")); Files.writeString(root.resolve("照片/existing.txt"),"keep")
            val storage = TestFolderStorage(root)
            FolderReceiver(FolderProtocol.Header(1,"照片",2,2,3),storage).use { receiver ->
                receiver.consume(10,"旅行".toByteArray()); receiver.consume(10,"空目录".toByteArray())
                receiver.consume(11,FolderProtocol.fileHeader("旅行/a.bin",3)); receiver.consume(4,byteArrayOf(1,2,3)); receiver.consume(5,MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1,2,3)))
                receiver.consume(11,FolderProtocol.fileHeader("空文件.txt",0)); receiver.consume(5,MessageDigest.getInstance("SHA-256").digest())
                assertNull(storage.published)
                val published = Path.of(receiver.commit()); assertEquals("照片 (1)",published.fileName.toString())
                assertArrayEquals(byteArrayOf(1,2,3),Files.readAllBytes(published.resolve("旅行/a.bin")))
                assertTrue(Files.isDirectory(published.resolve("空目录")))
                assertEquals(0,Files.size(published.resolve("空文件.txt")))
            }
            assertEquals("keep",Files.readString(root.resolve("照片/existing.txt")))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun abortAndChecksumFailureRemoveEntireUnpublishedTree() {
        val root = Files.createTempDirectory("lanbridge-folders-")
        try {
            val storage = TestFolderStorage(root)
            FolderReceiver(FolderProtocol.Header(1,"broken",1,0,3),storage).use { receiver ->
                receiver.consume(11,FolderProtocol.fileHeader("a.bin",3)); receiver.consume(4,byteArrayOf(1,2,3))
                rejected { receiver.consume(5,ByteArray(32)) }
            }
            assertFalse(Files.exists(storage.staging)); assertFalse(Files.exists(root.resolve("broken")))
            val interrupted = TestFolderStorage(root)
            FolderReceiver(FolderProtocol.Header(2,"interrupted",1,0,3),interrupted).use { it.consume(11,FolderProtocol.fileHeader("a.bin",3)); it.consume(4,byteArrayOf(1)) }
            assertFalse(Files.exists(interrupted.staging))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun refusesMissingParentsDuplicatePathsAndIncompleteCommit() {
        val root = Files.createTempDirectory("lanbridge-folders-")
        try {
            FolderReceiver(FolderProtocol.Header(1,"root",1,2,0),TestFolderStorage(root)).use { receiver ->
                rejected { receiver.consume(11,FolderProtocol.fileHeader("missing/file.txt",0)) }
                receiver.consume(10,"A".toByteArray())
                rejected { receiver.consume(10,"a".toByteArray()) }
                rejected { receiver.commit() }
            }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun completelyEmptyRootIsPreserved() {
        val root = Files.createTempDirectory("lanbridge-folders-")
        try { FolderReceiver(FolderProtocol.Header(1,"empty",0,0,0),TestFolderStorage(root)).use { assertTrue(Files.isDirectory(Path.of(it.commit()))) } }
        finally { root.toFile().deleteRecursively() }
    }
}
