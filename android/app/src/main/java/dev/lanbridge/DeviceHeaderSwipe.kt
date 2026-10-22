package dev.lanbridge

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class HeaderAnchor { Previous, Closed, Next }

/** Native drag/velocity/settling; changing pages is strictly local browsing. */
@Composable internal fun DeviceHeaderSwipe(
    page: Int, pageCount: Int, enabled: Boolean, onPageChanged: (Int) -> Unit,
    content: @Composable (page: Int, modifier: Modifier, interactive: Boolean, close: (() -> Unit)?) -> Unit
) {
    key(page) {
        val scope = rememberCoroutineScope()
        val drag = remember { AnchoredDraggableState(HeaderAnchor.Closed) }
        var width by remember { mutableIntStateOf(0) }
        val pageGap = with(LocalDensity.current) { 8.dp.toPx() }
        val pageTravel = width + pageGap
        LaunchedEffect(width, pageGap) {
            if(width > 0) drag.updateAnchors(DraggableAnchors {
                if(page > 0) HeaderAnchor.Previous at pageTravel
                HeaderAnchor.Closed at 0f
                if(page < pageCount - 1) HeaderAnchor.Next at -pageTravel
            })
        }
        val latestPageChanged by rememberUpdatedState(onPageChanged)
        LaunchedEffect(drag) {
            snapshotFlow { drag.settledValue }.collect { settled ->
                when(settled) {
                    HeaderAnchor.Next -> latestPageChanged(page + 1)
                    HeaderAnchor.Previous -> latestPageChanged(page - 1)
                    else -> Unit
                }
            }
        }
        // Return to the current card if a button starts connecting during a swipe.
        LaunchedEffect(enabled) { if(!enabled) drag.animateTo(HeaderAnchor.Closed) }
        val close: () -> Unit = { scope.launch { drag.animateTo(HeaderAnchor.Closed) } }
        fun currentOffset() = drag.offset.takeUnless { it.isNaN() } ?: 0f
        val isOpen by remember { derivedStateOf { drag.offset.isFinite() && drag.offset != 0f } }
        BackHandler(enabled = isOpen) { close() }
        val fling = AnchoredDraggableDefaults.flingBehavior(drag,
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f))
        Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }.clip(RoundedCornerShape(20.dp))
            .anchoredDraggable(drag, reverseDirection = false, orientation = Orientation.Horizontal,
                enabled = enabled, flingBehavior = fling)
            .semantics {
                customActions = buildList {
                    if(enabled && page > 0) add(CustomAccessibilityAction("上一台电脑") { scope.launch { drag.animateTo(HeaderAnchor.Previous) }; true })
                    if(enabled && page < pageCount - 1) add(CustomAccessibilityAction("下一台电脑") { scope.launch { drag.animateTo(HeaderAnchor.Next) }; true })
                }
            }) {
            // Neighbours are previews only. No duplicate touch or accessibility targets.
            if(page > 0) content(page - 1, Modifier.absoluteOffset { IntOffset((currentOffset() - pageTravel).roundToInt(), 0) }
                .clearAndSetSemantics { }, false, null)
            if(page < pageCount - 1) content(page + 1, Modifier.absoluteOffset { IntOffset((currentOffset() + pageTravel).roundToInt(), 0) }
                .clearAndSetSemantics { }, false, null)
            content(page, Modifier.absoluteOffset { IntOffset(currentOffset().roundToInt(), 0) }, true,
                if(isOpen) close else null)
        }
    }
}
