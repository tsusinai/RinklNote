package com.example.rinklnote.ui.screen.bookkeeping

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.ui.component.AlertDialog
import com.example.rinklnote.ui.component.RinklTextButton
import com.example.rinklnote.ui.component.motionClickable
import com.example.rinklnote.util.today
import java.time.LocalDate
import java.time.YearMonth

/** 按年展示十二个月；与首页保持一致，仅可查看当月及以前的账单。 */
@Composable
internal fun MonthDetailMonthPicker(
    selectedMonth: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    val current = YearMonth.from(today())
    val selected = YearMonth.from(selectedMonth)
    var year by remember(selected) { mutableStateOf(selected.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择月份") },
        confirmButton = { RinklTextButton(onClick = { onSelect(current.atDay(1)) }) { Text("回到本月") } },
        dismissButton = { RinklTextButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    RinklTextButton(
                        onClick = { if (year > 1) year-- }, enabled = year > 1,
                        modifier = Modifier.semantics { contentDescription = "上一年" }
                    ) { Text("‹", fontSize = 22.sp) }
                    Text(year.toString() + "年", fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    RinklTextButton(
                        onClick = { year++ }, enabled = year < current.year,
                        modifier = Modifier.semantics { contentDescription = "下一年" }
                    ) { Text("›", fontSize = 22.sp) }
                }
                (1..12).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { month ->
                            val candidate = YearMonth.of(year, month)
                            val enabled = candidate <= current
                            val active = candidate == selected
                            Box(
                                Modifier.weight(1f).heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface)
                                    .semantics {
                                        this.selected = active
                                        contentDescription = year.toString() + "年" + month + "月"
                                    }
                                    .motionClickable(enabled = enabled, role = Role.Button,
                                        onClickLabel = "查看该月账单") { onSelect(candidate.atDay(1)) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(month.toString() + "月",
                                    color = when {
                                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        active -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    )
}
