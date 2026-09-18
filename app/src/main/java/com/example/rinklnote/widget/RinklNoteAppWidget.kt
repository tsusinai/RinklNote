package com.example.rinklnote.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.rinklnote.MainActivity
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.ui.util.categoryIconRes
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.util.getMonthStart
import com.example.rinklnote.util.getNextMonthStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

// ── Color palette ──────────────────────────────────
// Intentional warm/cool split: expense = primary (warm),
// income = tertiary (cool), budget = neutral with progress.
// Colors are resolved via GlanceTheme.colors inside each composable.

class RinklNoteAppWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as RinklNoteApp
        val repo = app.repository
        val budgetRepo = app.budgetRepository
        val quickAmounts = app.settingsManager.quickAmounts.first()
        val data = withContext(Dispatchers.IO) { loadWidgetData(repo, budgetRepo, quickAmounts) }

        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(12.dp)
                        .background(GlanceTheme.colors.surface)
                ) {
                    when {
                        size.height <= 85.dp -> {
                            if (size.width <= 180.dp) Widget1x2(data)
                            else Widget1x4(data)
                        }
                        else -> Widget2x2(data, size)
                    }
                }
            }
    }
    }
}

class RinklNoteAppWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RinklNoteAppWidget()

    companion object {
        const val EXTRA_OPEN_QUICK_ADD = "extra_open_quick_add"
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        const val EXTRA_AMOUNT_MINOR = "extra_amount_minor"

        fun categoryAddIntent(context: Context, categoryId: Long): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_QUICK_ADD, true)
                putExtra(EXTRA_CATEGORY_ID, categoryId)
            }

        /** 预设金额快捷 chip：带金额 extra 拉起主界面并预填快速记账（支出）。 */
        fun amountAddIntent(context: Context, amountMinor: Long): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_QUICK_ADD, true)
                putExtra(EXTRA_AMOUNT_MINOR, amountMinor)
            }
    }
}

// ── Data ────────────────────────────────────────────

private data class WidgetData(
    val todayExpense: Long,
    val todayIncome: Long,
    val monthExpense: Long,
    val budget: Long?,
    val ranking: List<Pair<com.example.rinklnote.data.db.entity.Category, Long>>,
    val masked: Boolean,
    /** 预设金额快捷 chip（整数分，SettingsManager 可配置，默认 ¥10/¥50）。 */
    val quickAmounts: List<Long>
)

private var lastRefLoadTime = 0L

@OptIn(androidx.glance.ExperimentalGlanceApi::class)
private suspend fun loadWidgetData(
    repo: com.example.rinklnote.data.repository.BillRepository,
    budgetRepo: com.example.rinklnote.data.repository.BudgetRepository,
    quickAmounts: List<Long>
): WidgetData {
    val zone = bookkeepingZone()
    val today = LocalDate.now(zone)
    val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthStart = getMonthStart()
    val nextMonthStart = getNextMonthStart()

    // 避免每次 widget 刷新都重复查库；5 秒内跳过
    val now = System.currentTimeMillis()
    if (now - lastRefLoadTime > 5_000L) {
        repo.loadReferenceData()
        lastRefLoadTime = now
    }
    val cats = repo.expenseCategories.value
    val catIdToCat = cats.associateBy { it.id }

    val todayExpense = repo.getTotalExpense(todayStart, todayEnd)
    val todayIncome = repo.getTotalIncome(todayStart, todayEnd)
    val monthExpense = repo.getTotalExpense(monthStart, nextMonthStart)
    val budget = budgetRepo.getBudget(monthStart)?.amountMinor

    val bills = repo.observeBillsByMonth(monthStart, nextMonthStart).first()
    val ranking = bills
        .filter { it.billType == BillType.EXPENSE }
        .groupBy { it.categoryId }
        .mapNotNull { (catId, list) ->
            val cat = catIdToCat[catId] ?: return@mapNotNull null
            cat to list.sumOf { it.amountMinor }
    }
        .sortedByDescending { it.second }
        .take(4)

    return WidgetData(
        todayExpense = todayExpense,
        todayIncome = todayIncome,
        monthExpense = monthExpense,
        budget = budget,
        ranking = ranking,
        masked = BalancePrivacy.hidden.value,
        quickAmounts = quickAmounts
    )
}

private fun fmtAmount(minor: Long): String {
    val absMinor = kotlin.math.abs(minor)
    val plain = "${absMinor / 100}.${(absMinor % 100).toString().padStart(2, '0')}"
    return if (plain.endsWith(".00")) plain.dropLast(3) else plain
}

private fun WidgetData.money(value: Long): String =
    if (masked) "¥••" else "¥${fmtAmount(value)}"

// ── 1×2 (73×146) — Split pulse: expense | income ──

@Composable
private fun Widget1x2(d: WidgetData) {
    val c = GlanceTheme.colors
    Row(modifier = GlanceModifier.fillMaxSize()) {
        // Expense half — warm tinted background
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .cornerRadius(12.dp)
                .background(c.primaryContainer)
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(6.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text("支出", style = TextStyle(fontSize = 10.sp, color = c.onSurface))
                Spacer(GlanceModifier.height(1.dp))
                Text(d.money(d.todayExpense), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.primary), maxLines = 1)
            }
    }
        // Income half — cool tinted background
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .cornerRadius(12.dp)
                .background(c.tertiaryContainer)
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(6.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text("收入", style = TextStyle(fontSize = 10.sp, color = c.onSurface))
                Spacer(GlanceModifier.height(1.dp))
                Text(d.money(d.todayIncome), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.tertiary), maxLines = 1)
            }
    }
    }
}

// ── 1×4 (73×292) — Dashboard row: 4 metrics ──────

@Composable
private fun Widget1x4(d: WidgetData) {
    val c = GlanceTheme.colors
    val budgetRemain = d.budget?.let { it - d.monthExpense }
    val budgetColor = when {
        budgetRemain == null -> c.onSurface
        budgetRemain >= 0 -> c.tertiary
        else -> GlanceTheme.colors.error
    }
    val budgetLabel = when {
        budgetRemain == null -> "未设置"
        budgetRemain >= 0 -> d.money(budgetRemain)
        else -> d.money(-budgetRemain)
    }
    val budgetTitle = if (budgetRemain != null) "预算剩余" else "预算"

    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        MetricCell("今日支出", d.money(d.todayExpense), c.primary, GlanceModifier.defaultWeight())
        Divider()
        MetricCell("今日收入", d.money(d.todayIncome), c.tertiary, GlanceModifier.defaultWeight())
        Divider()
        MetricCell("本月支出", d.money(d.monthExpense), c.primary, GlanceModifier.defaultWeight())
        Divider()
        MetricCell(budgetTitle, budgetLabel, budgetColor, GlanceModifier.defaultWeight())
    }
}

@Composable
private fun MetricCell(label: String, value: String, color: ColorProvider, modifier: GlanceModifier) {
    Column(modifier = modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
        Text(label, style = TextStyle(fontSize = 9.sp, color = GlanceTheme.colors.onSurface), maxLines = 1)
        Spacer(GlanceModifier.height(2.dp))
        Text(value, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color), maxLines = 1)
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = GlanceModifier
            .width(1.dp)
            .height(28.dp)
            .background(GlanceTheme.colors.surfaceVariant)
    ) {}
}

// ── 2×2 (146×146) — Compact dashboard ─────────────

@Composable
private fun Widget2x2(d: WidgetData, size: DpSize) {
    val c = GlanceTheme.colors
    val usable = size.width - 16.dp
    Column(modifier = GlanceModifier.fillMaxSize().padding(8.dp)) {
        // Zone 1: 今日支出 | 今日收入 — split row
        Row(
            modifier = GlanceModifier.fillMaxWidth().cornerRadius(8.dp).background(c.surfaceVariant),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            ZoneStat("今日支出", d.money(d.todayExpense), c.primary, GlanceModifier.defaultWeight())
            Box(modifier = GlanceModifier.width(1.dp).height(32.dp).background(c.surface)) { }
            ZoneStat("今日收入", d.money(d.todayIncome), c.tertiary, GlanceModifier.defaultWeight())
    }

        Spacer(GlanceModifier.height(6.dp))

        // Zone 2: 本月预算进度
        d.budget?.let { budget ->
            val remain = budget - d.monthExpense
            val ratio = (d.monthExpense / budget).toFloat().coerceIn(0f, 1f)
            val barColor = when {
                ratio <= 0.6f -> c.tertiary
                ratio <= 0.85f -> GlanceTheme.colors.secondary
                else -> GlanceTheme.colors.error
            }

            BudgetSection(ratio, d.money(d.monthExpense), barColor, usable * 0.55f, d.money(if (remain >= 0) remain else -remain), remain >= 0)
    } ?: run {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text("本月", style = TextStyle(fontSize = 11.sp, color = c.onSurface))
                Spacer(GlanceModifier.width(4.dp))
                Text(d.money(d.monthExpense), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.primary))
            }
    }

        Spacer(GlanceModifier.height(6.dp))

        // Zone 3: 预设金额快捷 chip（可配置，默认 ¥10/¥50）+ 本月支出 Top 分类快捷记账芯片。
        // 有金额 chip 时分类让位（最多 3 个），保证 2×2 宽度下每个 chip 仍有可用点击面积。
        val amountChips = d.quickAmounts
        val categoryChips = d.ranking.map { it.first }.take(if (amountChips.isNotEmpty()) 3 else 4)
        val totalChips = amountChips.size + categoryChips.size
        if (totalChips > 0) {
            Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                val cellW = (usable - 4.dp) / totalChips
                amountChips.forEach { amountMinor ->
                    AmountChip(amountMinor, GlanceModifier.width(cellW))
                }
                categoryChips.forEach { cat ->
                    CategoryChip(cat, GlanceModifier.width(cellW))
                }
            }
        }
    }
}

@Composable
private fun ZoneStat(label: String, value: String, color: ColorProvider, modifier: GlanceModifier) {
    Column(modifier = modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
        Text(label, style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurface), maxLines = 1)
        Text(value, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color), maxLines = 1)
    }
}

@Composable
private fun BudgetSection(ratio: Float, spent: String, barColor: ColorProvider, barMaxWidth: Dp, remainText: String, isRemain: Boolean) {
    val c = GlanceTheme.colors
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text("预算", style = TextStyle(fontSize = 10.sp, color = c.onSurface))
            Spacer(GlanceModifier.width(4.dp))
            Box(modifier = GlanceModifier.defaultWeight().height(8.dp).cornerRadius(4.dp).background(c.surfaceVariant)) {
                Box(modifier = GlanceModifier.fillMaxHeight().width(barMaxWidth * ratio).cornerRadius(4.dp).background(barColor)) { }
            }
            Spacer(GlanceModifier.width(4.dp))
            Text(spent, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = barColor), maxLines = 1)
    }
        Spacer(GlanceModifier.height(2.dp))
        Text(
            if (isRemain) "剩余 $remainText" else "超支 $remainText",
            style = TextStyle(fontSize = 10.sp, color = if (isRemain) c.tertiary else GlanceTheme.colors.error),
            maxLines = 1
        )
    }
}

@Composable
private fun CategoryChip(cat: com.example.rinklnote.data.db.entity.Category, modifier: GlanceModifier) {
    val c = GlanceTheme.colors
    Column(
        modifier = modifier
            .padding(2.dp)
            .cornerRadius(8.dp)

            .background(c.surfaceVariant)
            .clickable(actionStartActivity(RinklNoteAppWidgetReceiver.categoryAddIntent(LocalContext.current, cat.id)))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        Image(
            provider = ImageProvider(categoryIconRes(cat.name)),
            contentDescription = cat.name,
            modifier = GlanceModifier.height(18.dp)
        )
        Text(
            text = cat.name,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = c.onSurface),
            maxLines = 1
        )
    }
}

/**
 * 预设金额快捷 chip：「＋¥10」样式，点按直接拉起快速记账并预填该金额（支出）。
 * 金额走 [Money.formatPlain]（widget 内不读应用展示偏好，保持恒定带 ¥ 的观感）。
 */
@Composable
private fun AmountChip(amountMinor: Long, modifier: GlanceModifier) {
    val c = GlanceTheme.colors
    Column(
        modifier = modifier
            .padding(2.dp)
            .cornerRadius(8.dp)
            .background(c.primaryContainer)
            .clickable(actionStartActivity(RinklNoteAppWidgetReceiver.amountAddIntent(LocalContext.current, amountMinor)))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        Text(
            text = "＋¥${fmtAmount(amountMinor)}",
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.primary),
            maxLines = 1
        )
        Text(
            text = "记一笔",
            style = TextStyle(fontSize = 9.sp, color = c.onSurface),
            maxLines = 1
        )
    }
}







