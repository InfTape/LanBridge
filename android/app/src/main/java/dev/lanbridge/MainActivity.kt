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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val engine get() = (application as BridgeApplication).engine
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if(savedInstanceState == null) readShare(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = bridgeColorScheme(dark)
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
    // Only connection transitions reset the panel. Status/progress updates must
    // not close a panel the user has explicitly opened.
    var pairExpanded by rememberSaveable(state.connected) { mutableStateOf(!state.connected) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var topMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val connectionScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val chooser = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if(uris.isNotEmpty()) engine.sendFiles(uris) }
    val folderChooser = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if(uri != null) engine.sendFolder(uri) }
    var folderDestination by remember { mutableStateOf(engine.folderDestination()) }
    var folderLocation by remember { mutableStateOf<String?>(null) }
    var destinationRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(destinationRevision) {
        folderLocation = withContext(Dispatchers.IO) { engine.folderDestinationName() }
    }
    val receiveFolderChooser = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if(uri != null) { engine.setFolderDestination(uri); folderDestination = engine.folderDestination(); destinationRevision++ } }
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
    if(showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ListItem(
                supportingContent = { Text(if(state.connected) "已连接 ${state.peer}" else "配对、扫码与手动连接") },
                leadingContent = { AppIcon(R.drawable.ic_monitor) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.clickable {
                    showSettings = false; pairExpanded = true
                    scope.launch { listState.animateScrollToItem(0) }
                    scope.launch { connectionScroll.animateScrollTo(0) }
                }
            ) { Text("连接电脑") }
            ListItem(
                supportingContent = { Text(folderLocation ?: if(folderDestination) "已授权目录" else "尚未授权，点击选择") },
                leadingContent = { AppIcon(R.drawable.ic_folder) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.clickable { showSettings = false; receiveFolderChooser.launch(null) }
            ) { Text("文件夹接收位置") }
            Text("普通文件保存至 下载 / LanBridge；文件夹使用授权目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("LanBridge 1.5.0 · 同一局域网内加密传输", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(
            title = { Text("LanBridge", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) },
            modifier = Modifier.padding(horizontal = 8.dp),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = {
                IconButton(onClick = { showSettings = true }) { AppIcon(R.drawable.ic_settings, description = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box {
                    IconButton(onClick = { topMenu = true }) { AppIcon(R.drawable.ic_more, description = "更多选项", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                        DropdownMenuItem(text = { Text(if(state.connected) "断开连接" else if(state.remembered) "重新连接" else "扫码绑定") }, onClick = {
                            topMenu = false
                            if(state.connected) engine.disconnect() else if(state.remembered) engine.resumeRemembered(force = true) else scanner.launch(Intent(context, ScanActivity::class.java))
                        }, enabled = !state.connecting)
                        DropdownMenuItem(text = { Text("清空接收记录") }, enabled = state.received.isNotEmpty(), onClick = { topMenu = false; engine.clearHistory() })
                    }
                }
            }
        ) },
        bottomBar = { Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 6.dp)) {
                if(state.progress != null && state.progress!! < 1f) {
                    LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Text(state.progressLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                }
                Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    choose = { chooser.launch(arrayOf("*/*")) },
                    chooseFolder = { folderChooser.launch(null) })
            }
            val connectionPanel: @Composable () -> Unit = {
                ConnectionSection(state, pairExpanded, { pairExpanded = !pairExpanded }, pairPanel)
            }
            if(maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(connectionScroll)) { connectionPanel() }
                    LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { sendPanel() }
                        item { FolderDestination(folderDestination, folderLocation) { receiveFolderChooser.launch(null) } }
                        item { ReceivedHeading(state, engine) }
                        itemsIndexed(state.received, key = { _, item -> item.id }) { _, item -> ReceivedCard(item, engine) }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "connection") { connectionPanel() }
                    item { sendPanel() }
                    item { FolderDestination(folderDestination, folderLocation) { receiveFolderChooser.launch(null) } }
                    item { ReceivedHeading(state, engine) }
                    itemsIndexed(state.received, key = { _, item -> item.id }) { _, item -> ReceivedCard(item, engine) }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable private fun ConnectionSection(state: TransferState, expanded: Boolean, toggle: () -> Unit, content: @Composable () -> Unit) {
    Column {
        Hero(state, expanded, toggle)
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            // Include the gap in the animation so a collapsed panel leaves no blank space.
            Box(Modifier.padding(top = 16.dp)) { content() }
        }
    }
}
@Composable private fun Hero(state: TransferState, expanded: Boolean, toggle: () -> Unit) {
    val arrowRotation by animateFloatAsState(if(expanded) 180f else 0f, label = "Connection panel arrow")
    Surface(
        onClick = toggle,
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = if(expanded) "连接设置已展开" else "连接设置已收起" },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 70.dp).padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon(R.drawable.ic_monitor, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Windows 电脑", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(if(state.connected) state.peer else if(state.connecting) "正在连接…" else if(state.remembered) "已绑定，点击连接" else "点击扫码连接", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if(state.connected) {
                val green = if(isSystemInDarkTheme()) Color(0xFF67DD94) else Color(0xFF00A847)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AppIcon(R.drawable.ic_check_circle, modifier = Modifier.size(16.dp), tint = green)
                    Text("已连接", fontSize = 13.sp, color = green)
                }
            } else if(state.connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("未连接", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(expanded) AppIcon(R.drawable.ic_expand_more, modifier = Modifier.size(16.dp).rotate(arrowRotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun PairCard(state: TransferState, address: String, onAddress: (String) -> Unit, code: String, onCode: (String) -> Unit, scan: () -> Unit, connect: () -> Unit, reconnect: () -> Unit, forget: () -> Unit, paste: () -> Unit) {
    var manual by rememberSaveable { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    if(confirmForget) AlertDialog(onDismissRequest = { confirmForget = false }, title = { Text("解除绑定？") }, text = { Text("解除后，下次连接需要重新扫码。") }, confirmButton = { TextButton(onClick = { confirmForget = false; forget() }) { Text("解除绑定") } }, dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("取消") } })
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("连接电脑", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(if(state.remembered) "已绑定电脑，打开应用或分享文件时会自动连接。" else "第一次扫描 Windows 二维码绑定，以后自动连接。", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = if(state.remembered) reconnect else scan, enabled = !state.connecting && !state.connected, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if(state.connected) "已连接电脑" else if(state.connecting) "连接中…" else if(state.remembered) "重新连接已绑定电脑" else "扫码绑定 Windows") }
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
@Composable private fun SendCard(state: TransferState, text: String, onText: (String) -> Unit, send: () -> Unit, paste: () -> Unit, choose: () -> Unit, chooseFolder: () -> Unit) {
    val ready = state.connected && !state.sending
    val colors = MaterialTheme.colorScheme
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("发送", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
            BasicTextField(
                value = text, onValueChange = onText,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "输入或粘贴要发送的文本" },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary), minLines = 3, maxLines = 6,
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Box(Modifier.fillMaxWidth().padding(end = 36.dp)) {
                            if(text.isEmpty()) Text("输入或粘贴要发送的文本…", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
                            field()
                        }
                        IconButton(onClick = paste, modifier = Modifier.align(Alignment.BottomEnd).size(40.dp)) {
                            AppIcon(R.drawable.ic_paste, description = "粘贴文本", modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                }
            )
            TextButton(onClick = send, enabled = ready && text.isNotEmpty(), modifier = Modifier.align(Alignment.End), colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface, disabledContentColor = colors.onSurfaceVariant)) {
                Text("发送文本", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp)); AppIcon(R.drawable.ic_arrow_up_right, modifier = Modifier.size(16.dp))
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = choose, enabled = ready, modifier = Modifier.weight(1f).heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
                    AppIcon(R.drawable.ic_file_upload, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("发送文件", fontSize = 14.sp)
                }
                OutlinedButton(onClick = chooseFolder, enabled = ready, modifier = Modifier.weight(1f).heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp), border = BorderStroke(1.dp, colors.outlineVariant), colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)) {
                    AppIcon(R.drawable.ic_folder_upload, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("发送文件夹", fontSize = 14.sp)
                }
            }
            Text(if(state.sending) "正在发送，请稍候…" else "支持多选文件，自动保留文件夹结构", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if(state.pendingShares > 0) {
                Text(if(!state.connected) "${state.pendingShares} 份分享等待连接后发送" else "${state.pendingShares} 份分享正在排队", style = MaterialTheme.typography.bodySmall, color = colors.primary)
            }
        }
    }
}
@Composable private fun FolderDestination(configured: Boolean, location: String?, choose: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存位置", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(R.drawable.ic_folder, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("下载 / LanBridge", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(if(configured) "文件夹：${location ?: "已授权目录"}" else "接收文件夹前需授权目录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = choose, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if(configured) "更改" else "选择", fontSize = 14.sp) }
            }
        }
    }
}
@Composable private fun ReceivedHeading(state: TransferState, engine: TransferEngine) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最近收到", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
            TextButton(onClick = { engine.clearHistory() }, enabled = state.received.isNotEmpty(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("清空记录", fontSize = 14.sp) }
        }
        if(state.received.isEmpty()) Text("还没有收到文件或文本", modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun ReceivedCard(item: Received, engine: TransferEngine) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showText by rememberSaveable(item.id) { mutableStateOf(false) }
    val open: () -> Unit = {
        if(item.text != null) showText = true
        else try {
            val uri = requireNotNull(item.uri)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch(_: Exception) { engine.report("没有可打开此文件的应用，可使用分享或系统文件管理器") }
    }
    val copyText: () -> Unit = {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LanBridge", item.text.orEmpty()))
        engine.report("已复制全文")
    }
    if(showText) AlertDialog(
        onDismissRequest = { showText = false }, title = { Text("收到文本") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                androidx.compose.foundation.text.selection.SelectionContainer { Text(item.text.orEmpty().take(2000)) }
                if(item.text.orEmpty().length > 2000) Text("仅预览前 2000 字，可复制全文。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { copyText(); showText = false }) { Text("复制全文") } },
        dismissButton = { TextButton(onClick = { showText = false }) { Text("关闭") } }
    )
    val flash = remember(item.id) { Animatable(0f) }
    var hasFlashed by rememberSaveable(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        if(!hasFlashed) {
            // Stable receipt keys keep older cards from flashing again when a
            // new receipt is inserted, the list scrolls, or the screen recreates.
            hasFlashed = true
            repeat(3) {
                flash.animateTo(1f, tween(300))
                flash.animateTo(0f, tween(300))
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    val extension = item.name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val image = extension in setOf("PNG", "JPG", "JPEG", "GIF", "WEBP", "BMP", "HEIC", "HEIF", "AVIF", "SVG")
    val icon = when { item.text != null -> R.drawable.ic_text; item.directory -> R.drawable.ic_folder; image -> R.drawable.ic_image; else -> R.drawable.ic_document }
    val kind = when { item.directory -> "文件夹"; image -> "$extension 图片"; extension.isNotEmpty() -> "$extension 文件"; else -> "文件" }
    Surface(
        onClick = open,
        shape = RoundedCornerShape(18.dp),
        color = lerp(colors.surfaceContainerLow, colors.primaryContainer, flash.value),
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = flash.value))
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = colors.background) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { AppIcon(icon, modifier = Modifier.size(22.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if(item.text != null) item.text.lineSequence().firstOrNull { it.isNotBlank() } ?: "收到文本" else item.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if(item.text != null) "文本 · ${item.text.length} 字" else "$kind · ${formatSize(item.size)}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) { AppIcon(R.drawable.ic_more, description = "更多操作：${item.name}", modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariant) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text(if(item.text != null) "查看文本" else if(item.directory) "打开文件夹" else "打开") }, onClick = { menuExpanded = false; open() })
                    if(item.text != null) DropdownMenuItem(text = { Text("复制全文") }, onClick = { menuExpanded = false; copyText() })
                    else if(!item.directory) DropdownMenuItem(text = { Text("分享") }, onClick = {
                        menuExpanded = false
                        try { context.startActivity(Intent.createChooser(receivedFileShare(context.contentResolver, requireNotNull(item.uri), item.name), "分享")) }
                        catch(_: Exception) { engine.report("无法分享，请在系统文件管理器中打开 下载/LanBridge") }
                    })
                }
            }
        }
    }
}

@Composable private fun AppIcon(resource: Int, modifier: Modifier = Modifier, description: String? = null, tint: Color = LocalContentColor.current) {
    Icon(painter = painterResource(resource), contentDescription = description, modifier = modifier.size(24.dp), tint = tint)
}
