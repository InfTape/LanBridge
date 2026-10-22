package dev.lanbridge

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal class ReceiveSnackbarVisuals(val progress: ReceiveProgress) : SnackbarVisuals {
    override val message = when(progress.phase) {
        ReceivePhase.SUCCEEDED -> "接收成功：${progress.name}"
        ReceivePhase.FAILED -> "接收失败：${progress.error}"
        else -> "正在接收${if(progress.directory) "文件夹" else "文件"}：${progress.name}"
    }
    override val actionLabel = when(progress.phase) {
        ReceivePhase.FAILED -> "详情"
        else -> null
    }
    override val withDismissAction = false
    override val duration = when(progress.phase) {
        ReceivePhase.SUCCEEDED -> SnackbarDuration.Short
        ReceivePhase.FAILED -> SnackbarDuration.Long
        else -> SnackbarDuration.Indefinite
    }
}

@Composable internal fun ReceiveProgressCard(progress: ReceiveProgress, action: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val failed = progress.phase == ReceivePhase.FAILED
    val success = progress.phase == ReceivePhase.SUCCEEDED
    val green = if(dark) Color(0xFF73D9A0) else Color(0xFF00A64B)
    val blue = if(dark) Color(0xFFA7C6FF) else Color(0xFF6689CC)
    val red = if(dark) Color(0xFFFF908A) else Color(0xFFFF2929)
    val title = when(progress.phase) {
        ReceivePhase.SUCCEEDED -> "接收成功"
        ReceivePhase.FAILED -> "接收失败"
        ReceivePhase.SAVING -> if(progress.directory) "正在保存文件夹" else "正在保存文件"
        ReceivePhase.RECEIVING -> if(progress.directory) "正在接收文件夹" else "正在接收文件"
    }
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(20.dp),
        color = if(dark) colors.surfaceContainerHigh else Color.White,
        contentColor = colors.onSurface, border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val icon = when { failed -> R.drawable.ic_error_circle; success -> R.drawable.ic_check_circle; else -> R.drawable.ic_download }
                if(failed) Icon(painterResource(icon), null, Modifier.size(24.dp), red)
                else Surface(shape = CircleShape, color = if(success) {
                    if(dark) Color(0xFF233C32) else Color(0xFFE2F0EA)
                } else if(dark) Color(0xFF2B3A53) else Color(0xFFE5ECF7)) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), null, Modifier.size(24.dp), if(success) green else colors.onSurface)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(when {
                        failed -> progress.error
                        success -> "${progress.name} · 已保存"
                        else -> "${progress.name} · 来自 Windows"
                    }, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                if(progress.active) Text("${progress.percent}%", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                else if(failed) OutlinedButton(onClick = action, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), modifier = Modifier.heightIn(min = 36.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant), border = BorderStroke(1.dp, colors.outlineVariant)) {
                    Text("详情", fontSize = 14.sp)
                }
            }
            if(progress.active) {
                val fraction by animateFloatAsState(progress.fraction, animationSpec = tween(180), label = "Receive percentage")
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = blue, trackColor = if(dark) colors.outlineVariant else Color(0xFFF2F2F2), gapSize = 0.dp, drawStopIndicator = {})
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${formatSize(progress.current)} / ${formatSize(progress.total)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("保存至 ${progress.destination}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}
