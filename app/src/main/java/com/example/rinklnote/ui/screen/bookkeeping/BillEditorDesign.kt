package com.example.rinklnote.ui.screen.bookkeeping

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.theme.RinklNoteTheme
import com.example.rinklnote.ui.util.DisplayPreferences

/** 编辑页的局部尺度：金额左对齐、平铺分类、细线分组，沿用标准字号。 */
internal object BillEditorDesign {
    val PagePadding = 16.dp
    val SectionGap = 12.dp
    val GroupRadius = 18.dp
    val ChipRadius = 10.dp
    val TouchHeight = 48.dp
    val SectionTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val Secondary = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val Amount = TextStyle(fontSize = 40.sp, lineHeight = 48.sp,
        fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = (-1).sp)
}

/** 金额与货币符号按同一基线排版；收支选择由底部数字键盘承载。 */
@Composable
internal fun BillAmountHeader(
    amount: String,
    billType: BillType,
    modifier: Modifier = Modifier
) {
    val colors = LocalRinklColors.current
    val showSymbol by DisplayPreferences.currencySymbolVisible.collectAsStateWithLifecycle()
    val amountColor by animateColorAsState(
        if (billType == BillType.EXPENSE) colors.expenseColor else colors.incomeColor,
        Motion.SelectColor, label = "编辑金额收支颜色"
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("本笔金额", style = BillEditorDesign.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = BillEditorDesign.TouchHeight),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showSymbol) {
                Text("¥", fontSize = 24.sp, color = amountColor, modifier = Modifier.alignByBaseline())
            }
            val display = if (amount.isEmpty() || amount == "0") "0.00" else amount
            Text(display, style = BillEditorDesign.Amount.copy(fontSize = if (display.length > 12) 28.sp else 40.sp),
                color = amountColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alignByBaseline())
        }
    }
}

@Preview(name = "记账金额 · 浅色", showBackground = true)
@Composable
private fun BillAmountHeaderPreview() {
    RinklNoteTheme { BillAmountHeader("128.50", BillType.EXPENSE, Modifier.padding(16.dp)) }
}

@Preview(name = "记账金额 · 深色", showBackground = true)
@Composable
private fun BillAmountHeaderDarkPreview() {
    RinklNoteTheme(darkTheme = true) { BillAmountHeader("128.50", BillType.INCOME, Modifier.padding(16.dp)) }
}
