package com.orangemanager.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 露出的删除按钮宽度。 */
private val ACTION_WIDTH = 78.dp

/** CardBox 自带 10dp 的下边距，删除按钮跟着留同样的量，看起来才对齐。 */
private val CARD_BOTTOM_GAP = 10.dp

/**
 * 左滑露出红色「删除」按钮，**点按钮**才真正删除。
 *
 * 没有用 SwipeToDismissBox：那个是"滑到底直接删掉"，误触就没了；
 * 这里要的是"滑开露出按钮、再点一下确认"，安全得多。
 * 滑不到一半松手会自动弹回去。
 *
 * @param enabled false 时完全不响应左滑（首页只允许删除"已结束"的条目）
 */
@Composable
fun SwipeToRevealDelete(
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        Box(modifier.fillMaxWidth()) { content() }
    } else {
        val actionPx = with(LocalDensity.current) { ACTION_WIDTH.toPx() }
        var offset by remember { mutableFloatStateOf(0f) }
        val animated by animateFloatAsState(targetValue = offset, label = "reveal")

        Box(modifier.fillMaxWidth()) {
            // 只有真的滑开之后才创建这个按钮。必须这样：
            // 首页"已结束"那一行的背景是半透明白，红色会从底下透出来，
            // 看着就像"没滑也露出了删除"。不创建它就没有任何东西可以透。
            if (animated < -0.5f) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    Box(
                        Modifier
                            .width(ACTION_WIDTH)
                            .fillMaxHeight()
                            .padding(bottom = CARD_BOTTOM_GAP)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Red)
                            .clickable { onDelete() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("删除", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }

            // 上层：真正的内容，可以往左拖
            Box(
                Modifier
                    .offset { IntOffset(animated.roundToInt(), 0) }
                    .pointerInput(actionPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = { offset = if (offset < -actionPx / 2f) -actionPx else 0f },
                            onDragCancel = { offset = 0f }
                        ) { change, dragAmount ->
                            // 消费掉横向位移，纵向滚动仍然交给 LazyColumn
                            change.consume()
                            offset = (offset + dragAmount).coerceIn(-actionPx, 0f)
                        }
                    }
            ) { content() }
        }
    }
}
