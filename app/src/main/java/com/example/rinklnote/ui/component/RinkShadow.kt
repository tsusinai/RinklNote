package com.example.rinklnote.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * 全局统一阴影挂点：2026-10-10 视觉扁平化调整后**不再绘制阴影**。
 * 保留这个空实现是为了不动十几处调用点——组件层次改由底色、描边与毛玻璃承担；
 * 之后若要恢复阴影，只需在此处统一挂回 `shadow(...)`，各卡片自动跟随。
 */
fun Modifier.rinkShadow(shape: Shape = RectangleShape): Modifier = this
