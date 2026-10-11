@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.lanbridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val engine get() = (application as BridgeApplication).engine
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if(savedInstanceState == null) readShare(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if(dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialExpressiveTheme(colorScheme = colors) {
                BridgeScreen(engine)
            }
        }
        if(Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES))
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readShare(intent) }
    override fun onStart() { super.onStart(); engine.resumeRemembered() }
    private fun readShare(intent: Intent?) {
        if(intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        var sharedFiles = if(intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if(Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else @Suppress("DEPRECATION") (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty())
        } else {
            val uri = if(Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            listOfNotNull(uri)
        }
        // ClipData is used by some providers instead of EXTRA_STREAM.
        if(sharedFiles.isEmpty()) intent.clipData?.let { clip -> sharedFiles = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }
        sharedFiles = sharedFiles.filter { it.scheme == "content" }
        engine.enqueueShare(sharedText, sharedFiles)
    }
}

@Composable private fun BridgeScreen(engine: TransferEngine) {
    val state by engine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf(engine.savedAddress()) }
    // Pairing secrets stay in the engine/Keystore, outside saved activity state.
    var code by remember { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    val chooser = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if(uris.isNotEmpty()) engine.sendFiles(uris) }
    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode == android.app.Activity.RESULT_OK) {
            try {
                val value = requireNotNull(result.data?.getStringExtra(ScanActivity.RESULT_PAIRING))
                val pair = Pairing.fromQr(value); address = "${pair.host}:${pair.port}"; code = pair.code
                engine.connect(address, code)
                code = ""
            } catch(e: Exception) { engine.report(e.message ?: "配对二维码无效") }
        }
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("LanBridge", fontWeight = FontWeight.Bold) }, actions = {
            if(state.connected) TextButton(onClick = { engine.disconnect() }) { Text("断开") }
        }) },
        bottomBar = { Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if(state.progress != null) {
                    LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Text(state.progressLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                }
                Text(state.status, style = MaterialTheme.typography.bodySmall)
            }
        } }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val pairPanel: @Composable () -> Unit = {
                PairCard(state, address, { address = it }, code, { code = it },
                    scan = { scanner.launch(Intent(context, ScanActivity::class.java)) },
                    connect = { engine.connect(address, code); code = "" },
                    reconnect = { engine.resumeRemembered(force = true) },
                    forget = { engine.forgetPairing(); address = ""; code = "" },
                    paste = {
                        val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if(value.startsWith("lanbridge://")) {
                            try { val pair = Pairing.parse(value, ""); address = "${pair.host}:${pair.port}"; code = pair.code }
                            catch(e: Exception) { engine.report(e.message ?: "连接信息无效") }
                        } else engine.report("剪贴板中没有 LanBridge 连接信息")
                    })
            }
            val sendPanel: @Composable () -> Unit = {
                SendCard(state, text, { text = it },
                    send = { engine.sendText(text) },
                    paste = { text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty() },
                    choose = { chooser.launch(arrayOf("*/*")) })
            }
            if(maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Hero(state); pairPanel() }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item { sendPanel() }
                        item { ReceivedHeading(state, engine) }
                        itemsIndexed(state.received) { _, item -> ReceivedCard(item, engine) }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Hero(state) }
                    item { pairPanel() }
                    item { sendPanel() }
                    item { ReceivedHeading(state, engine) }
                    itemsIndexed(state.received) { _, item -> ReceivedCard(item, engine) }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable private fun Hero(state: TransferState) {
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if(state.connected) "已连接 ${state.peer}" else "Android ↔ Windows · 同一局域网", style = MaterialTheme.typography.bodyMedium)
            if(state.connecting) LoadingIndicator(modifier = Modifier.size(40.dp))
            else if(!state.connected) Text("○ 等待配对", style = MaterialTheme.typography.labelLarge)
        }
    }
}
@Composable private fun PairCard(state: TransferState, address: String, onAddress: (String) -> Unit, code: String, onCode: (String) -> Unit, scan: () -> Unit, connect: () -> Unit, reconnect: () -> Unit, forget: () -> Unit, paste: () -> Unit) {
    var manual by rememberSaveable { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    if(confirmForget) AlertDialog(onDismissRequest = { confirmForget = false }, title = { Text("解除绑定？") }, text = { Text("解除后，下次连接需要重新扫码。") }, confirmButton = { TextButton(onClick = { confirmForget = false; forget() }) { Text("解除绑定") } }, dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("取消") } })
    ElevatedCard(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("连接电脑", style = MaterialTheme.typography.titleLarge)
            Text(if(state.remembered) "已绑定电脑，打开应用或分享文件时会自动连接。" else "第一次扫描 Windows 二维码绑定，以后自动连接。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = if(state.remembered) reconnect else scan, enabled = !state.connecting && !state.connected, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text(if(state.connected) "已连接电脑" else if(state.connecting) "连接中…" else if(state.remembered) "重新连接已绑定电脑" else "扫码绑定 Windows") }
            if(state.remembered) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = scan, enabled = !state.connected && !state.connecting) { Text("扫描其他电脑") }
                TextButton(onClick = { confirmForget = true }, enabled = !state.sending) { Text("解除绑定") }
            }
            TextButton(onClick = { manual = !manual }, modifier = Modifier.fillMaxWidth()) { Text(if(manual) "收起手动连接" else "手动输入 / 粘贴连接信息") }
            if(manual) {
            OutlinedTextField(address, onAddress, label = { Text("电脑地址") }, placeholder = { Text("192.168.1.10") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.connecting && !state.connected, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(code, { onCode(it.uppercase().filter { c -> c.isDigit() || c in 'A'..'F' }.take(16)) }, label = { Text("16 位配对码") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.connecting && !state.connected, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            Button(onClick = connect, enabled = !state.connecting && !state.connected, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(if(state.connecting) "连接中…" else if(state.connected) "已连接" else "连接 Windows") }
            TextButton(onClick = paste, enabled = !state.connecting && !state.connected, modifier = Modifier.fillMaxWidth()) { Text("粘贴连接信息") }
            }
        }
    }
}
@Composable private fun SendCard(state: TransferState, text: String, onText: (String) -> Unit, send: () -> Unit, paste: () -> Unit, choose: () -> Unit) {
    val ready = state.connected && !state.sending
    ElevatedCard(shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("发送到 Windows", style = MaterialTheme.typography.titleLarge)
            Text("电脑收到后自动复制，直接粘贴就好。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(text, onText, label = { Text("写点什么…") }, modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp), minLines = 3, maxLines = 6)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = send, enabled = ready && text.isNotEmpty(), modifier = Modifier.weight(1f).height(52.dp)) { Text("发送文本") }
                FilledTonalButton(onClick = paste, modifier = Modifier.weight(1f).height(52.dp)) { Text("粘贴文本") }
            }
            FilledTonalButton(onClick = choose, enabled = ready, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(if(state.sending) "正在发送…" else "选择文件 · 支持多选") }
            if(state.pendingShares > 0) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(if(!state.connected) "有 ${state.pendingShares} 份分享等待发送，连接电脑后会自动发送。" else "有 ${state.pendingShares} 份分享等待发送，当前传输结束后会自动发送。")
                    }
                }
            }
        }
    }
}
@Composable private fun ReceivedHeading(state: TransferState, engine: TransferEngine) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最近收到", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
            if(state.received.isNotEmpty()) TextButton(onClick = { engine.clearHistory() }) { Text("清空记录") }
        }
        Text(if(state.received.isEmpty()) "还没有收到内容。接收的文件会保存到 下载/LanBridge。" else "文件已保存到 下载/LanBridge；清空记录不会删除文件。", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun ReceivedCard(item: Received, engine: TransferEngine) {
    val context = LocalContext.current
    OutlinedCard(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if(item.text != null) "收到文本" else item.name, style = MaterialTheme.typography.titleMedium)
            if(item.text != null) {
                androidx.compose.foundation.text.selection.SelectionContainer { Text(item.text.take(2000), style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LanBridge", item.text))
                    engine.report("已复制文本")
                }) { Text("复制全文") }
            } else {
                Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, context.contentResolver.getType(item.uri!!) ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        catch(_: Exception) { engine.report("没有可打开此文件的应用，可使用分享或系统文件管理器") }
                    }) { Text("打开") }
                    TextButton(onClick = {
                        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享文件")) }
                        catch(_: Exception) { engine.report("无法分享，请在系统文件管理器中打开 下载/LanBridge") }
                    }) { Text("分享") }
                }
            }
        }
    }
}
