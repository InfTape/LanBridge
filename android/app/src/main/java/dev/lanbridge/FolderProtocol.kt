package dev.lanbridge

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal object FolderProtocol {
    const val CAPABILITY = "LB-FOLDER1"
    const val MAX_ENTRIES = 10000
    const val MAX_FILE = 100L*1024*1024*1024
    const val MAX_TOTAL = 100L*1024*1024*1024*1024
    data class Header(val id: Long, val name: String, val files: Int, val directories: Int, val bytes: Long)
    fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun path(value: String): List<String> {
        require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 4096) { "目录路径过长或为空" }
        val parts = value.split('/')
        require(parts.size <= 64)
        for(name in parts) {
            require(name.isNotEmpty() && name != "." && name != ".." && name.length <= 180 && name.toByteArray(Charsets.UTF_8).size <= 255 && !name.endsWith('.') && !name.endsWith(' ')) { "目录名称无效或过长：$name" }
            require(name.none { it < ' ' || it in "<>:\"\\|?*" }) { "目录含不支持的名称：$name" }
            require(!name.substringBefore('.').uppercase(Locale.ROOT).matches(Regex("CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[1-9¹²³]|LPT[1-9¹²³]"))) { "目录含 Windows 保留名称：$name" }
        }
        // Strict encoder also rejects unpaired UTF-16 surrogates before sending.
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        return parts
    }
    fun key(value: String): String { path(value); return value.uppercase(Locale.ROOT) }
    fun collisionName(name: String, index: Int): String {
        if(index == 0) return name
        val suffix = " ($index)"; var base = name
        while(base.length+suffix.length > 180 || (base+suffix).toByteArray(Charsets.UTF_8).size > 255) {
            base = base.dropLast(1); if(base.lastOrNull()?.isHighSurrogate() == true) base = base.dropLast(1)
        }
        return base+suffix
    }
    fun encode(h: Header): ByteArray {
        require(path(h.name).size == 1)
        require(h.id != 0L && h.files >= 0 && h.directories >= 0 && h.files.toLong()+h.directories <= MAX_ENTRIES && h.bytes in 0..MAX_TOTAL && (h.files > 0 || h.bytes == 0L))
        val name = h.name.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(name.size+26).putLong(h.id).putShort(name.size.toShort()).put(name).putInt(h.files).putInt(h.directories).putLong(h.bytes).array()
    }
    fun decode(data: ByteArray): Header {
        require(data.size >= 26)
        val buffer = ByteBuffer.wrap(data); val id = buffer.long; val length = buffer.short.toInt() and 65535
        require(data.size == length+26)
        val name = text(ByteArray(length).also(buffer::get))
        return Header(id, name, buffer.int, buffer.int, buffer.long).also { encode(it) }
    }
    fun fileHeader(name: String, size: Long): ByteArray {
        path(name); require(size in 0..MAX_FILE)
        val bytes = name.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(bytes.size+10).putShort(bytes.size.toShort()).put(bytes).putLong(size).array()
    }
    fun file(data: ByteArray): Pair<String, Long> {
        require(data.size >= 10); val buffer = ByteBuffer.wrap(data); val n = buffer.short.toInt() and 65535
        require(data.size == n+10)
        val name = text(ByteArray(n).also(buffer::get)); val size = buffer.long
        fileHeader(name,size); return name to size
    }
    fun id(id: Long): ByteArray = ByteBuffer.allocate(8).putLong(id).array()
    fun id(data: ByteArray): Long { require(data.size >= 8); return ByteBuffer.wrap(data).long }
    data class Reply(val id: Long, val accepted: Boolean, val reason: String)
    fun reply(id: Long, accepted: Boolean, reason: String = ""): ByteArray = id(id)+byteArrayOf(if(accepted) 1 else 0)+reason.toByteArray(Charsets.UTF_8)
    fun reply(data: ByteArray): Reply { require(data.size in 9..4096 && data[8] in 0..1); return Reply(id(data), data[8] == 1.toByte(), text(data.copyOfRange(9,data.size))) }
}
