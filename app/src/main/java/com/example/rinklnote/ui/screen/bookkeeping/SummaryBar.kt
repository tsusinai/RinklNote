package com.example.rinklnote.ui.screen.bookkeeping

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.theme.IncomeGreen

@SuppressLint("DefaultLocale")
@Composable
fun SummaryBar(
    currentMonth: Int = java.time.LocalDate.now().monthValue,
    totalExpense: Double,
    totalIncome: Double,
    aiSummary: String? = null,
    aiSummaryLoading: Boolean = false
) {
    // 展开/收起状态：默认收起，点击卡片切换。仅有总结内容时才可交互。
    var expanded by remember { mutableStateOf(false) }
    val hasSummary = aiSummary != null
    val interactionSource = remember { MutableInteractionSource() }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220),
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
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(size = 15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(clickModifier)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // 收入/支出合计行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${currentMonth}月：",
                fontSize = 17.sp,
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
                text = "${String.format("%.2f", totalExpense)}  ",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                text = "收入",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = IncomeGreen
            )
            Text(
                text = "${String.format("%.2f", totalIncome)}  ",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = IncomeGreen
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
            enter = expandVertically(animationSpec = tween(durationMillis = 220)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 220))
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
