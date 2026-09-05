package com.example.rinklnote.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 全局统一阴影：RinklNote 所有卡片/浮层阴影收敛为 2.dp（此前散落 2/3/4/6/8.dp）。
 * 形状由调用方保留（默认矩形，匹配原 `shadow(无 shape)` 行为），仅统一抬高/高度。
 */
fun Modifier.rinkShadow(shape: Shape = RectangleShape): Modifier =
    shadow(elevation = 2.dp, shape = shape)
