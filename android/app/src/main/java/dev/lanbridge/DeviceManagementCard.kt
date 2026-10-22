package dev.lanbridge

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun DeviceManagementCard(computer: BoundComputer, state: TransferState, selected: Boolean, selecting: Boolean, busy: Boolean, connectionAction: () -> Unit, click: () -> Unit, longClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val current = computer.id == state.selectedComputerId
    val active = current && (state.connected || state.connecting)
    val pinned = computer.id == state.defaultComputerId
    val status = when { current && state.connected -> "已连接"; current && state.connecting -> "连接中"; else -> "未连接" }
    Surface(shape = RoundedCornerShape(20.dp), color = if(selected) colors.secondaryContainer else colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().combinedClickable(enabled = !busy, onClick = click, onLongClick = longClick, onLongClickLabel = "选择设备")
            .semantics { if(selecting) this.selected = selected }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = colors.background, modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_monitor), null, Modifier.size(24.dp)) }
                }
                if(selected) Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary, modifier = Modifier.size(20.dp).align(Alignment.BottomEnd)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_check), "已选择", Modifier.size(14.dp)) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(computer.name.ifEmpty { "Windows 电脑" }, modifier = Modifier.weight(1f, fill = false), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if(pinned) Icon(painterResource(R.drawable.ic_pin), "默认连接设备，已置顶", Modifier.size(16.dp), tint = colors.primary)
                }
                Text("$status · ${computer.host}", fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(enabled = !busy && (active || current || !state.deviceSwitchBlocked), onClick = connectionAction) {
                Icon(painterResource(if(active) R.drawable.ic_unlink else R.drawable.ic_link),
                    if(active) { if(state.connected) "断开连接" else "停止连接" } else "连接设备", Modifier.size(24.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}
