package com.example.rinklnote.ui.screen.bookkeeping

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** 单条正在拖动的账单（坐标均为 root 坐标系）。 */
data class BillDragInfo(
    val billId: Long,
    val date: Long,
    /** 手指的全局 Y。 */
    val pointerY: Float,
    /** 拖起时手指相对行顶部的偏移，ghost 用它保持"抓住"的手感。 */
    val grabOffsetY: Float,
    /** 行高（px），补位占位与 ghost 都用它。 */
    val rowHeightPx: Float,
    /** 拖起时行顶部的全局 Y，ghost 位移基准。 */
    val originY: Float
) {
    /** ghost 中心的全局 Y。 */
    val centerY: Float get() = pointerY - grabOffsetY + rowHeightPx / 2f
}

/**
 * 记账页长按拖动状态机：
 *  - 长按行 → [startDrag]（重震动）→ 拖动中 [onDrag] 更新手指位置，插入位与删除区命中即时重算；
 *  - 插入位变化 / 进入删除区 / 松手删除 由 UI 层用状态边沿触发震动；
 *  - 松手 → UI 层读 [isOverDelete] / [insertionIndex] 决定删除或重排，再调 [endDrag] 复位。
 * 行 bounds 由各 BillRow 经 [registerRow] 注册（billId → 全局 Rect）。
 */
class BillDragHost {
    var dragging by mutableStateOf<BillDragInfo?>(null)
        private set

    /** 删除区（FAB）的全局 bounds，由 FAB 经 onGloballyPositioned 上报。 */
    var deleteZone by mutableStateOf<Rect?>(null)

    /** ghost 中心是否进入删除区。 */
    var isOverDelete by mutableStateOf(false)
        private set

    /** 当前插入位（0..n，n=该日去掉拖动项后的行数），无拖动时为 null。 */
    var insertionIndex by mutableStateOf<Int?>(null)
        private set

    // 行注册表：billId → 全局 Rect / 所属日期
    private val rowBounds = mutableStateMapOf<Long, Rect>()
    private val rowDates = mutableStateMapOf<Long, Long>()

    fun registerRow(billId: Long, date: Long, bounds: Rect?) {
        rowDates[billId] = date
        if (bounds == null) rowBounds.remove(billId) else rowBounds[billId] = bounds
    }

    fun startDrag(billId: Long, date: Long, pointerY: Float, grabOffsetY: Float, rowHeightPx: Float, originY: Float) {
        dragging = BillDragInfo(
            billId = billId, date = date, pointerY = pointerY,
            grabOffsetY = grabOffsetY, rowHeightPx = rowHeightPx, originY = originY
        )
        recompute()
    }

    fun onDrag(deltaY: Float) {
        val d = dragging ?: return
        dragging = d.copy(pointerY = d.pointerY + deltaY)
        recompute()
    }

    private fun recompute() {
        val d = dragging ?: return
        // 插入位：同日（不含拖动项自身）各行中心在 ghost 中心上方的个数 → 0..n
        val rows = rowBounds.entries
            .filter { it.key != d.billId && rowDates[it.key] == d.date }
            .map { it.value }
        insertionIndex = rows.count { it.center.y < d.centerY }
        // 删除区命中：ghost 中心落在删除区（横向略放宽）内
        val zone = deleteZone
        isOverDelete = zone != null && zone.inflate(zone.width * 0.25f)
            .contains(Offset(rowBounds[d.billId]?.center?.x ?: zone.center.x, d.centerY))
    }

    fun endDrag() {
        dragging = null
        insertionIndex = null
        isOverDelete = false
    }
}

/** 震动助手：拖起/删除确认用重震动，插入位变化/进入删除区用轻震动。 */
class BillDragHaptics(private val feedback: HapticFeedback) {
    fun heavy() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    fun light() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

@Composable
fun rememberBillDragHost(): Pair<BillDragHost, BillDragHaptics> {
    val host = remember { BillDragHost() }
    val haptics = BillDragHaptics(LocalHapticFeedback.current)
    return host to haptics
}
