package dev.lanbridge

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReceivedHistoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 1_800_000_000_000L
    private fun file() = File(temporary.root, "history/history.bin")
    private fun receipt(name: String, at: Long = now) = ReceivedRecord(name, at, uri = "content://media/external/downloads/1")
    private fun store(file: File = file()) = ReceivedHistory(file, { now })

    @Test fun restartRestoresFilesFoldersFullTextAndStableIdsWithoutFlashing() {
        val store = store()
        val file = receipt("图片😀.png")
        val folder = ReceivedRecord("目录", now + 1, uri = "content://documents/tree/root/document/root%2Ffolder", size = 1234, directory = true)
        // More than writeUTF's 65,535-byte limit; keep the complete permitted text payload.
        val text = ReceivedRecord("文本", now + 2, text = "中文😀".repeat(20_000))
        store.add(file); now++; store.add(folder); now++; store.add(text)
        val restored = store().refresh()
        assertNull(restored.error)
        assertEquals(listOf(text, folder, file).map { it.copy(fresh = false) }, restored.records)
        assertEquals(text.text, restored.records.first().text)
        assertTrue(restored.records.none { it.fresh })
    }

    @Test fun expiresAtExactly24HoursAndKeepsNewerReceiptsAfterRestart() {
        val store = store()
        val expired = receipt("到期")
        store.add(expired)
        now += RECEIVED_HISTORY_TTL - 1
        assertEquals(listOf(expired.id), store.refresh().records.map { it.id })
        val recent = receipt("刚收到")
        store.add(recent)
        now++
        val snapshot = store().refresh()
        assertNull(snapshot.error)
        assertEquals(listOf(recent.id), snapshot.records.map { it.id })
        assertEquals(listOf(recent.id), store().refresh().records.map { it.id })
    }

    @Test fun livePruningAndReopeningAfterLongAbsenceBothRemoveExpiredRows() {
        val saved = store()
        saved.add(receipt("旧文件"))
        now += RECEIVED_HISTORY_TTL
        assertTrue(saved.refresh().records.isEmpty())
        assertTrue(store().refresh().records.isEmpty())
        saved.add(receipt("另一个"))
        now += RECEIVED_HISTORY_TTL * 3
        assertTrue(store().refresh().records.isEmpty())
        assertTrue(store().refresh().records.isEmpty())
    }

    @Test fun allRecentRowsSurviveBeyondOld80LimitAndNewestArrivalWinsTies() {
        val saved = store()
        val arrivals = (0..100).map { receipt("文件$it") }
        arrivals.forEach { assertNull(saved.add(it).error) }
        val restored = store().refresh().records
        assertEquals(101, restored.size)
        assertEquals(arrivals.reversed().map { it.id }, restored.map { it.id })
        val changed = arrivals.first().copy(name = "更新记录")
        val updated = saved.add(changed).records
        assertEquals(101, updated.size)
        assertEquals("更新记录", updated.first().name)
    }

    @Test fun clearPersistsAndNeverDeletesTheActualContent() {
        val actualFile = File(temporary.root, "下载/LanBridge/image.png").apply { requireNotNull(parentFile).mkdirs(); writeText("image bytes") }
        val actualFolder = File(temporary.root, "授权目录/folder").apply { mkdirs() }
        val saved = store()
        saved.add(receipt("image.png"))
        saved.add(ReceivedRecord("folder", now, uri = "content://documents/folder", directory = true))
        assertNull(saved.clear().error)
        assertTrue(store().refresh().records.isEmpty())
        assertEquals("image bytes", actualFile.readText())
        assertTrue(actualFolder.isDirectory)
        saved.add(receipt("清空后收到"))
        assertEquals(listOf("清空后收到"), store().refresh().records.map { it.name })
    }

    @Test fun interruptedReplacementKeepsOldDiskAndRetriesAllLiveReceipts() {
        var fail = false
        val saved = ReceivedHistory(file(), { now }) { source, target ->
            if(fail) throw IOException("模拟写入中断")
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        val first = receipt("已保存"); saved.add(first)
        fail = true
        val second = receipt("写入未完成")
        val failed = saved.add(second)
        assertNotNull(failed.error)
        assertEquals(listOf(second.id, first.id), failed.records.map { it.id })
        assertEquals(listOf(first.id), store().refresh().records.map { it.id })
        assertFalse(requireNotNull(file().parentFile).listFiles()!!.any { it.name.endsWith(".tmp") })
        fail = false
        assertNull(saved.refresh().error)
        assertEquals(listOf(second.id, first.id), store().refresh().records.map { it.id })
    }

    @Test fun failedClearIsReportedAndRetryDoesNotResurrectOldRows() {
        var fail = false
        val saved = ReceivedHistory(file(), { now }) { source, target ->
            if(fail) throw IOException("模拟磁盘错误")
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        saved.add(receipt("旧记录"))
        fail = true
        assertNotNull(saved.clear().error)
        assertTrue(saved.refresh().records.isEmpty())
        fail = false
        assertNull(saved.refresh().error)
        assertTrue(store().refresh().records.isEmpty())
    }

    @Test fun corruptedDiskIsNotOverwrittenAndRecoveryMergesReceiptsArrivingDuringReadErrors() {
        store().add(receipt("原记录"))
        val bytes = file().readBytes()
        file().writeBytes(bytes.copyOf(bytes.size - 3))
        val damaged = file().readBytes()
        val saved = store()
        assertNotNull(saved.refresh().error)
        assertNotNull(saved.add(receipt("读取失败后收到")).error)
        assertArrayEquals(damaged, file().readBytes())
        file().writeBytes(bytes)
        val recovered = saved.refresh()
        assertNull(recovered.error)
        assertEquals(listOf("读取失败后收到", "原记录"), recovered.records.map { it.name })
        assertEquals(recovered.records.map { it.id }, store().refresh().records.map { it.id })
    }

    @Test fun checksumRejectsOtherwiseValidTamperingAndExplicitClearRecovers() {
        store().add(receipt("原始名称"))
        val bytes = file().readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file().writeBytes(bytes)
        val saved = store()
        assertNotNull(saved.refresh().error)
        assertTrue(saved.refresh().records.isEmpty())
        assertNull(saved.clear().error)
        assertTrue(store().refresh().records.isEmpty())
    }

    @Test fun parallelReceiptsAreSerializedAndAllRemainOnDisk() {
        val saved = store()
        val arrivals = (0..23).map { receipt("并发$it") }
        val workers = Executors.newFixedThreadPool(4)
        try {
            val tasks = arrivals.map { record -> workers.submit { assertNull(saved.add(record).error) } }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { workers.shutdownNow() }
        assertEquals(arrivals.map { it.id }.toSet(), store().refresh().records.map { it.id }.toSet())
    }
}
