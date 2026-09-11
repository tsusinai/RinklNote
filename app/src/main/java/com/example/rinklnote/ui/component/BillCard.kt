package com.example.rinklnote.ui.component

import java.util.Locale

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.screen.bookkeeping.BillDragHost
import com.example.rinklnote.ui.theme.DarkIncomeGreen
import com.example.rinklnote.ui.theme.IncomeGreen
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
    totalAmount: Double,
    bills: List<Bill>,
    dragHost: BillDragHost,
    onEdit: (Bill) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val dragging = dragHost.dragging
    val isSourceDay = dragging?.date == date
    val draggedId = dragging?.billId ?: -1L
    val gap = if (isSourceDay) dragHost.insertionIndex?.coerceIn(0, bills.size - 1) else null
    val rowHeightPx = dragging?.rowHeightPx ?: 0f
    // 被拖行在日账单流中的原始槽位：拖动中必须保持组合（销毁会中断 pointerInput 手势流），
    // 仅折叠高度 + 视觉隐藏，由页面层 ghost 代替显示。
    val sourceIndex = if (isSourceDay) bills.indexOfFirst { it.id == draggedId } else -1

    Column(
        modifier = modifier
            .padding(horizontal = 14.dp)
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${date.toDateString()} $dayOfWeek",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            val incomeGreen = if (isSystemInDarkTheme()) DarkIncomeGreen else IncomeGreen
            val sign = if (totalAmount >= 0) "+" else "-"
            // 缓存金额格式化（重组时不重复 String.format）。
            val totalText = remember(totalAmount) {
                String.format(Locale.US, "%s¥%.2f", sign, kotlin.math.abs(totalAmount))
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
                DraggableBillRow(
                    bill = bill,
                    date = date,
                    isDragged = isDragged,
                    dragHost = dragHost,
                    onEdit = onEdit,
                    onDragFinished = onDragFinished,
                    modifier = Modifier
                        .then(if (isDragged) Modifier.height(0.dp) else Modifier)
                        .offset(y = offsetY)
                        .graphicsLayer { alpha = if (isDragged) 0f else 1f }
                )
            }
        }
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
            .clickable(enabled = !isDragged) { onEdit(bill) }
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
            amount = bill.amount,
            billType = bill.billType.value,
            remark = bill.remark
        )
    }
}

/** 账单行纯内容（卡片行与拖动 ghost 共用）。 */
@Composable
internal fun BillRowContent(
    categoryName: String,
    amount: Double,
    billType: String,
    remark: String?,
    modifier: Modifier = Modifier
) {
    val incomeGreen = if (isSystemInDarkTheme()) DarkIncomeGreen else IncomeGreen
    val isExpense = billType == "EXPENSE"
    // 缓存金额格式化（重组时不重复 String.format）。
    val amountText = remember(amount, isExpense) {
        String.format(Locale.US, "%s¥%.2f", if (isExpense) "-" else "+", amount)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isExpense) MaterialTheme.colorScheme.tertiary else incomeGreen)
            )
            Spacer(modifier = Modifier.width(8.dp))
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
                        fontSize = 13.sp,
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
