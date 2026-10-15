package com.example.rinklnote.ui.component

import java.util.Locale

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import com.example.rinklnote.ui.component.motionClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.screen.bookkeeping.BillDragHost
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.toDateString

/**
 * 一天一张卡：日期头 + 该日所有账单。
 * 长按账单行进入拖动（震动反馈）：被拖行从流中抽出（ghost 由页面层渲染），其余行实时补位/让位，
 * 卡片高度经 animateContentSize 同步变化。删除 = 把行拖到页面的删除区（FAB 变身），不再有左滑删除。
 */
@Composable
fun BillCard(
    date: Long,
    dayOfWeek: String,
    totalAmount: Long,
    bills: List<Bill>,
    dragHost: BillDragHost,
    onEdit: (Bill) -> Unit,
    onDragFinished: () -> Unit,
    // 日期头整行点击 → 打开「当天账单」页（默认空实现：未接线时点了无反应，不影响既有调用）
    onOpenDay: (Long) -> Unit = {},
    // 卡片右滑「再来一笔」回调（默认空实现）
    onCopyBill: (Bill) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val isSourceDay = dragHost.draggedDate == date
    val draggedId = dragHost.draggedBillId ?: -1L
    val gap = if (isSourceDay) dragHost.insertionIndex?.coerceIn(0, bills.size - 1) else null
    val rowHeightPx = dragHost.dragging?.rowHeightPx ?: 0f
    // 被拖行在日账单流中的原始槽位：拖动中必须保持组合（销毁会中断 pointerInput 手势流），
    // 仅折叠高度 + 视觉隐藏，由页面层 ghost 代替显示。
    val sourceIndex = if (isSourceDay) bills.indexOfFirst { it.id == draggedId } else -1

    Column(
        modifier = modifier
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            .animateContentSize(animationSpec = Motion.ContentResize)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                // 日期头整行可点：进入「当天账单」页（只加在头部，不碰行内长按拖动手势）
                .motionClickable { onOpenDay(date) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val headerDateLabel = remember(date, dayOfWeek) {
                "${date.toDateString()} $dayOfWeek"
            }
            Text(
                text = headerDateLabel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            // 收入色走主题令牌（第 10 槽）：未自定义时 rinklColorsOf 已按明暗给默认（亮 IncomeGreen/暗 DarkIncomeGreen）。
            val incomeGreen = LocalRinklColors.current.incomeColor
            val sign = if (totalAmount >= 0) "+" else "-"
            // 缓存金额格式化（重组时不重复计算）；金额为「分」，展示统一走 Money.format。
            val totalText = remember(totalAmount) {
                sign + Money.format(kotlin.math.abs(totalAmount))
            }
            Text(
                text = totalText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (totalAmount >= 0) incomeGreen else MaterialTheme.colorScheme.tertiary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        RinklDivider(endInset = 6.dp)

        bills.forEachIndexed { slot, bill ->
            val isDragged = isSourceDay && bill.id == draggedId
            // 视觉序：去掉被拖行后的序号
            val vi = if (sourceIndex >= 0 && slot > sourceIndex) slot - 1 else slot
            // 被拖行高度折叠为 0（卡片自适应收缩、列表自动补齐空位），插入位之下的行整体下移一行让出空隙
            val shift = when {
                gap == null || isDragged -> 0
                vi >= gap -> 1
                else -> 0
            }
            val offsetY by animateDpAsState(
                targetValue = with(density) { (shift * rowHeightPx).toDp() },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "rowShift"
            )
            key(bill.id) {
                SwipeableBillRow(
                    bill = bill,
                    date = date,
                    isDragged = isDragged,
                    dragHost = dragHost,
                    onEdit = onEdit,
                    onCopyBill = onCopyBill,
                    onDragFinished = onDragFinished,
                    modifier = Modifier
                        .then(if (isDragged) Modifier.height(0.dp) else Modifier)
                        .offset { IntOffset(0, offsetY.roundToPx()) }
                        .graphicsLayer { alpha = if (isDragged) 0f else 1f }
                )
            }
        }
    }
}

/**
 * 包装行：右滑「再来一笔」手势层。
 * 向右滑动超过阈值（64dp）松手触发复制记账，露出「再来一笔」操作背景并伴随轻触震动；
 * 水平滑动事件优先消费（change.consume），避免与外层 Tab 切换手势冲突；
 * 长按纵向拖动排序/删除手势在内层保留，互不影响。
 */
@Composable
private fun SwipeableBillRow(
    bill: Bill,
    date: Long,
    isDragged: Boolean,
    dragHost: BillDragHost,
    onEdit: (Bill) -> Unit,
    onCopyBill: (Bill) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val maxSwipePx = with(LocalDensity.current) { 88.dp.toPx() }
    val triggerPx = with(LocalDensity.current) { 64.dp.toPx() }
    val swipeOffsetAnim = remember { Animatable(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
    ) {
        // 背景层：向右滑动时露出的操作底色与图标提示（Draw 阶段推迟）
        val isSwiping by remember { derivedStateOf { swipeOffsetAnim.value > 0f } }
        if (isSwiping) {
            val colors = LocalRinklColors.current
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = (swipeOffsetAnim.value / triggerPx).coerceIn(0f, 1f)
                    }
                    .drawBehind {
                        drawRect(colors.themeColor.copy(alpha = 0.12f))
                    }
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "再来一笔",
                    tint = colors.themeColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "再来一笔",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.themeColor
                )
            }
        }

        // 内容层：拖动中禁用水平滑动手势
        val isDraggingActive = isDragged || dragHost.isDragging
        DraggableBillRow(
            bill = bill,
            date = date,
            isDragged = isDragged,
            dragHost = dragHost,
            onEdit = onEdit,
            onDragFinished = onDragFinished,
            modifier = Modifier
                .offset { IntOffset(swipeOffsetAnim.value.roundToInt(), 0) }
                .pointerInput(bill.id, isDraggingActive) {
                    if (isDraggingActive) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragAmount > 0 || swipeOffsetAnim.value > 0) {
                                change.consume()
                                val next = (swipeOffsetAnim.value + dragAmount).coerceIn(0f, maxSwipePx)
                                coroutineScope.launch { swipeOffsetAnim.snapTo(next) }
                            }
                        },
                        onDragEnd = {
                            val triggered = swipeOffsetAnim.value >= triggerPx
                            if (triggered) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCopyBill(bill)
                            }
                            coroutineScope.launch {
                                swipeOffsetAnim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                swipeOffsetAnim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        }
                    )
                }
        )
    }
}

/** 单行账单：单击编辑；长按启动拖动（重震动由页面层状态边沿触发），拖动中把位移上报给 dragHost。
 *  拖动中该行保持组合但视觉隐藏（isDragged），点击也被禁用，手势流由本行持续上报。 */
@Composable
private fun DraggableBillRow(
    bill: Bill,
    date: Long,
    isDragged: Boolean,
    dragHost: BillDragHost,
    onEdit: (Bill) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var rowBounds by remember { mutableStateOf<Rect?>(null) }
    // 兜底：行在拖动中被移出组合（列表重排/滚动回收）时，手势流不会回调 onDragCancel，
    // 会让 dragHost.dragging 永久残留（FAB 卡在删除态且不可点）。这里强制收尾。
    DisposableEffect(bill.id) {
        onDispose { if (dragHost.dragging?.billId == bill.id) onDragFinished() }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                rowBounds = coords.boundsInRoot()
                dragHost.registerRow(bill.id, date, coords.boundsInRoot())
            }
            .motionClickable(enabled = !isDragged) { onEdit(bill) }
            .pointerInput(bill.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val bounds = rowBounds
                        if (bounds != null) {
                            dragHost.startDrag(
                                billId = bill.id,
                                date = date,
                                pointerY = bounds.top + offset.y,
                                grabOffsetY = offset.y,
                                rowHeightPx = size.height.toFloat(),
                                originY = bounds.top
                            )
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragHost.onDrag(dragAmount.y)
                    },
                    onDragEnd = { onDragFinished() },
                    onDragCancel = { onDragFinished() }
                )
            }
    ) {
        BillRowContent(
            categoryName = bill.subCategoryName ?: bill.categoryName,
            iconCategoryName = bill.categoryName,
            amountMinor = bill.amountMinor,
            billType = bill.billType.value,
            remark = bill.remark
        )
    }
}

/** 账单行纯内容（卡片行与拖动 ghost 共用）。 */
@Composable
internal fun BillRowContent(
    categoryName: String,
    amountMinor: Long,
    billType: String,
    remark: String?,
    modifier: Modifier = Modifier,
    iconCategoryName: String = categoryName
) {
    // 收入色走主题令牌（第 10 槽）：未自定义时 rinklColorsOf 已按明暗给默认（亮 IncomeGreen/暗 DarkIncomeGreen）。
    val incomeGreen = LocalRinklColors.current.incomeColor
    val isExpense = billType == "EXPENSE"
    // 缓存金额格式化（重组时不重复计算）；金额为「分」，展示统一走 Money.format。
    val amountText = remember(amountMinor, isExpense) {
        (if (isExpense) "-" else "+") + Money.format(amountMinor)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BillCategoryIcon(iconCategoryName)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = categoryName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!remark.isNullOrBlank()) {
                    Text(
                        text = remark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = amountText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (isExpense) MaterialTheme.colorScheme.tertiary else incomeGreen
        )
    }
}
