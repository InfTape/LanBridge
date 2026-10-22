package dev.lanbridge

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream

internal const val RECEIVED_HISTORY_TTL = 24L * 60 * 60 * 1000

/** Durable receipt metadata only: deleting a receipt never touches its content URI. */
internal data class ReceivedRecord(
    val name: String, val receivedAt: Long, val text: String? = null, val uri: String? = null,
    val size: Long = 0, val directory: Boolean = false, val id: String = UUID.randomUUID().toString(),
    val fresh: Boolean = true
) {
    init {
        require(name.isNotBlank() && id.isNotBlank() && receivedAt >= 0 && size >= 0)
        require((text != null) != (uri != null))
        require(uri == null || uri.startsWith("content://"))
        require(!directory || uri != null)
    }
    fun recent(now: Long) = receivedAt <= now && receivedAt > now - RECEIVED_HISTORY_TTL
}

internal data class ReceivedHistorySnapshot(val records: List<ReceivedRecord>, val error: String? = null)

/** Serialized, atomic snapshots. Failed writes retain both the old disk file and live receipts. */
internal class ReceivedHistory(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val replace: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
) {
    private var loaded = false
    private var dirty = false
    private var records = emptyList<ReceivedRecord>()

    @Synchronized fun refresh(): ReceivedHistorySnapshot = update()

    @Synchronized fun add(record: ReceivedRecord): ReceivedHistorySnapshot {
        // Loading after an earlier read error merges, rather than overwrites, live receipts.
        records = listOf(record) + records.filterNot { it.id == record.id }
        dirty = true
        return update()
    }

    @Synchronized fun clear(): ReceivedHistorySnapshot {
        // Explicit clear may replace even an unreadable snapshot. A failed clear remains dirty.
        loaded = true
        records = emptyList()
        dirty = true
        return update()
    }

    private fun update(): ReceivedHistorySnapshot {
        var readFailed = false
        if(!loaded) {
            try {
                val saved = if(file.exists()) read() else emptyList()
                records = (records + saved).distinctBy { it.id }.sortedByDescending { it.receivedAt }
                loaded = true
            } catch(_: Exception) { readFailed = true }
        }
        val now = clock()
        val recent = records.filter { it.recent(now) }.sortedByDescending { it.receivedAt }
        if(recent != records) { records = recent; dirty = true }
        if(readFailed) return ReceivedHistorySnapshot(records, "无法读取接收记录；新记录暂未保存，关闭应用后可能丢失")
        if(dirty) {
            try { write(); dirty = false }
            catch(_: Exception) { return ReceivedHistorySnapshot(records, "接收记录暂未保存，重新打开后可能丢失新记录或恢复旧记录") }
        }
        return ReceivedHistorySnapshot(records)
    }

    private fun write() {
        val parent = requireNotNull(file.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法创建记录目录" }
        val temporary = File.createTempFile(".received-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                val checked = CheckedOutputStream(BufferedOutputStream(output), CRC32())
                val data = DataOutputStream(checked)
                data.writeInt(0x4C425248); data.writeInt(1); data.writeInt(records.size)
                for(record in records) {
                    data.writeString(record.id, 256); data.writeString(record.name, 8192)
                    data.writeLong(record.receivedAt); data.writeLong(record.size); data.writeBoolean(record.directory)
                    data.writeString(record.text, 262144); data.writeString(record.uri, 32768)
                }
                val checksum = checked.checksum.value
                data.writeLong(checksum); data.flush()
                output.fd.sync()
            }
            replace(temporary.toPath(), file.toPath())
        } finally { temporary.delete() }
    }

    private fun read(): List<ReceivedRecord> = FileInputStream(file).use { input ->
        val checked = CheckedInputStream(BufferedInputStream(input), CRC32())
        val data = DataInputStream(checked)
        require(data.readInt() == 0x4C425248 && data.readInt() == 1) { "接收记录格式无效" }
        val count = data.readInt(); require(count >= 0)
        val saved = ArrayList<ReceivedRecord>()
        repeat(count) {
            val id = requireNotNull(data.readString(256)); val name = requireNotNull(data.readString(8192))
            val at = data.readLong(); val size = data.readLong(); val directory = data.readBoolean()
            val text = data.readString(262144); val uri = data.readString(32768)
            saved.add(ReceivedRecord(name, at, text, uri, size, directory, id, fresh = false))
        }
        val checksum = checked.checksum.value
        require(data.readLong() == checksum && data.read() == -1) { "接收记录校验失败" }
        saved
    }
}

private fun DataOutputStream.writeString(value: String?, maximum: Int) {
    if(value == null) { writeInt(-1); return }
    val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= maximum)
    writeInt(bytes.size); write(bytes)
}

private fun DataInputStream.readString(maximum: Int): String? {
    val count = readInt()
    if(count == -1) return null
    require(count in 0..maximum)
    val bytes = ByteArray(count); readFully(bytes)
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
