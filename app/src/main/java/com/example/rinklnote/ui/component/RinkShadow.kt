package com.example.rinklnote.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 全局统一阴影：RinklNote 所有卡片/浮层阴影收敛为 1.dp + 极轻黑（alpha 0.05），
 * 只做"轻微抬高"暗示，避免默认黑色阴影在透明玻璃卡片上形成过重的"黑边"。
 * 形状由调用方保留（默认矩形，匹配原 `shadow(无 shape)` 行为）。
 */
fun Modifier.rinkShadow(shape: Shape = RectangleShape): Modifier =
    shadow(
        elevation = 1.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.05f),
        spotColor = Color.Black.copy(alpha = 0.05f),
    )
