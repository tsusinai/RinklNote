package com.example.rinklnote.ui.screen.bookkeeping

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext

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
@androidx.compose.runtime.Stable
class BillDragHost {
    var dragging by mutableStateOf<BillDragInfo?>(null)
        private set

    /** 是否正处于拖拽中（离散独立状态，彻底隔离高频 pointerY 对外层重组的触发）。 */
    var isDragging by mutableStateOf(false)
        private set

    /** 正在拖拽的账单日期（无拖拽时为 null）。 */
    var draggedDate by mutableStateOf<Long?>(null)
        private set

    /** 正在拖拽的账单 id（无拖拽时为 null）。 */
    var draggedBillId by mutableStateOf<Long?>(null)
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
        isDragging = true
        draggedDate = date
        draggedBillId = billId
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
        val targetCenterY = d.centerY
        val targetBillId = d.billId
        val targetDate = d.date

        // 原位纯循环计算插入位，消灭临时迭代器、ArrayList 与 Offset 装箱
        var count = 0
        for ((id, bounds) in rowBounds) {
            if (id != targetBillId && rowDates[id] == targetDate) {
                val rowCenterY = bounds.top + bounds.height * 0.5f
                if (rowCenterY < targetCenterY) {
                    count++
                }
            }
        }
        insertionIndex = count

        // 删除区命中：只看纵向重叠
        val zone = deleteZone
        if (zone != null) {
            val slack = zone.height * 0.5f
            isOverDelete = targetCenterY >= zone.top - slack && targetCenterY <= zone.bottom + slack
        } else {
            isOverDelete = false
        }
    }

    fun endDrag() {
        isDragging = false
        draggedDate = null
        draggedBillId = null
        dragging = null
        insertionIndex = null
        isOverDelete = false
    }
}

/** 震动助手：直接驱动系统 Vibrator（可控时长与振幅），比 Compose 预设的 HapticFeedbackType 强得多。 */
class BillDragHaptics(context: Context) {
    private val vibrator: Vibrator? = run {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** 拖起 / 落入删除区：一记干脆的重震（最大振幅）。 */
    fun heavy() = play(longArrayOf(0, 50), intArrayOf(0, 255))

    /** 删除落定：两段重震，与「命中」区分开。 */
    fun confirm() = play(longArrayOf(0, 40, 70, 80), intArrayOf(0, 255, 0, 255))

    /** 插入位变化：短促轻震。 */
    fun light() = play(longArrayOf(0, 18), intArrayOf(0, 190))

    private fun play(timings: LongArray, amplitudes: IntArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }
}

@Composable
fun rememberBillDragHost(): Pair<BillDragHost, BillDragHaptics> {
    val host = remember { BillDragHost() }
    val context = LocalContext.current
    val haptics = remember(context) { BillDragHaptics(context) }
    return host to haptics
}
