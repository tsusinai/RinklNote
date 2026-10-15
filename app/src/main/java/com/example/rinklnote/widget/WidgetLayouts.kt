package com.example.rinklnote.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.rinklnote.R
import com.example.rinklnote.ui.util.categoryIconRes
import com.example.rinklnote.widget.RinklNoteAppWidgetReceiver.Companion.amountAddIntent
import com.example.rinklnote.widget.RinklNoteAppWidgetReceiver.Companion.appIntent
import com.example.rinklnote.widget.RinklNoteAppWidgetReceiver.Companion.categoryAddIntent
import com.example.rinklnote.widget.RinklNoteAppWidgetReceiver.Companion.quickAddIntent

@Composable
private fun Label(value: String, color: ColorProvider = WidgetStyle.Muted, size: Int = 11) {
    Text(value, style = TextStyle(fontSize = size.sp, color = color), maxLines = 1)
}

@Composable
private fun Value(value: String, color: ColorProvider = WidgetStyle.Ink, size: Int = 22) {
    Text(value, style = TextStyle(fontSize = size.sp, color = color, fontWeight = FontWeight.Bold), maxLines = 1)
}

@Composable
private fun PrivacyMark(hidden: Boolean) {
    if (hidden) Image(ImageProvider(R.drawable.ic_lock), contentDescription = "桌面隐私保护已开启",
        modifier = GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(WidgetStyle.Muted))
}

@Composable
internal fun OverviewWidgetContent(content: WidgetContent, compact: Boolean) {
    val d = content.presentation
    val cells = listOf(
        Triple("今日支出", d.todayExpense, WidgetStyle.Expense),
        Triple("今日收入", d.todayIncome, WidgetStyle.Income),
        Triple("本月支出", d.monthExpense, WidgetStyle.Ink),
        Triple(if (d.budgetState == WidgetBudgetState.OVERSPENT) "预算超支" else "预算剩余", d.remaining, WidgetStyle.PrimaryInk)
    )
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxSize().clickable(actionStartActivity(appIntent(context))).padding(WidgetStyle.Padding),
        verticalAlignment = Alignment.Vertical.CenterVertically) {
        cells.chunked(if (compact) 2 else 4).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(GlanceModifier.height(12.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                row.forEachIndexed { index, (title, amount, color) ->
                    if (index > 0) Box(GlanceModifier.width(1.dp).height(28.dp).background(WidgetStyle.Divider)) { }
                    Column(GlanceModifier.defaultWeight().padding(horizontal = 3.dp),
                        horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                        Label(title, size = 10)
                        Spacer(GlanceModifier.height(4.dp))
                        Value(amount, color, if (LocalSize.current.width < 340.dp) 13 else 16)
                    }
                }
            }
        }
    }
}

@Composable
internal fun QuickAddWidgetContent(content: WidgetContent) {
    val context = LocalContext.current
    val small = LocalSize.current.height < 180.dp
    Column(GlanceModifier.fillMaxSize().padding(WidgetStyle.Padding)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text("快捷记账", modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(fontSize = 15.sp, color = WidgetStyle.Ink, fontWeight = FontWeight.Bold))
            Image(ImageProvider(R.drawable.ic_edit), contentDescription = null,
                modifier = GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(WidgetStyle.PrimaryInk))
        }
        Spacer(GlanceModifier.defaultWeight())
        Box(GlanceModifier.fillMaxWidth().height(if (small) 32.dp else 40.dp)
            .cornerRadius(WidgetStyle.ActionRadius).background(WidgetStyle.PrimarySoft)
            .clickable(actionStartActivity(quickAddIntent(context))), contentAlignment = Alignment.Center) {
            Value("＋ 记一笔", WidgetStyle.PrimaryInk, 16)
        }
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            content.shortcuts.forEachIndexed { index, shortcut ->
                if (index > 0) Spacer(GlanceModifier.width(6.dp))
                val intent = shortcut.categoryId?.let { categoryAddIntent(context, it) } ?: quickAddIntent(context)
                Column(GlanceModifier.defaultWeight().cornerRadius(WidgetStyle.ActionRadius).background(WidgetStyle.Neutral)
                    .clickable(actionStartActivity(intent)).padding(vertical = if (small) 4.dp else 8.dp),
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                    Image(ImageProvider(categoryIconRes(shortcut.name)), contentDescription = null,
                        modifier = GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(WidgetStyle.category(shortcut.name)))
                    Spacer(GlanceModifier.height(2.dp))
                    Label(shortcut.name, WidgetStyle.Ink)
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            content.quickAmounts.take(2).forEachIndexed { index, amount ->
                if (index > 0) Spacer(GlanceModifier.width(6.dp))
                Box(GlanceModifier.defaultWeight().height(if (small) 28.dp else 34.dp)
                    .cornerRadius(WidgetStyle.ActionRadius).background(WidgetStyle.Neutral)
                    .clickable(actionStartActivity(amountAddIntent(context, amount))), contentAlignment = Alignment.Center) {
                    Value("＋${widgetMoney(amount).removeSuffix(".00")}", size = 13)
                }
            }
        }
    }
}

@Composable
internal fun BudgetWidgetContent(content: WidgetContent) {
    val d = content.presentation
    val context = LocalContext.current
    val small = LocalSize.current.height < 180.dp
    Column(GlanceModifier.fillMaxSize().clickable(actionStartActivity(appIntent(context))).padding(WidgetStyle.Padding)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text("本月预算", modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(fontSize = 15.sp, color = WidgetStyle.Ink, fontWeight = FontWeight.Bold))
            if (d.hidden) PrivacyMark(true) else Label("${content.month}月")
        }
        Spacer(GlanceModifier.defaultWeight())
        Label(if (d.budgetState == WidgetBudgetState.OVERSPENT) "超支" else "剩余")
        Spacer(GlanceModifier.height(4.dp))
        Value(d.remaining, if (d.budgetState == WidgetBudgetState.OVERSPENT) WidgetStyle.Expense else WidgetStyle.PrimaryInk,
            if (small) 22 else 26)
        Spacer(GlanceModifier.height(10.dp))
        Progress(d.progress, WidgetStyle.Primary)
        Spacer(GlanceModifier.height(6.dp))
        Label(d.usage)
        if (!small) {
            Spacer(GlanceModifier.height(3.dp))
            Label(if (d.hidden) "金额与进度已隐藏" else if (d.budgetState == WidgetBudgetState.UNSET) "到计划页设置预算" else "${d.spent} / ${d.limit}", size = 10)
        }
        Spacer(GlanceModifier.defaultWeight())
        if (!small) {
            Spacer(GlanceModifier.height(6.dp))
            Rule()
            Spacer(GlanceModifier.height(6.dp))
            Label(when (d.budgetState) {
                WidgetBudgetState.PROTECTED -> "请在应用内查看详情"
                WidgetBudgetState.UNSET -> "开启预算，从容记账"
                WidgetBudgetState.OVERSPENT -> "留意预算，适度调整"
                WidgetBudgetState.AVAILABLE -> "预算内，稳稳记"
            }, size = 10)
        }
    }
}

@Composable
internal fun CategoryWidgetContent(content: WidgetContent) {
    val d = content.presentation
    val context = LocalContext.current
    val short = LocalSize.current.height < 180.dp
    Column(GlanceModifier.fillMaxSize().padding(WidgetStyle.Padding)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(appIntent(context)))) {
                Label("${content.month}月支出")
                Value(d.monthExpense, size = if (short) 18 else 24)
            }
            PrivacyMark(d.hidden)
            Spacer(GlanceModifier.width(8.dp))
            Box(GlanceModifier.height(36.dp).clickable(actionStartActivity(quickAddIntent(context))), contentAlignment = Alignment.Center) {
                Label("＋ 记一笔", WidgetStyle.PrimaryInk, 13)
            }
        }
        Spacer(GlanceModifier.height(if (short) 4.dp else 8.dp))
        if (d.categories.isEmpty()) {
            Column(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Label("本月还没有支出")
                Label("记一笔，慢慢了解花在哪里", size = 10)
            }
        } else d.categories.forEachIndexed { index, category ->
            if (index > 0) Rule()
            Row(GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionStartActivity(appIntent(context))),
                verticalAlignment = Alignment.Vertical.CenterVertically) {
                Image(ImageProvider(categoryIconRes(category.name)), contentDescription = null,
                    modifier = GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(WidgetStyle.category(category.name)))
                Spacer(GlanceModifier.width(8.dp))
                Text(category.name, modifier = GlanceModifier.width(42.dp), style = TextStyle(fontSize = 12.sp, color = WidgetStyle.Ink), maxLines = 1)
                Box(GlanceModifier.defaultWeight().padding(horizontal = 8.dp)) { Progress(category.progress, WidgetStyle.category(category.name)) }
                Value(category.amount, size = 13)
            }
        }
    }
}

@Composable
private fun Progress(progress: Float?, color: ColorProvider) {
    if (progress == null) {
        // 掩码时仅保留相同的中性轨道，不保留真实长度、颜色告警或百分比。
        Box(GlanceModifier.fillMaxWidth().height(5.dp).cornerRadius(3.dp).background(WidgetStyle.Divider)) { }
    } else LinearProgressIndicator(progress, GlanceModifier.fillMaxWidth().height(5.dp).cornerRadius(3.dp),
        color = color, backgroundColor = WidgetStyle.Divider)
}

@Composable
private fun Rule() {
    Box(GlanceModifier.fillMaxWidth().height(1.dp).background(WidgetStyle.Divider)) { }
}
