package com.example.rinklnote.ui.component

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
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

    val settle = spring<Float>(dampingRatio = 0.8f, stiffness = 400f)

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
        // Red delete background — sits behind the row, revealed when row slides left
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = "删除",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .padding(end = 24.dp)
                    .clickable { onDelete() }
            )
        }

        // Foreground row content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX }
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
