package com.example.rinklnote.ui.component

import androidx.compose.animation.core.animate
import com.example.rinklnote.ui.theme.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * iOS-style swipe row: dragging left reveals a red "删除" action on the right.
 * The row itself translates — it is NOT dismissed (no SwipeToDismissBox).
 * Exactly one row is open at a time: the parent hoists `revealed` so opening one
 * automatically closes the others.
 *
 * 删除面板为固定 80dp 宽、钉在尾端的红色区域，"删除"居中、可点。前景是不透明 surface，
 * 左移后即确定露出右侧红底，避免旧实现 matchParentSize + CenterEnd 在细行/卡片裁剪下显示不佳。
 */
@Composable
fun SwipeableBillItem(
    revealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {

    val density = LocalDensity.current
    val revealPx = with(density) { 80.dp.toPx() }
    var offsetX by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    // Gesture lambdas must not capture a stale callback
    val currentOnRevealChange by rememberUpdatedState(onRevealChange)

    val settle = Motion.SwipeSettle

    // Parent-driven reveal change (e.g. opening another row closes this one)
    LaunchedEffect(revealed) {
        if (revealed == (offsetX < -revealPx / 2f)) return@LaunchedEffect
        animate(
            initialValue = offsetX,
            targetValue = if (revealed) -revealPx else 0f,
            animationSpec = settle
        ) { value, _ -> offsetX = value }
    }

    Box(modifier = modifier) {
        // Red delete action — only a red "删除" label on the card surface, revealed
        // when the row slides left. No full red panel behind it (minimal, and the
        // error-color text matches the app's other delete affordances).
        Box(
            modifier = Modifier.matchParentSize(),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = "删除",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable { onDelete() }
            )
        }

        // Foreground row content — opaque surface so the red panel only becomes
        // visible when the row is swiped left.
        // IMPORTANT: graphicsLayer must come BEFORE background, otherwise the
        // background() layer is applied outside the transform and stays fixed,
        // permanently covering the red delete panel (only the content slides).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX }
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onHorizontalDrag = { _, dragAmount ->
                            // Live follow: clamp between fully revealed (negative) and closed
                            offsetX = (offsetX + dragAmount).coerceIn(-revealPx, 0f)
                        },
                        onDragEnd = {
                            val open = offsetX < -revealPx / 2f
                            currentOnRevealChange(open)
                            scope.launch {
                                animate(
                                    initialValue = offsetX,
                                    targetValue = if (open) -revealPx else 0f,
                                    animationSpec = settle
                                ) { value, _ -> offsetX = value }
                            }
                        },
                        onDragCancel = {
                            currentOnRevealChange(false)
                            scope.launch {
                                animate(
                                    initialValue = offsetX,
                                    targetValue = 0f,
                                    animationSpec = settle
                                ) { value, _ -> offsetX = value }
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}
