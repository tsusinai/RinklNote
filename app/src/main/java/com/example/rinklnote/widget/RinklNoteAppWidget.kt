package com.example.rinklnote.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
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
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.unit.ColorProvider
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.example.rinklnote.MainActivity
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.ui.util.categoryIconRes
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.getMonthStart
import com.example.rinklnote.util.getNextMonthStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

class RinklNoteAppWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = (context.applicationContext as RinklNoteApp).repository
        val data = withContext(Dispatchers.IO) { loadWidgetData(repo) }

        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                ) {
                    when {
                        size.height <= 85.dp -> {
                            if (size.width <= 180.dp) Compact13(data)
                            else Wide14(data)
                        }
                        else -> Square22(data, size)
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

        fun categoryAddIntent(context: Context, categoryId: Long): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_QUICK_ADD, true)
                putExtra(EXTRA_CATEGORY_ID, categoryId)
            }
    }
}

private data class WidgetData(
    val todayExpense: Double,
    val todayIncome: Double,
    val monthExpense: Double,
    val budget: Double?,
    val categories: List<com.example.rinklnote.data.db.entity.Category>,
    val ranking: List<Pair<com.example.rinklnote.data.db.entity.Category, Double>>,
    val masked: Boolean
)

@OptIn(androidx.glance.ExperimentalGlanceApi::class)
private suspend fun loadWidgetData(repo: com.example.rinklnote.data.repository.BillRepository): WidgetData {
    val zone = bookkeepingZone()
    val today = LocalDate.now(zone)
    val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthStart = getMonthStart()
    val nextMonthStart = getNextMonthStart()

    repo.loadReferenceData()
    val cats = repo.expenseCategories.value
    val catIdToCat = cats.associateBy { it.id }

    val todayExpense = repo.getTotalExpense(todayStart, todayEnd)
    val todayIncome = repo.getTotalIncome(todayStart, todayEnd)
    val monthExpense = repo.getTotalExpense(monthStart, nextMonthStart)
    val budget = repo.getBudget(monthStart)?.amount

    val bills = repo.observeBillsByMonth(monthStart, nextMonthStart).first()
    val ranking = bills
        .filter { it.billType == "EXPENSE" }
        .groupBy { it.categoryId }
        .mapNotNull { (catId, list) ->
            val cat = catIdToCat[catId] ?: return@mapNotNull null
            cat to list.sumOf { it.amount }
        }
        .sortedByDescending { it.second }
        .take(4)

    return WidgetData(
        todayExpense = todayExpense,
        todayIncome = todayIncome,
        monthExpense = monthExpense,
        budget = budget,
        categories = cats.take(4),
        ranking = ranking,
        masked = BalancePrivacy.hidden.value
    )
}

private fun fmtAmount(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.2f", value)

private fun WidgetData.money(value: Double): String =
    if (masked) "¥••" else "¥${fmtAmount(value)}"

// ── 1×2 (73×146) ──────────────────────────────────

@Composable
private fun Compact13(d: WidgetData) {
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            Text("支出", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface))
            Spacer(GlanceModifier.height(2.dp))
            Text(d.money(d.todayExpense), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary))
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            Text("收入", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface))
            Spacer(GlanceModifier.height(2.dp))
            Text(d.money(d.todayIncome), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.tertiary))
        }
    }
}

// ── 1×4 (73×292) ──────────────────────────────────

@Composable
private fun Wide14(d: WidgetData) {
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        CellWide("今日支出", d.money(d.todayExpense), GlanceTheme.colors.primary, GlanceModifier.defaultWeight())
        CellWide("今日收入", d.money(d.todayIncome), GlanceTheme.colors.tertiary, GlanceModifier.defaultWeight())
        CellWide("本月支出", d.money(d.monthExpense), GlanceTheme.colors.primary, GlanceModifier.defaultWeight())
        val budgetRemain = d.budget?.let { it - d.monthExpense }
        if (budgetRemain != null) {
            val color = if (budgetRemain >= 0) GlanceTheme.colors.tertiary else GlanceTheme.colors.error
            CellWide("预算剩余", d.money(budgetRemain), color, GlanceModifier.defaultWeight())
        } else {
            CellWide("预算", "未设置", GlanceTheme.colors.onSurface, GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun CellWide(label: String, value: String, color: ColorProvider, modifier: GlanceModifier) {
    Column(modifier = modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
        Text(label, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface), maxLines = 1)
        Spacer(GlanceModifier.height(2.dp))
        Text(value, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color), maxLines = 1)
    }
}

// ── 2×2 (146×146) ──────────────────────────────────

@Composable
private fun Square22(d: WidgetData, size: DpSize) {
    val usable = size.width - 16.dp
    Column(modifier = GlanceModifier.fillMaxSize().padding(10.dp)) {
        // Row 1: 今日支出 + 今日收入
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.width(usable / 2), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                Text("今日支出", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface), maxLines = 1)
                Text(d.money(d.todayExpense), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary), maxLines = 1)
            }
            Column(modifier = GlanceModifier.width(usable / 2), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                Text("今日收入", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface), maxLines = 1)
                Text(d.money(d.todayIncome), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.tertiary), maxLines = 1)
            }
        }

        Spacer(GlanceModifier.height(6.dp))

        // Row 2: 本月支出 + 预算进度条
        d.budget?.let { budget ->
            val remain = budget - d.monthExpense
            val ratio = if (budget > 0) (d.monthExpense / budget).toFloat().coerceIn(0f, 1f) else 0f
            val barColor = if (ratio <= 0.7f) GlanceTheme.colors.tertiary
                else if (ratio <= 0.9f) GlanceTheme.colors.secondary
                else GlanceTheme.colors.error

            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text("本月", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface))
                Spacer(GlanceModifier.width(4.dp))
                Box(modifier = GlanceModifier.defaultWeight().height(8.dp).background(GlanceTheme.colors.surfaceVariant)) {
                    Box(modifier = GlanceModifier.fillMaxHeight().width((usable * 0.5f) * ratio).background(barColor)) {}
                }
                Spacer(GlanceModifier.width(4.dp))
                Text(d.money(d.monthExpense), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = barColor), maxLines = 1)
            }
            Spacer(GlanceModifier.height(2.dp))
            Text(
                if (remain >= 0) "预算剩余 ${d.money(remain)}" else "已超支 ${d.money(-remain)}",
                style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurface),
                maxLines = 1
            )
        } ?: run {
            Text("本月支出 ${d.money(d.monthExpense)}", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary))
        }

        Spacer(GlanceModifier.height(6.dp))

        // Row 3: 4 quick category buttons
        Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            val chips = d.categories.take(4)
            if (chips.isNotEmpty()) {
                val cellW = (usable - 6.dp) / chips.size.coerceAtLeast(1)
                chips.forEach { cat ->
                    QuickChip(cat, GlanceModifier.width(cellW))
                }
            }
        }
    }
}

@Composable
private fun QuickChip(cat: com.example.rinklnote.data.db.entity.Category, modifier: GlanceModifier) {
    Column(
        modifier = modifier
            .padding(2.dp)
            .background(GlanceTheme.colors.secondaryContainer)
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
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
            maxLines = 1
        )
    }
}