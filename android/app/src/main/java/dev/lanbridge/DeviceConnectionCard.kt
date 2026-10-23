package dev.lanbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable internal fun DeviceConnectionCard(state: TransferState, events: kotlinx.coroutines.flow.SharedFlow<TransferEvent>, addComputer: () -> Unit, manageComputer: (String) -> Unit, connectComputer: suspend (String) -> Unit) {
    ComputerHeaderPager(state, events, addComputer, manageComputer, connectComputer)
}

@Composable private fun ComputerHeaderPager(state: TransferState, events: kotlinx.coroutines.flow.SharedFlow<TransferEvent>, addComputer: () -> Unit, manageComputer: (String) -> Unit, connectComputer: suspend (String) -> Unit) {
    val green = if(isSystemInDarkTheme()) Color(0xFF67DD94) else Color(0xFF00A847)
    val computers = remember(state.computers, state.defaultComputerId) {
        state.computers.filter { it.id == state.defaultComputerId } + state.computers.filterNot { it.id == state.defaultComputerId }
    }
    // Rebuild local pages when membership or display order changes; resolve selection by ID.
    key(computers.map { it.id }) {
        val selected = computers.indexOfFirst { it.id == state.selectedComputerId }.coerceAtLeast(0)
        // The last page is an action, never a saved computer or connection target.
        val pageCount = if(computers.isEmpty()) 1 else computers.size + 1
        var page by rememberSaveable { mutableIntStateOf(selected) }
        val scope = rememberCoroutineScope()
        var requestingConnection by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            events.collect { event ->
                if(event is TransferEvent.ComputerSelected) {
                    page = selected
                }
            }
        }
        val viewedComputer = computers.getOrNull(page)
        val viewedCurrent = viewedComputer != null && viewedComputer.id == state.selectedComputerId
        val viewedConnected = viewedCurrent && state.connected
        val viewedConnecting = viewedCurrent && state.connecting
        val canConnect = !viewedConnecting && !requestingConnection && (viewedCurrent || !state.deviceSwitchBlocked)
        val connectViewedComputer: () -> Unit = {
            if(viewedComputer != null && !viewedConnected && canConnect) {
                requestingConnection = true
                scope.launch {
                    try { connectComputer(viewedComputer.id) }
                    finally { requestingConnection = false }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DeviceHeaderSwipe(page, pageCount, computers.isNotEmpty() && !requestingConnection,
                onPageChanged = { page = it }) { displayPage, modifier, interactive, close ->
                val computer = computers.getOrNull(displayPage)
                val addPage = computer == null
                val current = computer?.id == state.selectedComputerId
                val connected = !addPage && current && state.connected
                val connecting = !addPage && current && state.connecting
                val status = when { connected -> "已连接"; connecting -> "连接中"; else -> "未连接" }
                val colors = MaterialTheme.colorScheme
                Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLow,
                    modifier = modifier.fillMaxWidth().then(if(!interactive) Modifier else Modifier.combinedClickable(
                        enabled = !addPage || computers.isEmpty() || !state.deviceSwitchBlocked,
                        onClick = { if(close != null) close() else if(computer == null) addComputer() else manageComputer(computer.id) },
                        onLongClickLabel = "管理设备", onLongClick = computer?.let { { manageComputer(it.id) } }))) {
                    Row(Modifier.heightIn(min = 80.dp).padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(16.dp), color = colors.background) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { ConnectionIcon(if(addPage) R.drawable.ic_add else R.drawable.ic_monitor) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if(addPage) "添加电脑" else computer!!.name.ifEmpty { "Windows 电脑" }, modifier = Modifier.weight(1f, fill = false),
                                    fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if(computer != null && computer.id == state.defaultComputerId) Icon(painterResource(R.drawable.ic_pin), "默认连接设备", Modifier.size(16.dp), tint = colors.primary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                val tint = if(connected) green else colors.onSurfaceVariant
                                if(!addPage) {
                                    if(connecting) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                                    else ConnectionIcon(if(connected) R.drawable.ic_check_circle else R.drawable.ic_error_circle, Modifier.size(15.dp), tint)
                                }
                                Text(if(addPage) "点击扫码或手动连接" else status, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if(computer != null && !connected && interactive) IconButton(
                            enabled = !connecting && !requestingConnection && (current || !state.deviceSwitchBlocked),
                            onClick = connectViewedComputer
                        ) { Icon(painterResource(R.drawable.ic_link), "连接设备", Modifier.size(24.dp), tint = LocalContentColor.current) }
                        if(addPage) ConnectionIcon(R.drawable.ic_chevron_right, Modifier.size(20.dp), colors.onSurfaceVariant)
                    }
                }
            }
            if(computers.isNotEmpty()) Row(
                modifier = Modifier.fillMaxWidth().height(14.dp).semantics {
                    contentDescription = "第 ${page + 1} 页，共 $pageCount 页；左右滑动浏览电脑，点击连接按钮才连接"
                },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pageCount) { index ->
                    Box(Modifier.size(6.dp).background(
                        if(index == page) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), CircleShape))
                }
            }
        }
    }
}

@Composable private fun ConnectionIcon(resource: Int, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(resource), contentDescription = null, modifier = modifier.size(24.dp), tint = tint)
}
