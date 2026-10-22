package dev.lanbridge

import org.junit.Assert.*
import org.junit.Test

class ReceiveProgressTest {
    @Test fun fullBytesAreNotACommittedFile() {
        val full = ReceiveProgress("project.zip", 128, "下载/LanBridge").advance(128)
        assertEquals(100, full.percent)
        assertEquals(ReceivePhase.RECEIVING, full.phase)
        assertNull(full.savedUri)
        assertThrows(IllegalArgumentException::class.java) { full.saved("content://downloads/1") }
        val saving = full.saving()
        assertTrue(saving.active)
        assertNull(saving.savedUri)
        val saved = saving.saved("content://downloads/1")
        assertEquals(ReceivePhase.SUCCEEDED, saved.phase)
        assertEquals("content://downloads/1", saved.savedUri)
        assertFalse(saved.active)
    }
    @Test fun interruptedFileCannotBeViewedOrPublished() {
        val partial = ReceiveProgress("large.zip", 1000, "下载/LanBridge").advance(300)
        assertThrows(IllegalArgumentException::class.java) { partial.saving() }
        val failure = partial.failed("连接中断", "测试连接已关闭")
        assertEquals(300L, failure.current)
        assertNull(failure.savedUri)
        assertEquals(ReceivePhase.FAILED, failure.phase)
        assertEquals(failure, failure.advance(1000))
        assertThrows(IllegalArgumentException::class.java) { failure.saved("content://downloads/1") }
    }
    @Test fun laterConnectionFailureCannotUndoPublishedFile() {
        val saved = ReceiveProgress("ready.png", 1, "下载/LanBridge").advance(1).saving().saved("content://downloads/2")
        assertEquals(saved, saved.failed("回执发送失败", "连接已断开"))
    }
    @Test fun zeroByteFileStillRequiresPublication() {
        val empty = ReceiveProgress("empty.bin", 0, "下载/LanBridge")
        assertEquals(100, empty.percent)
        assertTrue(empty.active)
        assertNull(empty.savedUri)
        assertEquals(ReceivePhase.SUCCEEDED, empty.saving().saved("content://downloads/3").phase)
    }
    @Test fun invalidOrRewindingProgressIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ReceiveProgress("bad", -1, "目录") }
        val progress = ReceiveProgress("test", 100, "目录").advance(50)
        assertThrows(IllegalArgumentException::class.java) { progress.advance(49) }
        assertThrows(IllegalArgumentException::class.java) { progress.advance(101) }
    }
    @Test fun savedDirectoryUsesActualNameAndDestination() {
        val folder = ReceiveProgress("project", 10, "用户选择的目录", directory = true).advance(10).saving().saved("content://documents/tree/1", "project (1)")
        assertTrue(folder.directory)
        assertEquals("project (1)", folder.name)
        assertEquals("用户选择的目录", folder.destination)
        assertNotEquals(folder.id, ReceiveProgress("project", 10, "目录").id)
    }
}
