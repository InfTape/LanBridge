package dev.lanbridge

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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

internal class SendSnackbarVisuals(val progress: SendProgress) : SnackbarVisuals {
    override val message = when(progress.phase) {
        SendPhase.SUCCEEDED -> "发送成功：${progress.name}"
        SendPhase.FAILED -> "发送失败：${progress.error}"
        else -> "正在发送${progress.kind.label}：${progress.name}"
    }
    override val actionLabel: String? = null
    override val withDismissAction = false
    override val duration = when(progress.phase) {
        SendPhase.SUCCEEDED -> SnackbarDuration.Short
        SendPhase.FAILED -> SnackbarDuration.Long
        else -> SnackbarDuration.Indefinite
    }
}

@Composable internal fun SendProgressCard(progress: SendProgress, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val success = progress.phase == SendPhase.SUCCEEDED
    val failed = progress.phase == SendPhase.FAILED
    val blue = if(dark) Color(0xFFA7C6FF) else Color(0xFF6689CC)
    val green = if(dark) Color(0xFF73D9A0) else Color(0xFF00A64B)
    val red = if(dark) Color(0xFFFF908A) else Color(0xFFFF2929)
    val badge = when {
        success -> if(dark) Color(0xFF233C32) else Color(0xFFE2F0EA)
        failed -> colors.surfaceContainerLow
        else -> if(dark) Color(0xFF2B3A53) else Color(0xFFE3EBF8)
    }
    val title = when(progress.phase) {
        SendPhase.PREPARING -> "正在准备${progress.kind.label}"
        SendPhase.SENDING -> "正在发送${progress.kind.label}"
        SendPhase.CONFIRMING -> "等待电脑确认"
        SendPhase.SUCCEEDED -> "发送成功"
        SendPhase.FAILED -> "发送失败"
    }
    val batch = if(progress.itemCount > 1) " · ${progress.itemIndex}/${progress.itemCount}" else ""
    val subtitle = when(progress.phase) {
        SendPhase.SUCCEEDED -> if(progress.itemCount > 1) "${progress.itemCount} 个项目已发送至 Windows" else "${progress.name} 已发送至 Windows"
        SendPhase.FAILED -> progress.error
        SendPhase.CONFIRMING -> "${progress.name}$batch · 正在确认接收"
        SendPhase.PREPARING -> if(progress.name.isEmpty()) "正在读取发送内容…" else "${progress.name}$batch"
        SendPhase.SENDING -> "${progress.name}$batch"
    }
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = if(dark) colors.surfaceContainerHigh else Color.White,
        contentColor = colors.onSurface,
        // animateContentSize clips its bounds; an elevated surface would leave
        // a cut-off shadow along the bottom above the gesture navigation area.
        shadowElevation = 0.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = badge) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(when { success -> R.drawable.ic_check; failed -> R.drawable.ic_error_circle; else -> R.drawable.ic_upload }),
                            contentDescription = null, modifier = Modifier.size(24.dp),
                            tint = when { success -> green; failed -> red; else -> colors.onSurface }
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = if(failed) 2 else 1, softWrap = failed, overflow = TextOverflow.Ellipsis)
                }
                if(success) Icon(painterResource(R.drawable.ic_done_all), contentDescription = null, modifier = Modifier.size(22.dp), tint = green)
                else if(progress.active && progress.percent != null) Text("${progress.percent}%", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
            }
            if(progress.active) {
                val track = if(dark) colors.outlineVariant else Color(0xFFF2F2F2)
                val bar = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape)
                if(progress.fraction == null || progress.phase == SendPhase.PREPARING) {
                    LinearProgressIndicator(modifier = bar, color = blue, trackColor = track)
                } else {
                    key(progress.id, progress.itemIndex) {
                        val fraction by animateFloatAsState(progress.fraction ?: 0f, animationSpec = tween(180), label = "Send percentage")
                        LinearProgressIndicator(progress = { fraction }, modifier = bar, color = blue, trackColor = track, gapSize = 0.dp, drawStopIndicator = {})
                    }
                }
            }
        }
    }
}
