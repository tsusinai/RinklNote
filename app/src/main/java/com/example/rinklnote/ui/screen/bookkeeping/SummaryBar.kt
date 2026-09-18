package com.example.rinklnote.ui.screen.bookkeeping

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.util.Money

@SuppressLint("DefaultLocale")
@Composable
fun SummaryBar(
    currentMonth: Int = com.example.rinklnote.util.today().monthValue,
    totalExpense: Long,
    totalIncome: Long,
    aiSummary: String? = null,
    aiSummaryLoading: Boolean = false
) {
    // 展开/收起状态：默认收起，点击卡片切换。仅有总结内容时才可交互。
    var expanded by remember { mutableStateOf(false) }
    val hasSummary = aiSummary != null
    val interactionSource = remember { MutableInteractionSource() }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.Fade,
        label = "chevron"
    )
    val clickModifier = if (hasSummary) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null
        ) { expanded = !expanded }
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(size = 15.dp))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 收入/支出合计行
        // 收入色走主题令牌（第 10 槽）：未自定义时 rinklColorsOf 已按明暗给默认（亮 IncomeGreen/暗 DarkIncomeGreen）。
        val incomeColor = LocalRinklColors.current.incomeColor
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${currentMonth}月：",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "支出",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                text = "${Money.formatPlain(totalExpense)}  ",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                text = "收入",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = incomeColor
            )
            Text(
                text = "${Money.formatPlain(totalIncome)}  ",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = incomeColor
            )
            if (hasSummary) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "▾",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation)
                )
            }
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
            }
        }

        // 收起时的提示行：仅当月（有总结内容或正在加载）才展示；切换到其它月份时完全不出现。
        if(!expanded && (hasSummary || aiSummaryLoading)){
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =  if (aiSummaryLoading)"AI 正在整理本月总结…" else "AI总结完成，点击查看",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
