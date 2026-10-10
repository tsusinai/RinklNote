package com.example.rinklnote.ui.screen.bookkeeping

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.component.RollingAmountText
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow

@SuppressLint("DefaultLocale")
@Composable
fun SummaryBar(
    totalExpense: Long,
    totalIncome: Long,
    modifier: Modifier = Modifier,
    currentMonth: Int = com.example.rinklnote.util.today().monthValue,
    aiSummary: String? = null,
    aiSummaryLoading: Boolean = false,
    onOpenDetail: (() -> Unit)? = null
) {
    // 展开/收起状态：默认收起，点击卡片切换。仅有总结内容时才可交互。
    var expanded by remember { mutableStateOf(false) }
    val hasSummary = aiSummary != null
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(size = 15.dp))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 结余作为视觉主指标，收支作为辅助指标，避免首页第一行塞入过多数字。
        val incomeColor = LocalRinklColors.current.incomeColor
        val balance = totalIncome - totalExpense
        val balanceColor = if (balance >= 0) incomeColor else MaterialTheme.colorScheme.tertiary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onOpenDetail != null) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onOpenDetail
                        )
                    } else {
                        Modifier
                    }
                )
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "${currentMonth}月结余",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.height(2.dp))
                RollingAmountText(
                    amountMinor = balance,
                    color = balanceColor,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            SummaryMetric(
                label = "支出",
                amount = totalExpense,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.width(86.dp)
            )
            SummaryMetric(
                label = "收入",
                amount = totalIncome,
                color = incomeColor,
                modifier = Modifier.width(86.dp)
            )
        }

        // AI 当月总结建议：以 markdown 渲染，展示在卡片内、合计行下。
        // 有值时可点击卡片收回/展开；加载中但尚未有值时给出占位提示。
        AnimatedVisibility(
            visible = hasSummary && expanded,
            enter = expandVertically(animationSpec = Motion.Expand),
            exit = shrinkVertically(animationSpec = Motion.Expand)
        ) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                MarkdownText(text = aiSummary.orEmpty())
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "收起总结",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { expanded = false }
                )
            }
        }

        // 收起时的提示行：仅当月（有总结内容或正在加载）才展示；切换到其它月份时完全不出现。
        if(!expanded && (hasSummary || aiSummaryLoading)){
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =  if (aiSummaryLoading)"AI 正在整理本月总结…" else "AI总结完成，点击查看",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(
                    enabled = hasSummary,
                    interactionSource = interactionSource,
                    indication = null
                ) { expanded = true }
            )
        }
    }
}

/** 首页摘要里的辅助指标，使用语义色做轻量底纹，让数字分组更容易扫读。 */
@Composable
private fun SummaryMetric(
    label: String,
    amount: Long,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .height(48.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, color = color.copy(alpha = 0.82f), fontWeight = FontWeight.Medium)
            RollingAmountText(
                amountMinor = amount,
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
