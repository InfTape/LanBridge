package dev.lanbridge

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class BridgeApplication : Application() { val engine by lazy { TransferEngine(this) } }
data class Received(val name: String, val text: String? = null, val uri: Uri? = null, val size: Long = 0)
data class TransferState(
    val connected: Boolean = false, val connecting: Boolean = false,
    val sending: Boolean = false, val pendingShares: Int = 0, val status: String = "准备连接电脑",
    val peer: String = "", val progress: Float? = null, val progressLabel: String = "",
    val received: List<Received> = emptyList()
)
private class PendingFile(val uri: Uri, val name: String, val size: Long, val stream: OutputStream) {
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    var done = 0L
}
private class LocalFileException(cause: Exception) : Exception(cause.message,cause)
class TransferEngine(private val app: Application) {
    private data class SharedContent(val text: String, val files: List<Uri>)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(TransferState())
    val state = mutable.asStateFlow()
    private val generation = AtomicLong()
    private val sendMutex = Mutex()
    @Volatile private var socket: Socket? = null
    @Volatile private var wire: Wire? = null
    private var heartbeat: Job? = null
    private var sendJob: Job? = null
    @Volatile private var sendDisconnectReason: Pair<Long,String>? = null
    private val resolver get() = app.contentResolver
    private val sharedQueue = SharedSendQueue<SharedContent>(scope,
        awaitReady = { state.first { it.connected && !it.sending } },
        start = { share -> sendTask(quiet = true) { channel ->
            if(share.files.isNotEmpty()) sendFilesData(channel, share.files)
            else { channel.send(2, textBytes(share.text)); report("分享文本已发送 · 等待 Windows 确认") }
        } })
    init { scope.launch { sharedQueue.pending.collect { count -> mutable.update { it.copy(pendingShares = count) } } } }

    fun enqueueShare(text: String, files: List<Uri>) {
        val contentFiles = files.filter { it.scheme == "content" }.distinct()
        if(contentFiles.isEmpty()) {
            if(text.isEmpty()) { report("分享中没有可发送的文件或文本"); return }
            try { textBytes(text) } catch(e: Exception) { report(e.message ?: "分享文本无效"); return }
        }
        sharedQueue.enqueue(SharedContent(if(contentFiles.isEmpty()) text else "", contentFiles))
        if(!state.value.connected) report("分享已加入队列，连接电脑后自动发送")
    }

    fun connect(address: String, code: String) {
        val pairing = try { Pairing.parse(address, code) } catch(e: Exception) { report(e.message ?: "地址无效"); return }
        disconnect(false)
        val id = generation.incrementAndGet()
        sendDisconnectReason = null
        mutable.update { it.copy(connecting = true, status = "正在连接 ${pairing.host}…", progress = null) }
        app.getSharedPreferences("pairing", 0).edit().putString("address", "${pairing.host}:${pairing.port}").apply()
        try { app.startForegroundService(Intent(app, TransferService::class.java)) }
        catch(e: Exception) { mutable.update { it.copy(connecting = false, status = "无法启动传输服务：${e.message}") }; return }
        scope.launch {
            var local: Socket? = null
            var channel: Wire? = null
            try {
                local = Socket()
                synchronized(this@TransferEngine) { if(generation.get() != id) { local.close(); return@launch }; socket = local }
                local.connect(InetSocketAddress(pairing.host, pairing.port), 8000)
                local.soTimeout = 10000; local.tcpNoDelay = true
                channel = Wire(local, pairing.code)
                channel.send(1, "${Build.MANUFACTURER} ${Build.MODEL}".toByteArray(Charsets.UTF_8))
                val (type, data) = channel.receive()
                require(type == 1 && data.size <= 256) { "电脑握手失败" }
                local.soTimeout = 45000
                synchronized(this@TransferEngine) { if(generation.get() != id) { channel.close(); return@launch }; wire = channel }
                mutable.update { it.copy(connected = true, connecting = false, peer = pairing.host, status = "已连接 Windows · 可以开始互传") }
                val active = channel
                heartbeat = scope.launch { while(isActive && generation.get() == id) { delay(15000); try { active.send(7) } catch(_: Exception) { active.close(); break } } }
                receive(active)
            } catch(e: Exception) {
                if(generation.get() == id) {
                    android.util.Log.e("LanBridge","Connection ended",e)
                    report(sendDisconnectReason?.takeIf { it.first == id }?.second ?: "连接结束：${friendly(e)}")
                }
            } finally {
                channel?.close(); local?.close()
                if(generation.get() == id) {
                    heartbeat?.cancel(); wire = null; socket = null
                    mutable.update { it.copy(connected = false, connecting = false, sending = false, progress = null) }
                    app.stopService(Intent(app, TransferService::class.java))
                }
            }
        }
    }
    fun disconnect(stopService: Boolean = true) {
        synchronized(this) { generation.incrementAndGet(); heartbeat?.cancel(); sendJob?.cancel(); wire?.close(); wire = null; socket?.close(); socket = null }
        mutable.update { it.copy(connected = false, connecting = false, sending = false, progress = null, status = if(stopService || it.connected || it.connecting) "已断开连接" else it.status) }
        if(stopService) app.stopService(Intent(app, TransferService::class.java))
    }
    fun savedAddress(): String = app.getSharedPreferences("pairing", 0).getString("address", "") ?: ""
    fun report(message: String) { mutable.update { it.copy(status = message) } }
    private fun friendly(e: Exception): String = when(e) {
        is LocalFileException -> friendly(e.cause as Exception)
        is SecurityException -> "没有文件读取权限，请重新从来源应用分享"
        is java.io.FileNotFoundException -> "文件已不可读取，请重新选择或分享文件"
        is javax.crypto.AEADBadTagException -> "配对码不正确或加密数据受损"
        is java.net.SocketTimeoutException -> "连接超时，请检查同一 Wi-Fi 和 Windows 防火墙"
        is java.net.ConnectException -> "无法连接，请确认电脑程序已启动、地址正确且防火墙允许"
        is java.io.EOFException -> "电脑已断开连接"
        else -> e.message ?: "网络错误"
    }
    private fun progress(label: String, current: Long, total: Long) {
        mutable.update { it.copy(progress = if(total == 0L) 1f else (current.toFloat()/total).coerceIn(0f,1f), progressLabel = "$label · ${formatSize(current)} / ${formatSize(total)}") }
    }
    private fun receive(channel: Wire) {
        var pending: PendingFile? = null
        try {
            while(true) {
                val (type, data) = channel.receive()
                when(type) {
                    2 -> {
                        require(data.isNotEmpty()) { "文本为空" }
                        val text = decodeUtf8(data); require(!text.contains('\u0000'))
                        mutable.update { it.copy(status = "收到 Windows 文本", received = (listOf(Received("文本", text = text)) + it.received).take(80)) }
                        channel.send(6, "Android 已接收文本".toByteArray())
                    }
                    3 -> {
                        require(pending == null && data.size >= 10)
                        val buffer = ByteBuffer.wrap(data); val n = buffer.short.toInt() and 65535
                        require(n > 0 && data.size == n+10)
                        val rawName = ByteArray(n).also(buffer::get); val name = safeName(decodeUtf8(rawName))
                        val size = buffer.long; require(size in 0..MAX_FILE) { "文件超过 100 GiB" }
                        require(Environment.getExternalStorageDirectory().usableSpace >= size + 1024*1024) { "手机空间不足" }
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
                            put(MediaStore.Downloads.RELATIVE_PATH, "Download/LanBridge")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建接收文件")
                        val stream = try { resolver.openOutputStream(uri, "w") ?: error("无法打开文件") } catch(e: Exception) { resolver.delete(uri, null, null); throw e }
                        pending = PendingFile(uri, name, size, stream); progress("接收 $name", 0, size)
                    }
                    4 -> {
                        val file = pending ?: error("缺少文件头")
                        require(data.isNotEmpty() && data.size.toLong() <= file.size - file.done) { "无效文件数据" }
                        file.stream.write(data); file.digest.update(data); file.done += data.size
                        progress("接收 ${file.name}", file.done, file.size)
                    }
                    5 -> {
                        val file = pending ?: error("缺少文件头")
                        require(data.size == 32 && file.done == file.size && MessageDigest.isEqual(file.digest.digest(), data)) { "文件校验失败" }
                        file.stream.flush(); file.stream.close()
                        require(resolver.update(file.uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) { "文件保存失败" }
                        mutable.update { it.copy(status = "已保存到 下载/LanBridge：${file.name}", received = (listOf(Received(file.name, uri = file.uri, size = file.size)) + it.received).take(80)) }
                        progress("接收完成", file.size, file.size); pending = null
                        channel.send(6, "Android 已保存文件：${file.name}".toByteArray())
                    }
                    6 -> report(decodeUtf8(data))
                    7 -> channel.send(8)
                    8 -> Unit
                    else -> error("未知数据帧")
                }
            }
        } finally {
            pending?.let { try { it.stream.close() } finally { resolver.delete(it.uri, null, null) } }
        }
    }
    private fun sendTask(quiet: Boolean = false, task: suspend (Wire) -> Unit): Job? = synchronized(this) {
        if(!state.value.connected) { if(!quiet) report("请先连接电脑"); return@synchronized null }
        if(state.value.sending) { if(!quiet) report("当前发送尚未完成"); return@synchronized null }
        val channel = wire ?: return@synchronized null
        val id = generation.get()
        mutable.update { it.copy(sending = true) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            sendMutex.withLock {
                try { task(channel) }
                catch(e: Exception) {
                    if(generation.get() == id) {
                        android.util.Log.e("LanBridge","Send failed",e)
                        val message = "发送失败：${friendly(e)}"
                        report(message)
                        // A local provider error before the file header leaves
                        // the wire protocol intact; keep the connection usable.
                        if(e !is LocalFileException) { sendDisconnectReason = id to message; channel.close() }
                    }
                }
                finally { if(generation.get() == id) mutable.update { it.copy(sending = false) } }
            }
        }
        sendJob = job
        job.start()
        job
    }
    private fun textBytes(text: String): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        require(data.isNotEmpty() && data.size <= 262144 && !text.contains('\u0000')) { "文本需为 1–256 KiB，不能包含空字符" }
        return data
    }
    fun sendText(text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        if(data.isEmpty() || data.size > 262144 || text.contains('\u0000')) { report("文本需为 1–256 KiB，不能包含空字符"); return }
        sendTask { it.send(2, data); report("文本已发送 · 等待 Windows 确认") }
    }
    fun sendFiles(uris: List<Uri>) { sendTask { channel -> sendFilesData(channel, uris) } }
    private suspend fun sendFilesData(channel: Wire, uris: List<Uri>) {
        for(uri in uris) {
            var started = false
            try {
            var name = "共享文件"; var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if(cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if(nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if(sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
            name = safeName(name)
            var staged: File? = null
            try {
                if(size < 0) {
                    report("正在准备共享文件：$name")
                    staged = File.createTempFile("lanbridge-", ".part", app.cacheDir)
                    resolver.openInputStream(uri)?.use { input -> staged.outputStream().use { output ->
                        val buffer = ByteArray(262144); var total = 0L
                        while(true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if(n < 0) break; total += n; require(total <= MAX_FILE && app.cacheDir.usableSpace > n + 1024*1024) { "手机临时空间不足" }; output.write(buffer, 0, n) }
                    } } ?: error("无法读取共享文件")
                    size = staged.length()
                }
                require(size in 0..MAX_FILE) { "文件超过 100 GiB" }
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val header = ByteBuffer.allocate(nameBytes.size + 10).putShort(nameBytes.size.toShort()).put(nameBytes).putLong(size).array()
                val input = if(staged != null) staged.inputStream() else resolver.openInputStream(uri) ?: error("无法读取文件")
                input.use {
                    started = true
                    channel.send(3, header); progress("发送 $name", 0, size)
                    val digest = MessageDigest.getInstance("SHA-256"); var done = 0L; val buffer = ByteArray(262144)
                    while(done < size) {
                        currentCoroutineContext().ensureActive()
                        val n = it.read(buffer, 0, minOf(buffer.size.toLong(), size-done).toInt())
                        require(n > 0) { "文件长度发生变化" }
                        val chunk = buffer.copyOf(n); digest.update(chunk); channel.send(4, chunk); done += n
                        progress("发送 $name", done, size)
                    }
                    require(it.read() == -1) { "文件长度发生变化" }
                    channel.send(5, digest.digest()); progress("发送完成", size, size)
                }
            } finally { staged?.delete() }
            } catch(e: Exception) {
                if(!started && e !is CancellationException) throw LocalFileException(e)
                throw e
            }
        }
    }
    fun clearHistory() { mutable.update { it.copy(received = emptyList()) } }
    companion object {
        const val MAX_FILE = 100L*1024*1024*1024
        fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        fun safeName(input: String): String {
            var cleaned = input.map { if(it < ' ' || it in "<>:\"/\\|?*") '_' else it }.joinToString("").trim().trimEnd('.', ' ').take(170)
            if(cleaned.lastOrNull()?.isHighSurrogate() == true) cleaned = cleaned.dropLast(1)
            require(cleaned.isNotBlank() && cleaned != "." && cleaned != "..") { "文件名无效" }
            val base = cleaned.substringBefore('.').uppercase()
            return if(base.matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))) "_$cleaned" else cleaned
        }
    }
}
fun formatSize(n: Long): String = when {
    n >= 1024L*1024*1024 -> "%.1f GiB".format(n.toDouble()/(1024*1024*1024))
    n >= 1024L*1024 -> "%.1f MiB".format(n.toDouble()/(1024*1024))
    n >= 1024 -> "%.1f KiB".format(n.toDouble()/1024)
    else -> "$n B"
}
