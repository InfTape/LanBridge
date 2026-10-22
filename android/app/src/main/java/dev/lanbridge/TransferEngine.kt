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
import java.util.UUID

class BridgeApplication : Application() { val engine by lazy { TransferEngine(this) } }
data class Received(val name: String, val text: String? = null, val uri: Uri? = null, val size: Long = 0, val directory: Boolean = false, val id: String = UUID.randomUUID().toString(), val receivedAt: Long = System.currentTimeMillis(), val highlight: Boolean = true)
data class TransferState(
    val connected: Boolean = false, val connecting: Boolean = false, val remembered: Boolean = false,
    val sending: Boolean = false, val pendingShares: Int = 0, val status: String = "准备连接电脑",
    val peer: String = "", val progress: Float? = null, val progressLabel: String = "",
    val received: List<Received> = emptyList(), val historyError: String? = null, val historyLoading: Boolean = true, val outgoing: SendProgress? = null, val incoming: ReceiveProgress? = null,
    val deviceName: String = "", val deviceRevision: Long = 0,
    val computers: List<BoundComputer> = emptyList(), val selectedComputerId: String? = null, val defaultComputerId: String? = null
) {
    val deviceSwitchBlocked: Boolean get() = DeviceSwitchGuard.reason(sending, incoming?.active == true, pendingShares) != null
}
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
    private val historyLock = Any()
    private val history = ReceivedHistory(File(app.noBackupFilesDir, "received-history/history.bin"))
    private val generation = AtomicLong()
    private val sendMutex = Mutex()
    private val sendReceipts = SendReceiptGate()
    @Volatile private var socket: Socket? = null
    @Volatile private var wire: Wire? = null
    private var heartbeat: Job? = null
    private var sendJob: Job? = null
    private var connectionJob: Job? = null
    private data class ConnectionRequest(val id: Long, val pairing: Pairing, val automatic: Boolean)
    private val serviceStarts = ForegroundStartGate()
    private var pendingConnection: ConnectionRequest? = null
    private var foregroundService: TransferService? = null
    private var foregroundStartId = 0
    private val savedPairing = RememberedPairing(app)
    private val discovery = PairingDiscovery(app)
    private val folders = AndroidFolders(app)
    @Volatile private var peerFolders = false
    private data class FolderWait(val id: Long, val phase: Int, val result: CompletableDeferred<FolderProtocol.Reply>)
    @Volatile private var folderWait: FolderWait? = null
    private var bindings = runCatching { savedPairing.startupBindings() }.getOrDefault(ComputerBindings())
    @Volatile private var trustedPairing = bindings.current?.pairing
    @Volatile private var paused = bindings.defaultId == null
    @Volatile private var sendDisconnectReason: Pair<Long,String>? = null
    private val resolver get() = app.contentResolver
    private val sharedQueue = SharedSendQueue<SharedContent>(scope,
        awaitReady = { state.first { it.connected && !it.sending } },
        start = { share -> sendTask(quiet = true) { channel ->
            if(share.files.isNotEmpty()) sendFilesData(channel, share.files)
            else sendTextData(channel, textBytes(share.text))
        } })
    init {
        showBindings(bindings)
        scope.launch { sharedQueue.pending.collect { count -> mutable.update { it.copy(pendingShares = count) } } }
        scope.launch {
            while(isActive) {
                val wait = synchronized(historyLock) {
                    val snapshot = history.refresh()
                    publishHistory(snapshot)
                    // Wake at the oldest receipt's exact expiry; also recheck wall-clock changes.
                    val expires = snapshot.records.minOfOrNull { it.receivedAt + RECEIVED_HISTORY_TTL }
                    if(expires == null) 60_000L else (expires - System.currentTimeMillis()).coerceIn(1L, 60_000L)
                }
                delay(wait)
            }
        }
    }

    @Synchronized fun enqueueShare(text: String, files: List<Uri>) {
        val contentFiles = files.filter { it.scheme == "content" }.distinct()
        if(contentFiles.isEmpty()) {
            if(text.isEmpty()) { report("分享中没有可发送的文件或文本"); return }
            try { textBytes(text) } catch(e: Exception) { report(e.message ?: "分享文本无效"); return }
        }
        sharedQueue.enqueue(SharedContent(if(contentFiles.isEmpty()) text else "", contentFiles))
        if(!state.value.connected) report("分享已加入队列，连接电脑后自动发送")
        resumeRemembered(force = true)
    }

    fun connect(address: String, code: String) {
        val pairing = try { Pairing.parse(address, code) } catch(e: Exception) { report(e.message ?: "地址无效"); return }
        connectPair(pairing, automatic = false)
    }
    @Synchronized fun resumeRemembered(force: Boolean = false) {
        if(state.value.connected || state.value.connecting || (paused && !force)) return
        if(!force) {
            val default = bindings.defaultId ?: return
            if(bindings.selectedId != default) {
                try { showBindings(savedPairing.select(default)) }
                catch(e: Exception) { report(e.message ?: "无法读取默认连接设备"); return }
            }
        }
        trustedPairing?.let { connectPair(it, automatic = true) }
    }
    private fun showBindings(book: ComputerBindings) {
        bindings = book; trustedPairing = book.current?.pairing
        mutable.update { it.copy(remembered = book.current != null,
            computers = book.computers.map { computer -> computer.display() }, selectedComputerId = book.selectedId, defaultComputerId = book.defaultId,
            deviceName = book.current?.name.orEmpty(), peer = book.current?.pairing?.host.orEmpty(),
            deviceRevision = if(it.selectedComputerId != book.selectedId) it.deviceRevision + 1 else it.deviceRevision) }
    }
    private fun deviceSwitchError(): String? = DeviceSwitchGuard.reason(state.value.sending, state.value.incoming?.active == true, sharedQueue.pending.value)
    @Synchronized fun switchComputer(id: String): String? {
        if(id == bindings.selectedId) return null
        deviceSwitchError()?.let { return it }
        return try {
            val updated = savedPairing.select(id)
            disconnect(false)
            showBindings(updated)
            trustedPairing?.let { connectPair(it, automatic = true) }
            null
        } catch(e: Exception) { e.message ?: "无法切换电脑" }
    }
    @Synchronized fun deviceConnectionAction(id: String, stop: Boolean): String? {
        if(stop) {
            if(id != bindings.selectedId) return "设备已切换，请重新打开设备设置"
            disconnect()
            return null
        }
        if(id != bindings.selectedId) return switchComputer(id)
        resumeRemembered(force = true)
        return null
    }
    @Synchronized fun forgetPairing(expectedRevision: Long): String? {
        if(state.value.deviceRevision != expectedRevision) return "设备已切换，请重新打开解除绑定"
        val id = bindings.selectedId ?: return "请先绑定电脑"
        return removeComputers(setOf(id))
    }
    @Synchronized fun removeComputers(ids: Set<String>): String? {
        val removesCurrent = bindings.selectedId in ids
        if(removesCurrent) deviceSwitchError()?.let { return it }
        return try {
            val updated = savedPairing.remove(ids)
            if(removesCurrent) disconnect()
            showBindings(updated)
            if(removesCurrent) app.getSharedPreferences("pairing", 0).edit().remove("address").apply()
            report(if(updated.current == null) "已解除绑定，首次连接请扫码" else "已移除此电脑，其他绑定已保留")
            null
        } catch(e: Exception) { e.message ?: "无法解除绑定" }
    }
    @Synchronized fun renameDevice(value: String, expectedRevision: Long): String? {
        if(state.value.deviceRevision != expectedRevision) return "设备已切换，请重新打开重命名"
        if(!state.value.remembered || trustedPairing == null) return "请先绑定电脑"
        return renameComputer(requireNotNull(bindings.selectedId), value)
    }
    @Synchronized fun renameComputer(id: String, value: String): String? = try {
            showBindings(savedPairing.renameComputer(id, value))
            null
        } catch(e: Exception) { e.message ?: "无法保存设备名称" }
    @Synchronized fun setDefaultComputer(id: String?): String? = try {
        showBindings(savedPairing.setDefault(id))
        null
    } catch(e: Exception) { e.message ?: "无法保存默认连接设备" }
    @Synchronized private fun connectPair(initial: Pairing, automatic: Boolean) {
        if((trustedPairing != null && initial.code != trustedPairing?.code) || state.value.connected) {
            deviceSwitchError()?.let { report(it); return }
        }
        disconnect(false)
        paused = false
        val id = generation.incrementAndGet()
        sendDisconnectReason = null
        mutable.update { it.copy(connecting = true, status = if(automatic) "正在寻找已绑定电脑…" else "正在连接 ${initial.host}…", progress = null) }
        pendingConnection = ConnectionRequest(id, initial, automatic)
        serviceStarts.request(id)
        try { app.startForegroundService(Intent(app, TransferService::class.java).putExtra("generation", id)) }
        catch(e: Exception) {
            serviceStarts.cancel(); serviceStarts.arrive(id); pendingConnection = null
            stopReadyService()
            mutable.update { it.copy(connecting = false, status = "无法启动传输服务：${e.message}") }
        }
    }
    @Synchronized internal fun serviceReady(service: TransferService, startId: Int, id: Long) {
        foregroundService = service; foregroundStartId = startId
        when(serviceStarts.arrive(id)) {
            ForegroundStartGate.Arrival.START -> {
                val request = pendingConnection?.takeIf { it.id == id } ?: return
                pendingConnection = null
                launchConnection(request)
            }
            ForegroundStartGate.Arrival.STOP -> stopReadyService()
            ForegroundStartGate.Arrival.WAIT -> Unit
        }
    }
    private fun stopReadyService() {
        if(serviceStarts.canStop) foregroundService?.stopSelfResult(foregroundStartId)
    }
    @Synchronized internal fun serviceDestroyed(service: TransferService) {
        if(foregroundService !== service) return
        foregroundService = null
        if(!serviceStarts.hasPending) disconnect(false)
    }
    private fun launchConnection(request: ConnectionRequest) {
        val (id, initial, automatic) = request
        connectionJob = scope.launch {
            var pairing = initial
            var mayRetry = automatic
            var failures = 0
            while(isActive && generation.get() == id) {
                var local: Socket? = null
                var channel: Wire? = null
                var authenticationFailed = false
                try {
                    if(mayRetry) runCatching { discovery.find(pairing) }.getOrNull()?.let { pairing = it }
                    ensureActive()
                    local = discovery.tcpSocket()
                    synchronized(this@TransferEngine) { if(generation.get() != id) { local.close(); return@launch }; socket = local }
                    local.connect(InetSocketAddress(pairing.host, pairing.port), 3000)
                    local.soTimeout = 10000; local.tcpNoDelay = true
                    channel = Wire(local, pairing.code)
                    channel.send(1, "${Build.MANUFACTURER} ${Build.MODEL}\n${FolderProtocol.CAPABILITY}".toByteArray(Charsets.UTF_8))
                    val (type, data) = channel.receive()
                    require(type == 1 && data.size <= 256) { "电脑握手失败" }
                    val supportsFolders = decodeUtf8(data).endsWith("\n${FolderProtocol.CAPABILITY}")
                    local.soTimeout = 45000
                    val active = channel
                    // Only remember credentials after the encrypted hello proves the computer knows them.
                    synchronized(this@TransferEngine) {
                        if(generation.get() != id) { channel.close(); return@launch }
                        showBindings(savedPairing.save(pairing))
                        wire = channel; peerFolders = supportsFolders
                        app.getSharedPreferences("pairing", 0).edit().putString("address", "${pairing.host}:${pairing.port}").apply()
                        mayRetry = true; failures = 0
                        mutable.update { it.copy(connected = true, connecting = false, peer = pairing.host, status = "已连接 Windows · 已记住绑定") }
                        heartbeat = scope.launch { while(isActive && generation.get() == id) { delay(15000); try { active.send(7) } catch(_: Exception) { active.close(); break } } }
                    }
                    receive(active)
                } catch(e: Exception) {
                    synchronized(this@TransferEngine) { if(generation.get() == id) {
                        android.util.Log.e("LanBridge","Connection ended",e)
                        authenticationFailed = e is javax.crypto.AEADBadTagException
                        if(authenticationFailed && automatic) {
                            report("电脑配对信息已更改，请解除绑定后重新扫码")
                        } else {
                            report(sendDisconnectReason?.takeIf { it.first == id }?.second ?: "连接结束：${friendly(e)}")
                        }
                    } }
                } finally {
                    channel?.close(); local?.close()
                    val finishedSend = synchronized(this@TransferEngine) { if(generation.get() == id) {
                        heartbeat?.cancel(); wire = null; socket = null; peerFolders = false
                        folderWait?.result?.cancel(); folderWait = null
                        failActiveSend("连接已中断，请检查设备连接")
                        sendReceipts.cancel()
                        val finished = sendJob; finished?.cancel()
                        mutable.update { it.copy(connected = false, connecting = false, sending = false, progress = null) }
                        finished
                    } else null }
                    finishedSend?.join()
                }
                if(!mayRetry || authenticationFailed || generation.get() != id || !isActive) break
                failures++
                if(failures >= 30) { synchronized(this@TransferEngine) { if(generation.get() == id) report("暂未找到已绑定电脑，打开电脑后点重新连接即可") }; break }
                synchronized(this@TransferEngine) { if(generation.get() == id) mutable.update { it.copy(connecting = true, status = "等待已绑定电脑 · 自动重连中…") } }
                delay(minOf(1000L * failures, 10000L))
            }
            withContext(Dispatchers.Main) {
                synchronized(this@TransferEngine) {
                    if(generation.get() == id) {
                        mutable.update { it.copy(connecting = false) }
                        if(serviceStarts.complete(id)) stopReadyService()
                    }
                }
            }
        }
    }
    @Synchronized fun disconnect(stopService: Boolean = true) {
        synchronized(this) { generation.incrementAndGet(); interruptReceive(); failActiveSend("连接已断开，请重新连接后发送"); sendReceipts.cancel(); serviceStarts.cancel(); pendingConnection = null; if(stopService) paused = true; connectionJob?.cancel(); heartbeat?.cancel(); sendJob?.cancel(); folderWait?.result?.cancel(); folderWait = null; peerFolders = false; wire?.close(); wire = null; socket?.close(); socket = null }
        mutable.update { it.copy(connected = false, connecting = false, sending = false, progress = null, status = if(stopService || it.connected || it.connecting) "已断开连接" else it.status) }
        if(stopService) scope.launch(Dispatchers.Main.immediate) { synchronized(this@TransferEngine) { stopReadyService() } }
    }
    fun savedAddress(): String = trustedPairing?.let { "${it.host}:${it.port}" } ?: app.getSharedPreferences("pairing", 0).getString("address", "").orEmpty()
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
    private fun updateSend(channel: Wire, update: (SendProgress) -> SendProgress) = synchronized(this) {
        if(wire === channel) mutable.update { state -> state.copy(outgoing = state.outgoing?.let { if(it.active) update(it) else it }) }
    }
    private fun prepareSend(channel: Wire, name: String, kind: SendKind, index: Int = 1, count: Int = 1) {
        updateSend(channel) { it.copy(
            phase = SendPhase.PREPARING, name = name, kind = kind, current = 0, total = null, itemIndex = index, itemCount = count
        ) }
    }
    private fun sendProgress(channel: Wire, current: Long, total: Long) {
        updateSend(channel) { it.copy(
            phase = SendPhase.SENDING, current = current, total = total
        ) }
    }
    private fun confirmingSend(channel: Wire) {
        updateSend(channel) { it.copy(phase = SendPhase.CONFIRMING) }
    }
    private fun failActiveSend(message: String) {
        mutable.update { state -> state.copy(outgoing = state.outgoing?.takeIf { it.active }?.copy(phase = SendPhase.FAILED, error = message) ?: state.outgoing) }
    }
    private fun beginReceive(channel: Wire, name: String, size: Long, directory: Boolean = false): String {
        val destination = if(directory) folders.destinationName() ?: "已授权目录" else "下载/LanBridge"
        return synchronized(this) {
            if(wire !== channel) throw CancellationException("连接已断开")
            val receipt = ReceiveProgress(name, size, destination, directory)
            mutable.update { it.copy(incoming = receipt) }
            receipt.id
        }
    }
    private fun updateReceive(id: String?, update: (ReceiveProgress) -> ReceiveProgress) {
        if(id != null) mutable.update { state -> state.copy(incoming = state.incoming?.let { if(it.id == id) update(it) else it }) }
    }
    private fun savingReceive(id: String?) = synchronized(this) {
        // Fence publication against a user disconnect. Once saving starts, its
        // actual storage result decides success; a dropped receipt cannot undo it.
        check(id != null)
        updateReceive(id) {
            if(!it.active) throw CancellationException("接收已停止")
            it.saving()
        }
    }
    private fun failReceive(id: String?, summary: String, detail: String) = updateReceive(id) { it.failed(summary, detail) }
    private fun interruptReceive() {
        mutable.update { state -> state.copy(incoming = state.incoming?.let {
            if(it.phase == ReceivePhase.RECEIVING) it.failed("连接中断，文件未完整保存", "连接已断开，接收未完成。请重新连接后让电脑再次发送。") else it
        }) }
    }
    private fun receive(channel: Wire) {
        var pending: PendingFile? = null
        var folder: FolderReceiver? = null
        var receipt: String? = null
        try {
            while(true) {
                val (type, data) = channel.receive()
                if(type == 14 || type == 15) {
                    val reply = FolderProtocol.reply(data)
                    folderWait?.takeIf { it.id == reply.id && it.phase == type }?.result?.complete(reply)
                    continue
                }
                if(type == 9) {
                    require(pending == null && folder == null)
                    val header = FolderProtocol.decode(data)
                    receipt = beginReceive(channel, header.name, header.bytes, directory = true)
                    try { folder = folders.receiver(header) }
                    catch(e: Exception) { failReceive(receipt, "无法接收文件夹", friendly(e)); receipt = null; channel.send(14,FolderProtocol.reply(header.id,false,(e.message ?: "无法接收文件夹").take(500))); report(e.message ?: "无法接收文件夹"); continue }
                    channel.send(14,FolderProtocol.reply(header.id,true)); progress("接收文件夹 ${header.name}",0,header.bytes); continue
                }
                if(type == 13) {
                    val active = requireNotNull(folder); require(data.size == 8 && FolderProtocol.id(data) == active.header.id)
                    failReceive(receipt, "电脑已取消发送", "文件夹传输已取消，未保留不完整目录。"); receipt = null
                    active.close(); folder = null; channel.send(15,FolderProtocol.reply(active.header.id,false,"文件夹发送已取消")); report("文件夹传输已取消，未保留不完整目录"); continue
                }
                if(type == 12) {
                    val active = requireNotNull(folder); require(data.size == 8 && FolderProtocol.id(data) == active.header.id)
                    try {
                        savingReceive(receipt)
                        val uri = Uri.parse(active.commit()); active.close(); folder = null
                        val name = runCatching { folders.savedName(uri) }.getOrDefault(active.header.name)
                        recordReceived(Received(name, uri = uri, size = active.header.bytes, directory = true), "已保存文件夹：$name")
                        updateReceive(receipt) { it.saved(uri.toString(), name) }; receipt = null
                        progress("文件夹接收完成",active.header.bytes,active.header.bytes); channel.send(15,FolderProtocol.reply(active.header.id,true))
                    } catch(e: Exception) {
                        failReceive(receipt, "文件夹保存失败", friendly(e)); receipt = null
                        active.close(); folder = null; channel.send(15,FolderProtocol.reply(active.header.id,false,(e.message ?: "保存失败").take(500))); report("文件夹保存失败：${e.message}")
                    }
                    continue
                }
                if(type == 10 || type == 11 || (folder != null && (type == 4 || type == 5))) {
                    val active = requireNotNull(folder); active.consume(type,data)
                    updateReceive(receipt) { it.advance(active.done) }
                    progress("接收文件夹 ${active.header.name}",active.done,active.header.bytes); continue
                }
                when(type) {
                    2 -> {
                        require(data.isNotEmpty()) { "文本为空" }
                        val text = decodeUtf8(data); require(!text.contains('\u0000'))
                        recordReceived(Received("文本", text = text), "收到 Windows 文本")
                        channel.send(6, "Android 已接收文本".toByteArray())
                    }
                    3 -> {
                        require(pending == null && folder == null && data.size >= 10)
                        val buffer = ByteBuffer.wrap(data); val n = buffer.short.toInt() and 65535
                        require(n > 0 && data.size == n+10)
                        val rawName = ByteArray(n).also(buffer::get); val name = safeName(decodeUtf8(rawName))
                        val size = buffer.long; require(size in 0..MAX_FILE) { "文件超过 100 GiB" }
                        receipt = beginReceive(channel, name, size)
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
                        updateReceive(receipt) { it.advance(file.done) }
                        progress("接收 ${file.name}", file.done, file.size)
                    }
                    5 -> {
                        val file = pending ?: error("缺少文件头")
                        require(data.size == 32 && file.done == file.size && MessageDigest.isEqual(file.digest.digest(), data)) { "文件校验失败" }
                        savingReceive(receipt)
                        file.stream.flush(); file.stream.close()
                        require(resolver.update(file.uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) { "文件保存失败" }
                        recordReceived(Received(file.name, uri = file.uri, size = file.size), "已保存到 下载/LanBridge：${file.name}")
                        updateReceive(receipt) { it.saved(file.uri.toString()) }; receipt = null
                        progress("接收完成", file.size, file.size); pending = null
                        channel.send(6, "Android 已保存文件：${file.name}".toByteArray())
                    }
                    6 -> { val message = decodeUtf8(data); sendReceipts.confirm(channel); report(message) }
                    7 -> channel.send(8)
                    8 -> Unit
                    else -> error("未知数据帧")
                }
            }
        } catch(e: Exception) {
            val summary = when(e) {
                is java.io.EOFException, is java.net.SocketException, is CancellationException -> "连接中断，文件未完整保存"
                else -> friendly(e)
            }
            failReceive(receipt, summary, friendly(e))
            throw e
        } finally {
            folder?.close()
            pending?.let { try { it.stream.close() } finally { resolver.delete(it.uri, null, null) } }
        }
    }
    private fun sendTask(quiet: Boolean = false, task: suspend (Wire) -> Unit): Job? = synchronized(this) {
        if(!state.value.connected) { if(!quiet) report("请先连接电脑"); return@synchronized null }
        if(state.value.sending) { if(!quiet) report("当前发送尚未完成"); return@synchronized null }
        val channel = wire ?: return@synchronized null
        val id = generation.get()
        mutable.update { it.copy(sending = true, outgoing = SendProgress()) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            sendMutex.withLock {
                try {
                    task(channel)
                    currentCoroutineContext().ensureActive()
                    if(generation.get() == id) mutable.update { state -> state.copy(
                        outgoing = state.outgoing?.takeIf { it.active }?.copy(phase = SendPhase.SUCCEEDED) ?: state.outgoing
                    ) }
                }
                catch(e: Exception) {
                    if(generation.get() == id) {
                        val reason = when(e) {
                            is TimeoutCancellationException -> "等待电脑确认超时，请检查设备连接"
                            is CancellationException -> "连接已中断，请检查设备连接"
                            is java.net.SocketException, is java.io.EOFException -> "连接已中断，请检查设备连接"
                            else -> friendly(e)
                        }
                        failActiveSend(reason)
                        if(e !is CancellationException) android.util.Log.e("LanBridge","Send failed",e)
                        val message = "发送失败：$reason"
                        report(message)
                        // A local provider error before the file header leaves
                        // the wire protocol intact; keep the connection usable.
                        if(e !is LocalFileException) {
                            sendDisconnectReason = id to message
                            // Block the next queued share before this sender releases
                            // its busy flag; the receive loop will reconnect normally.
                            mutable.update { it.copy(connected = false) }
                            channel.close()
                        }
                    }
                    if(e is CancellationException) throw e
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
        sendTask { sendTextData(it, data) }
    }
    private suspend fun sendTextData(channel: Wire, data: ByteArray) {
        prepareSend(channel, "文本", SendKind.TEXT)
        sendProgress(channel, 0, data.size.toLong())
        sendReceipts.send(channel, write = {
            channel.send(2, data)
            sendProgress(channel, data.size.toLong(), data.size.toLong())
        }, onWritten = { confirmingSend(channel) })
    }
    fun sendFiles(uris: List<Uri>) { sendTask { channel -> sendFilesData(channel, uris) } }
    fun sendFolder(uri: Uri) { sendTask { channel -> sendFolderData(channel,uri) } }
    fun folderDestination(): Boolean = folders.destination() != null
    fun folderDestinationName(): String? = folders.destinationName()
    fun setFolderDestination(uri: Uri) {
        try { folders.setDestination(uri); report("文件夹接收位置已保存，下次无需再次授权") }
        catch(e: Exception) { report("无法保存接收位置：${e.message}") }
    }
    private suspend fun awaitFolderReply(wait: FolderWait) {
        val reply = withTimeout(30000) { wait.result.await() }
        if(!reply.accepted) throw LocalFileException(Exception(reply.reason.ifEmpty { "对端拒绝文件夹" }))
    }
    private suspend fun sendFolderData(channel: Wire, uri: Uri, index: Int = 1, count: Int = 1) {
        prepareSend(channel, "文件夹", SendKind.FOLDER, index, count)
        if(!peerFolders) throw LocalFileException(Exception("请先更新 Windows 端至支持文件夹的版本"))
        val plan = try { report("正在读取文件夹…"); folders.collect(uri) } catch(e: Exception) { if(e is CancellationException) throw e; throw LocalFileException(e) }
        plan.use {
            prepareSend(channel, plan.name, SendKind.FOLDER, index, count)
            // Windows emits both the folder commit reply and a normal receipt.
            // Consume both before the next item so a late folder receipt cannot
            // confirm a subsequent file in a mixed selection.
            sendReceipts.send(channel, write = {
            val id = java.security.SecureRandom().nextLong().let { if(it == 0L) 1L else it }
            val header = FolderProtocol.Header(id,plan.name,plan.entries.count { !it.directory },plan.entries.count { it.directory },plan.bytes)
            val ready = FolderWait(id,14,CompletableDeferred()); folderWait = ready
            try {
                channel.send(9,FolderProtocol.encode(header)); awaitFolderReply(ready)
                var done = 0L; sendProgress(channel,done,plan.bytes)
                try {
                    for(entry in plan.entries) {
                        currentCoroutineContext().ensureActive()
                        if(entry.directory) { channel.send(10,entry.relative.toByteArray(Charsets.UTF_8)); continue }
                        val input = entry.cache?.inputStream() ?: resolver.openInputStream(entry.uri) ?: error("无法读取目录内文件")
                        input.use { stream ->
                            channel.send(11,FolderProtocol.fileHeader(entry.relative,entry.size))
                            val hash = MessageDigest.getInstance("SHA-256"); var fileDone = 0L; val buffer = ByteArray(262144)
                            while(fileDone < entry.size) {
                                currentCoroutineContext().ensureActive(); val n = stream.read(buffer,0,minOf(buffer.size.toLong(),entry.size-fileDone).toInt())
                                require(n > 0) { "目录内文件长度发生变化" }; val chunk = buffer.copyOf(n)
                                hash.update(chunk); channel.send(4,chunk); fileDone += n; done += n; sendProgress(channel,done,plan.bytes)
                            }
                            require(stream.read() == -1) { "目录内文件长度发生变化" }; channel.send(5,hash.digest())
                        }
                    }
                } catch(e: Exception) {
                    if(e is CancellationException) throw e
                    channel.send(13,FolderProtocol.id(id))
                    throw LocalFileException(e)
                }
                val complete = FolderWait(id,15,CompletableDeferred()); folderWait = complete
                channel.send(12,FolderProtocol.id(id)); confirmingSend(channel); awaitFolderReply(complete)
            } finally { if(folderWait?.id == id) folderWait = null }
            }, onWritten = { confirmingSend(channel) })
        }
    }
    private suspend fun sendFilesData(channel: Wire, uris: List<Uri>) {
        for((index, uri) in uris.withIndex()) {
            prepareSend(channel, "文件", SendKind.FILE, index + 1, uris.size)
            val directory = try { folders.isDirectory(uri) } catch(e: Exception) { throw LocalFileException(e) }
            if(directory) { sendFolderData(channel,uri,index + 1,uris.size); continue }
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
            prepareSend(channel, name, SendKind.FILE, index + 1, uris.size)
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
                    sendReceipts.send(channel, write = {
                    started = true
                    channel.send(3, header); sendProgress(channel,0, size)
                    val digest = MessageDigest.getInstance("SHA-256"); var done = 0L; val buffer = ByteArray(262144)
                    while(done < size) {
                        currentCoroutineContext().ensureActive()
                        val n = it.read(buffer, 0, minOf(buffer.size.toLong(), size-done).toInt())
                        require(n > 0) { "文件长度发生变化" }
                        val chunk = buffer.copyOf(n); digest.update(chunk); channel.send(4, chunk); done += n
                        sendProgress(channel,done, size)
                    }
                    require(it.read() == -1) { "文件长度发生变化" }
                    channel.send(5, digest.digest())
                    }, onWritten = { confirmingSend(channel) })
                }
            } finally { staged?.delete() }
            } catch(e: Exception) {
                if(!started && e !is CancellationException) throw LocalFileException(e)
                throw e
            }
        }
    }
    private fun publishHistory(snapshot: ReceivedHistorySnapshot, status: String? = null) {
        val receipts = snapshot.records.map { record ->
            Received(record.name, record.text, record.uri?.let(Uri::parse), record.size, record.directory, record.id, record.receivedAt, record.fresh)
        }
        mutable.update { it.copy(received = receipts, historyError = snapshot.error, historyLoading = false, status = status ?: it.status) }
    }
    private fun recordReceived(item: Received, status: String) = synchronized(historyLock) {
        val record = ReceivedRecord(item.name.ifBlank { if(item.directory) "文件夹" else "文件" }, item.receivedAt,
            item.text, item.uri?.toString(), item.size, item.directory, item.id)
        publishHistory(history.add(record), status)
    }
    fun refreshHistory() { scope.launch { synchronized(historyLock) { publishHistory(history.refresh()) } } }
    fun clearHistory() { scope.launch { synchronized(historyLock) { publishHistory(history.clear()) } } }
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
