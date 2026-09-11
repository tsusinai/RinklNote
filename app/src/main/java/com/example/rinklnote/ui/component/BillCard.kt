package com.example.rinklnote.ui.component

import java.util.Locale

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import com.example.rinklnote.ui.theme.AxisLabelGray
import com.example.rinklnote.ui.theme.DarkIncomeGreen
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.util.toDateString
import dev.chrisbanes.haze.HazeState

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
    hazeState: HazeState,
    backgroundUri: String? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val dragging = dragHost.dragging
    val isSourceDay = dragging?.date == date
    val draggedId = dragging?.billId ?: -1L
    // 该日去掉被拖行后的剩余行（保持展示序）
    val others = bills.filter { it.id != draggedId }
    val gap = if (isSourceDay) dragHost.insertionIndex?.coerceIn(0, others.size) else null
    val rowHeightDp = with(density) { (dragging?.rowHeightPx ?: 0f).toDp() }

    Column(
        modifier = modifier
            .padding(horizontal = 14.dp)
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(hazeState, backgroundUri, RoundedCornerShape(15.dp)))
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
        Canvas(modifier = Modifier.fillMaxWidth()) { drawLine(color = AxisLabelGray, start = Offset(x=0.dp.toPx(),y = 0.dp.toPx()),  end = Offset(size.width - 6.dp.toPx(), 0f),) }

        others.forEachIndexed { index, bill ->
            // 插入位在该行下方 → 该行整体下移一行，制造"让位"空隙
            val shifted = gap != null && gap > index
            val offsetY by animateDpAsState(
                targetValue = if (shifted) rowHeightDp else 0.dp,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "rowShift"
            )
            key(bill.id) {
                DraggableBillRow(
                    bill = bill,
                    date = date,
                    dragHost = dragHost,
                    onEdit = onEdit,
                    onDragFinished = onDragFinished,
                    modifier = Modifier.offset(y = offsetY)
                )
            }
        }
        // 拖动源日：底部垫出一行高度，与各行下移共同构成补位/让位空间
        if (isSourceDay) {
            Spacer(modifier = Modifier.height(rowHeightDp))
        }
    }
}

/** 单行账单：单击编辑；长按启动拖动（重震动由页面层状态边沿触发），拖动中把位移上报给 dragHost。 */
@Composable
private fun DraggableBillRow(
    bill: Bill,
    date: Long,
    dragHost: BillDragHost,
    onEdit: (Bill) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var rowBounds by remember { mutableStateOf<Rect?>(null) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                rowBounds = coords.boundsInRoot()
                dragHost.registerRow(bill.id, date, coords.boundsInRoot())
            }
            .clickable { onEdit(bill) }
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
