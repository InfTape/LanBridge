package dev.lanbridge

import java.io.Closeable
import java.io.OutputStream
import java.security.MessageDigest

internal interface FolderStorage {
    fun directory(path: String)
    fun file(path: String): OutputStream
    fun publish(name: String): String
    fun abort()
}
internal class FolderReceiver(val header: FolderProtocol.Header, private val storage: FolderStorage) : Closeable {
    private data class Pending(val size: Long, val stream: OutputStream, val digest: MessageDigest = MessageDigest.getInstance("SHA-256"), var done: Long = 0)
    private var pending: Pending? = null
    private val names = mutableSetOf<String>()
    private val directories = mutableSetOf("")
    private var files = 0; private var dirs = 0
    var done = 0L; private set
    private var published = false
    init { FolderProtocol.encode(header) }
    private fun register(name: String, directory: Boolean) {
        val parts = FolderProtocol.path(name); val parent = parts.dropLast(1).joinToString("/")
        require((if(parent.isEmpty()) "" else FolderProtocol.key(parent)) in directories) { "父目录未声明" }
        require(names.add(FolderProtocol.key(name))) { "目录内有同名或大小写冲突" }
        if(directory) { require(dirs < header.directories); dirs++; directories.add(FolderProtocol.key(name)) }
        else require(files < header.files)
    }
    fun consume(type: Int, data: ByteArray) {
        check(!published)
        when(type) {
            10 -> { require(pending == null); val path = FolderProtocol.text(data); register(path,true); storage.directory(path) }
            11 -> { require(pending == null); val (path,size) = FolderProtocol.file(data); require(size <= header.bytes-done); register(path,false); pending = Pending(size,storage.file(path)) }
            4 -> { val file = requireNotNull(pending); require(data.isNotEmpty() && data.size <= file.size-file.done); file.stream.write(data); file.digest.update(data); file.done += data.size; done += data.size }
            5 -> { val file = requireNotNull(pending); require(data.size == 32 && file.done == file.size && MessageDigest.isEqual(file.digest.digest(),data)) { "文件夹内文件校验失败" }; file.stream.flush(); file.stream.close(); pending = null; files++ }
            else -> error("无效文件夹数据帧")
        }
    }
    fun commit(): String {
        check(!published); require(pending == null && files == header.files && dirs == header.directories && done == header.bytes) { "文件夹尚未接收完整" }
        return storage.publish(header.name).also { published = true }
    }
    override fun close() { try { pending?.stream?.close() } finally { pending = null; if(!published) storage.abort() } }
}
