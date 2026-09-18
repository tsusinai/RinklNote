package com.example.rinklnote.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.domain.BudgetBurnRisk
import com.example.rinklnote.domain.BudgetRiskLevel
import com.example.rinklnote.domain.budgetRiskLevel

/**
 * 预算燃烧风险预警条（Task 4.1 预算-挑战联动）：计划页（月总额预算）与挑战 hub
 * （周预算）共用，橙色系提示 + 小盘安慰文案。全部实时派生、零存储。
 *
 * 口径（与 Web 端钉死一致）：pct = 按当前燃烧速度预测到周期末的累计消耗 ÷ 周期预算 × 100；
 * <85 低（不展示）、85~100 中、>100 高。
 */
@Composable
fun BurnRiskBar(
    risk: BudgetBurnRisk,
    periodLabel: String,
    modifier: Modifier = Modifier
) {
    if (budgetRiskLevel(risk.pct) == BudgetRiskLevel.LOW) return
    val barColor = RISK_WARN_ORANGE
    val bg = RISK_WARN_ORANGE.copy(alpha = 0.14f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_info),
                contentDescription = null,
                tint = barColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "预算预警 · ${periodLabel}预计用到预算的 ${risk.pct.toInt()}%",
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = barColor
            )
        }
        Spacer(modifier = Modifier.padding(top = 4.dp))
        Text(
            text = when (risk.level) {
                BudgetRiskLevel.MEDIUM ->
                    "小盘陪你悠着点花，贴线也稳稳的～"
                BudgetRiskLevel.HIGH ->
                    "照这个节奏${periodLabel}预算要烧穿啦！别慌，从今天少一点点开始，小盘同行～"
                BudgetRiskLevel.LOW -> ""
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 预警条的橙色（固定值：不随收支色令牌变化，保证「预警」语义可辨识）。 */
private val RISK_WARN_ORANGE = androidx.compose.ui.graphics.Color(0xFFD97706)
