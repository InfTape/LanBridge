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
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
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
    override fun onStart() { super.onStart(); engine.refreshHistory(); engine.resumeRemembered() }
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
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showManualConnection by rememberSaveable { mutableStateOf(false) }
    var showAddComputer by rememberSaveable { mutableStateOf(false) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var deviceActionError by remember { mutableStateOf<String?>(null) }
    var managingDevices by rememberSaveable { mutableStateOf(false) }
    var managedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selectingDevices = managedIds.isNotEmpty()
    var deviceDetailsId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailComputer = state.computers.find { it.id == deviceDetailsId }
    var deviceSettingsMenu by remember(managingDevices, managedIds) { mutableStateOf(false) }
    var managementBusy by remember { mutableStateOf(false) }
    var removeTargets by remember { mutableStateOf<List<String>?>(null) }
    val chosenComputers = state.computers.filter { it.id in managedIds }
    val singleComputer = chosenComputers.singleOrNull()
    val cancelDefault = singleComputer != null && singleComputer.id == state.defaultComputerId
    LaunchedEffect(state.computers.map { it.id }) { managedIds = managedIds.filter { id -> state.computers.any { it.id == id } } }
    BackHandler(managingDevices) {
        if(selectingDevices) managedIds = emptyList() else managingDevices = false
        deviceSettingsMenu = false
    }
    var showRename by rememberSaveable(state.deviceRevision) { mutableStateOf(false) }
    var renameDraft by rememberSaveable(state.deviceRevision) { mutableStateOf("") }
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var renameError by remember(state.deviceRevision) { mutableStateOf<String?>(null) }
    var renameSaving by remember(state.deviceRevision) { mutableStateOf(false) }
    LaunchedEffect(state.selectedComputerId) {
        address = engine.savedAddress(); code = ""; showManualConnection = false; showAddComputer = false
    }
    LaunchedEffect(state.connected, state.peer) {
        if(state.connected) { address = engine.savedAddress(); code = ""; showManualConnection = false }
    }
    val listState = rememberLazyListState()
    val connectionScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val sendSnackbar = remember { SnackbarHostState() }
    val receiveSnackbar = remember { SnackbarHostState() }
    var receiveDetails by remember { mutableStateOf<ReceiveProgress?>(null) }
    val outgoing = state.outgoing
    // Keep one indefinite snackbar alive while bytes change. Only a new task or
    // terminal result replaces it, so progress updates never restart host animations.
    val sendNotice = outgoing?.let { if(it.active) "active" else it.phase.name }
    LaunchedEffect(outgoing?.id, sendNotice) {
        outgoing?.let { sendSnackbar.showSnackbar(SendSnackbarVisuals(it)) }
    }
    val incoming = state.incoming
    val receiveNotice = incoming?.let { if(it.active) "active" else it.phase.name }
    LaunchedEffect(incoming?.id, receiveNotice) {
        if(incoming != null && receiveSnackbar.showSnackbar(ReceiveSnackbarVisuals(incoming)) == SnackbarResult.ActionPerformed) {
            if(incoming.phase == ReceivePhase.FAILED) receiveDetails = incoming
        }
    }
    receiveDetails?.let { receipt ->
        AlertDialog(onDismissRequest = { receiveDetails = null }, title = { Text("接收失败详情") }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(receipt.name)
                Text("已接收 ${formatSize(receipt.current)} / ${formatSize(receipt.total)}")
                androidx.compose.foundation.text.selection.SelectionContainer { Text(receipt.detail.ifEmpty { receipt.error }) }
            }
        }, confirmButton = { TextButton(onClick = { receiveDetails = null }) { Text("关闭") } })
    }
    val chooser = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if(uris.isNotEmpty()) engine.sendFiles(uris) }
    val folderChooser = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if(uri != null) engine.sendFolder(uri) }
    // System photo picker: no storage permission required, returns readable content URIs.
    val imageChooser = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> if(uris.isNotEmpty()) engine.sendFiles(uris) }
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
    val scanForComputer: () -> Unit = {
        // Keep an established session while the user scans; only a valid new
        // pairing result replaces it. Stop offline retries so they cannot race.
        if(state.connecting && !state.connected) engine.disconnect()
        scanner.launch(Intent(context, ScanActivity::class.java))
    }
    val openManualConnection: () -> Unit = {
        if(state.connecting && !state.connected) engine.disconnect()
        manualError = null; showManualConnection = true
    }
    val openDeviceRename: (BoundComputer) -> Unit = { computer ->
        renameTarget = computer.id; renameDraft = computer.name.ifEmpty { "Windows 电脑" }; renameError = null; showRename = true
    }
    if(detailComputer != null) ModalBottomSheet(onDismissRequest = { deviceDetailsId = null }, containerColor = MaterialTheme.colorScheme.background) {
        val computer = detailComputer
        val current = computer.id == state.selectedComputerId
        val active = current && (state.connected || state.connecting)
        val isDefault = computer.id == state.defaultComputerId
        SheetContent("设备设置") {
            Text(computer.name.ifEmpty { "Windows 电脑" }, fontWeight = FontWeight.Medium)
            Text("${computer.host}${if(isDefault) " · 默认连接设备" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(managementBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                if(!active && (current || !state.deviceSwitchBlocked)) SheetOption(R.drawable.ic_link, SheetAccent.BLUE, "连接设备", "连接到这台电脑") {
                    deviceDetailsId = null; managementBusy = true
                    scope.launch {
                        try { deviceActionError = withContext(Dispatchers.IO) { engine.deviceConnectionAction(computer.id, false) } }
                        finally { managementBusy = false }
                    }
                }
                SheetOption(if(isDefault) R.drawable.ic_unpin else R.drawable.ic_pin, SheetAccent.BLUE,
                    if(isDefault) "取消默认连接" else "设为默认连接", "用于下次启动时自动连接") {
                    deviceDetailsId = null; managementBusy = true
                    scope.launch {
                        try { deviceActionError = withContext(Dispatchers.IO) { engine.setDefaultComputer(if(isDefault) null else computer.id) } }
                        finally { managementBusy = false }
                    }
                }
                SheetOption(R.drawable.ic_edit, SheetAccent.GREEN, "重命名设备", "修改这台电脑的显示名称") {
                    deviceDetailsId = null; openDeviceRename(computer)
                }
                SheetOption(R.drawable.ic_copy, SheetAccent.BLUE, "复制 IP 地址", computer.host) {
                    deviceDetailsId = null; clipboard.setPrimaryClip(ClipData.newPlainText("电脑 IP 地址", computer.host))
                }
                TextButton(enabled = !(current && state.deviceSwitchBlocked), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(), onClick = { deviceDetailsId = null; removeTargets = listOf(computer.id) }) {
                    AppIcon(R.drawable.ic_delete); Spacer(Modifier.width(8.dp)); Text("删除设备")
                }
            }
        }
    }
    if(showAddComputer) ModalBottomSheet(onDismissRequest = { showAddComputer = false }, containerColor = MaterialTheme.colorScheme.background) {
        SheetContent("添加电脑") {
            if(state.remembered && state.deviceSwitchBlocked) Text("正在传输或有待发送内容，请完成后再添加电脑。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                SheetOption(R.drawable.ic_scan, SheetAccent.BLUE, "扫码添加", "扫描另一台电脑上的配对二维码") {
                    showAddComputer = false; scanForComputer()
                }
                SheetOption(R.drawable.ic_edit, SheetAccent.GREEN, "手动连接", "输入或粘贴另一台电脑的连接信息") {
                    showAddComputer = false; address = ""; code = ""; openManualConnection()
                }
            }
        }
    }
    if(showRename) AlertDialog(
        onDismissRequest = { showRename = false }, title = { Text("重命名设备") },
        text = {
            OutlinedTextField(renameDraft, { renameDraft = it; renameError = null },
                label = { Text("设备名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = !renameSaving, isError = renameError != null,
                supportingText = { Text(renameError ?: "仅修改本机显示名称；留空恢复默认名称，最多 32 个字符。") })
        },
        confirmButton = { TextButton(onClick = {
            val target = renameTarget
            val draft = renameDraft
            renameSaving = true
            scope.launch {
                val error = withContext(Dispatchers.IO) { if(target == null) "请重新选择设备" else engine.renameComputer(target, draft) }
                renameSaving = false; renameError = error
                if(error == null) showRename = false
            }
        }, enabled = !renameSaving) { Text(if(renameSaving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = { showRename = false }, enabled = !renameSaving) { Text("取消") } }
    )
    removeTargets?.let { targets ->
        AlertDialog(onDismissRequest = { if(!managementBusy) removeTargets = null }, title = { Text("删除 ${targets.size} 台设备？") },
            text = { Text("删除后需重新扫码绑定。其他设备和已接收文件会保留。") },
            confirmButton = { TextButton(enabled = !managementBusy && !(state.selectedComputerId in targets && state.deviceSwitchBlocked),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = {
                    managementBusy = true
                    scope.launch {
                        try {
                            val error = withContext(Dispatchers.IO) { engine.removeComputers(targets.toSet()) }
                            if(error == null) {
                                removeTargets = null; managedIds = managedIds.filterNot { it in targets }
                                if(engine.state.value.computers.isEmpty()) managingDevices = false
                            } else deviceActionError = error
                        } finally { managementBusy = false }
                    }
                }) { Text(if(managementBusy) "删除中…" else "删除") } },
            dismissButton = { TextButton(enabled = !managementBusy, onClick = { removeTargets = null }) { Text("取消") } })
    }
    deviceActionError?.let { message ->
        AlertDialog(onDismissRequest = { deviceActionError = null }, title = { Text("设备操作未完成") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { deviceActionError = null }) { Text("关闭") } })
    }
    if(showManualConnection) ModalBottomSheet(
        onDismissRequest = { showManualConnection = false; manualError = null; code = "" },
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("手动连接", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(address, { address = it; manualError = null }, label = { Text("电脑地址") }, placeholder = { Text("192.168.1.10") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(code, { code = it.uppercase(Locale.ROOT).filter { c -> c.isDigit() || c in 'A'..'F' }.take(16); manualError = null }, label = { Text("16 位配对码") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            manualError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            FilledTonalButton(onClick = {
                try {
                    Pairing.parse(address, code)
                    engine.connect(address, code); code = ""; manualError = null; showManualConnection = false
                } catch(e: Exception) { manualError = e.message ?: "连接信息无效" }
            }, enabled = !state.connecting && (!state.remembered || !state.deviceSwitchBlocked), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("连接电脑") }
            TextButton(onClick = {
                val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                try { val pair = Pairing.fromQr(value); address = "${pair.host}:${pair.port}"; code = pair.code; manualError = null }
                catch(e: Exception) { manualError = if(value.startsWith("lanbridge://")) e.message ?: "连接信息无效" else "剪贴板中没有 LanBridge 连接信息" }
            }, modifier = Modifier.fillMaxWidth()) { Text("粘贴连接信息") }
        }
    }
    if(showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = MaterialTheme.colorScheme.background) {
        SheetContent("设置") {
            SheetOption(R.drawable.ic_monitor, SheetAccent.BLUE, "设备管理", if(state.connected) "已连接 ${state.peer}" else "查看设备与管理绑定") {
                    showSettings = false
                    managedIds = emptyList(); managingDevices = true
            }
            SheetOption(R.drawable.ic_folder, SheetAccent.ORANGE, "文件夹接收位置", folderLocation ?: if(folderDestination) "已授权目录" else "尚未授权，点击选择") {
                showSettings = false; receiveFolderChooser.launch(null)
            }
            Text("LanBridge 1.10.1 · 同一局域网内加密传输", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SnackbarHost(sendSnackbar) { snackbar ->
                val initial = (snackbar.visuals as SendSnackbarVisuals).progress
                val retained = remember(snackbar) { mutableStateOf(initial) }
                val latest = state.outgoing?.takeIf { it.id == initial.id && it.active == initial.active } ?: retained.value
                // Keep the last percentage while this notice fades out; falling
                // back to its initial snapshot would briefly reset the progress.
                SideEffect { retained.value = latest }
                SendProgressCard(latest)
            }
            SnackbarHost(receiveSnackbar) { snackbar ->
                val initial = (snackbar.visuals as ReceiveSnackbarVisuals).progress
                val retained = remember(snackbar) { mutableStateOf(initial) }
                val latest = state.incoming?.takeIf { it.id == initial.id && it.active == initial.active } ?: retained.value
                SideEffect { retained.value = latest }
                ReceiveProgressCard(latest, action = { snackbar.performAction() })
            }
            }
        },
        topBar = { TopAppBar(
            title = {
                Column {
                    Text(if(managingDevices) "设备管理" else "LanBridge", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    if(managingDevices && selectingDevices) Text("已选择 ${chosenComputers.size} 台", fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = {
                if(managingDevices) IconButton(onClick = {
                    if(selectingDevices) managedIds = emptyList() else managingDevices = false
                    deviceSettingsMenu = false; removeTargets = null
                }) {
                    AppIcon(if(selectingDevices) R.drawable.ic_close else R.drawable.ic_arrow_back, description = if(selectingDevices) "退出选择" else "返回")
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = {
                if(managingDevices && selectingDevices) {
                    IconButton(enabled = singleComputer != null && !managementBusy, onClick = {
                        val target = if(cancelDefault) null else singleComputer?.id
                        managementBusy = true
                        scope.launch {
                            try { deviceActionError = withContext(Dispatchers.IO) { engine.setDefaultComputer(target) } }
                            finally { managementBusy = false }
                        }
                    }) { AppIcon(if(cancelDefault) R.drawable.ic_unpin else R.drawable.ic_pin, description = if(cancelDefault) "取消默认连接" else "设为默认连接") }
                    IconButton(enabled = chosenComputers.isNotEmpty() && !managementBusy && !(state.selectedComputerId in managedIds && state.deviceSwitchBlocked),
                        onClick = { removeTargets = chosenComputers.map { it.id } }) { AppIcon(R.drawable.ic_delete, description = "删除设备") }
                    Box {
                        IconButton(enabled = singleComputer != null && !managementBusy, onClick = { deviceSettingsMenu = true }) { AppIcon(R.drawable.ic_more, description = "设备设置") }
                        DropdownMenu(expanded = deviceSettingsMenu, onDismissRequest = { deviceSettingsMenu = false }) {
                            val stopsConnection = singleComputer?.id == state.selectedComputerId && (state.connected || state.connecting)
                            if(!stopsConnection) DropdownMenuItem(text = { Text("连接设备") },
                                enabled = singleComputer != null && (singleComputer.id == state.selectedComputerId || !state.deviceSwitchBlocked),
                                leadingIcon = { AppIcon(R.drawable.ic_sync) }, onClick = {
                                    deviceSettingsMenu = false
                                    singleComputer?.let { target ->
                                        managementBusy = true
                                        scope.launch {
                                            try { deviceActionError = withContext(Dispatchers.IO) { engine.deviceConnectionAction(target.id, false) } }
                                            finally { managementBusy = false }
                                        }
                                    }
                                })
                            DropdownMenuItem(text = { Text("重命名设备") }, leadingIcon = { AppIcon(R.drawable.ic_edit) }, onClick = {
                                deviceSettingsMenu = false; singleComputer?.let(openDeviceRename)
                            })
                            DropdownMenuItem(text = { Text("复制 IP 地址") }, leadingIcon = { AppIcon(R.drawable.ic_copy) }, onClick = {
                                deviceSettingsMenu = false; singleComputer?.let { clipboard.setPrimaryClip(ClipData.newPlainText("电脑 IP 地址", it.host)) }
                            })
                        }
                    }
                } else if(!managingDevices) IconButton(onClick = { showSettings = true }) { AppIcon(R.drawable.ic_settings, description = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        ) }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val sendPanel: @Composable () -> Unit = {
                SendCard(state, text, { text = it },
                    send = { engine.sendText(text) },
                    paste = { text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty() },
                    chooseImages = { imageChooser.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    choose = { chooser.launch(arrayOf("*/*")) },
                    chooseFolder = { folderChooser.launch(null) })
            }
            val connectionPanel: @Composable () -> Unit = {
                DeviceConnectionCard(state,
                    addComputer = { showAddComputer = true },
                    manageComputer = { _ ->
                        managedIds = emptyList(); managingDevices = true
                    },
                    connectComputer = { id ->
                        val error = withContext(Dispatchers.IO) { engine.deviceConnectionAction(id, false) }
                        if(error != null) deviceActionError = error
                    })
            }
            Crossfade(targetState = managingDevices, label = "Device management", modifier = Modifier.fillMaxSize()) { management ->
            if(management) {
                val managementList = rememberLazyListState()
                val managementComputers = remember(state.computers, state.defaultComputerId) {
                    state.computers.filter { it.id == state.defaultComputerId } + state.computers.filterNot { it.id == state.defaultComputerId }
                }
                LaunchedEffect(state.defaultComputerId) {
                    if(state.defaultComputerId != null) managementList.animateScrollToItem(0)
                }
                LazyColumn(Modifier.fillMaxSize(), state = managementList, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(managementComputers, key = { _, computer -> computer.id }) { _, computer ->
                        DeviceManagementCard(computer, state, computer.id in managedIds, selecting = selectingDevices, busy = managementBusy, connectionAction = {
                            if(!managementBusy) {
                                val stop = computer.id == state.selectedComputerId && (state.connected || state.connecting)
                                managementBusy = true
                                scope.launch {
                                    try { deviceActionError = withContext(Dispatchers.IO) { engine.deviceConnectionAction(computer.id, stop) } }
                                    finally { managementBusy = false }
                                }
                            }
                        }, click = {
                            if(!managementBusy) {
                                if(selectingDevices) managedIds = if(computer.id in managedIds) managedIds - computer.id else managedIds + computer.id
                                else deviceDetailsId = computer.id
                            }
                        }, longClick = {
                            if(!managementBusy) managedIds = if(computer.id in managedIds) managedIds - computer.id else managedIds + computer.id
                        })
                    }
                    item { TextButton(onClick = { showAddComputer = true }, enabled = !state.deviceSwitchBlocked && !managementBusy, modifier = Modifier.fillMaxWidth()) {
                        AppIcon(R.drawable.ic_add); Spacer(Modifier.width(8.dp)); Text("添加电脑")
                    } }
                }
            } else if(maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(connectionScroll)) { connectionPanel() }
                    LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { sendPanel() }
                        item { ReceivedHeading(state, engine) }
                        itemsIndexed(state.received, key = { _, item -> item.id }) { _, item -> ReceivedCard(item, engine) }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "connection") { connectionPanel() }
                    item { sendPanel() }
                    item { ReceivedHeading(state, engine) }
                    itemsIndexed(state.received, key = { _, item -> item.id }) { _, item -> ReceivedCard(item, engine) }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
            }
        }
    }
}

@Composable private fun SendCard(state: TransferState, text: String, onText: (String) -> Unit, send: () -> Unit, paste: () -> Unit, chooseImages: () -> Unit, choose: () -> Unit, chooseFolder: () -> Unit) {
    val ready = state.connected && !state.sending
    val colors = MaterialTheme.colorScheme
    var showPicker by rememberSaveable { mutableStateOf(false) }
    if(showPicker) SendPickerSheet(
        dismiss = { showPicker = false },
        chooseImages = { showPicker = false; chooseImages() },
        choose = { showPicker = false; choose() },
        chooseFolder = { showPicker = false; chooseFolder() }
    )
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if(state.connected) "发送到 ${state.deviceName.ifEmpty { "Windows 电脑" }}" else "发送", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
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
            // Plain icons retain full touch targets without filled button backgrounds.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { showPicker = true }, enabled = ready, modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurface)
                ) { AppIcon(R.drawable.ic_add, description = "添加图片、文件或文件夹", modifier = Modifier.size(20.dp)) }
                Text(
                    "图片、文件、文件夹", fontSize = 14.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                )
                IconButton(
                    onClick = send, enabled = ready && text.isNotEmpty(), modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurface, disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.65f))
                ) { AppIcon(R.drawable.ic_send_circle, description = "发送文本", modifier = Modifier.size(24.dp)) }
            }
            if(state.pendingShares > 0) {
                Text(if(!state.connected) "${state.pendingShares} 份分享等待连接后发送" else "${state.pendingShares} 份分享正在排队", style = MaterialTheme.typography.bodySmall, color = colors.primary)
            }
        }
    }
}
@Composable private fun SendPickerSheet(dismiss: () -> Unit, chooseImages: () -> Unit, choose: () -> Unit, chooseFolder: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.background) {
        SheetContent("选择发送内容") {
            SheetOption(R.drawable.ic_image, SheetAccent.BLUE, "发送图片", "从相册选择，可多选", chooseImages)
            SheetOption(R.drawable.ic_document, SheetAccent.GREEN, "发送文件", "文档、压缩包及其他文件", choose)
            SheetOption(R.drawable.ic_folder, SheetAccent.ORANGE, "发送文件夹", "发送整个目录，保留结构", chooseFolder)
        }
    }
}
@Composable private fun SheetContent(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        content()
    }
}
private enum class SheetAccent { BLUE, GREEN, ORANGE }
@Composable private fun SheetOption(icon: Int, accent: SheetAccent, title: String, subtitle: String, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = when(accent) {
        SheetAccent.BLUE -> if(dark) Color(0xFF2F3550) to Color(0xFFC3CCF5) else Color(0xFFE3E8FB) to Color(0xFF3B4BA8)
        SheetAccent.GREEN -> if(dark) Color(0xFF243A2B) to Color(0xFF9EDCB0) else Color(0xFFDDF3E2) to Color(0xFF1E7A3A)
        SheetAccent.ORANGE -> if(dark) Color(0xFF41331F) to Color(0xFFF0C88E) else Color(0xFFF6E3C8) to Color(0xFF8A5A14)
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = palette.first) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { AppIcon(icon, modifier = Modifier.size(24.dp), tint = palette.second) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AppIcon(R.drawable.ic_chevron_right, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun ReceivedHeading(state: TransferState, engine: TransferEngine) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最近收到", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
            TextButton(onClick = { engine.clearHistory() }, enabled = !state.historyLoading && (state.received.isNotEmpty() || state.historyError != null), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("清空记录", fontSize = 14.sp) }
        }
        Text("仅保留最近 24 小时的记录", modifier = Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.historyError?.let { message ->
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { engine.refreshHistory() }) { Text("重试") }
            }
        }
        if(state.historyLoading) Text("正在加载接收记录…", modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if(state.received.isEmpty() && state.historyError == null) ReceivedEmptyState()
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
        if(item.highlight && !hasFlashed) {
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
