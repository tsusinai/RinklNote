package com.example.rinklnote.ui.screen.day

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.component.BillRowContent
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.RinklTopBarContentHeight
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.util.BillImageExporter
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.ShareRow
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.toDayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/** 本页卡片统一形状：与首页「一天一张卡」同款 15dp 卡片链。 */
private val DayCardShape = RoundedCornerShape(15.dp)

/**
 * 当天账单数据页（独立 navigation 路由，由主会话接线；建议路由 `day-detail/{dayStart}`）。
 *
 * 签名契约固定，主会话按此接线：
 * - [dayStart]：当日 0 点 epoch millis（业务时区 Asia/Shanghai，与 Bill.date 同锚）；
 * - [onBack]：返回；
 * - [onEditBill]：账单行点击回调（传账单 id），默认空实现防接线前编译断。
 *
 * 结构：悬浮顶栏（返回 + 居中「M月d日 账单」+ 右侧分享）→ 当日汇总卡（支出红/收入绿/结余，
 * 走收支色令牌）→ 当日账单卡（复用首页 [BillRowContent] 行样式：分类点+分类名+备注+金额）。
 * 当日无账单给引导文案，分享按钮置灰。
 *
 * 视觉基准 = 首页记账页无自选照片态：纯白背景 + 15dp 玻璃卡片（rinkShadow + applyCardGlass，
 * 只描边不挂 hazeEffect，无需采样源）。
 *
 * 分享：[BillImageExporter] 用 Bitmap/Canvas 手绘分享长图（不截屏，不受主题影响），
 * 经 `ACTION_SEND`(image/png) + createChooser 唤起 QQ / 微信等分享面板。
 */
@Composable
fun DayBillsScreen(
    dayStart: Long,
    onBack: () -> Unit,
    onEditBill: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    // 页内 VM：仓库从服务定位器（Application）取，主会话只需导航传参，无需额外装配
    val app = context.applicationContext as RinklNoteApp
    val viewModel: DayBillsViewModel = viewModel(
        key = "day_$dayStart",
        factory = DayBillsViewModel.Factory(app.repository, dayStart)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bills = state.bills

    // 当日合计（整数分）：结余 = 收入 - 支出
    val totalExpense = remember(bills) {
        bills.filter { it.billType == BillType.EXPENSE }.sumOf { it.amountMinor }
    }
    val totalIncome = remember(bills) {
        bills.filter { it.billType == BillType.INCOME }.sumOf { it.amountMinor }
    }

    val day = remember(dayStart) {
        Instant.ofEpochMilli(dayStart).atZone(bookkeepingZone()).toLocalDate()
    }
    val pageTitle = remember(day) { "${day.monthValue}月${day.dayOfMonth}日 账单" }

    // 分享中标志：绘长图 + 落盘期间防连点重复唤起
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun shareImage() {
        if (bills.isEmpty() || sharing) return
        sharing = true
        scope.launch {
            // 位图绘制 + PNG 落盘是重 IO/CPU，丢到 IO 线程；完成回主线程再唤起分享面板
            val uri = withContext(Dispatchers.IO) {
                BillImageExporter.export(
                    context = context,
                    title = pageTitle,
                    rows = bills.map {
                        ShareRow(
                            categoryName = it.subCategoryName ?: it.categoryName,
                            remark = it.remark,
                            amountMinor = it.amountMinor,
                            isExpense = it.billType == BillType.EXPENSE
                        )
                    },
                    totalExpenseMinor = totalExpense,
                    totalIncomeMinor = totalIncome
                )
            }
            sharing = false
            if (uri == null) {
                Toast.makeText(context, "生成分享图失败，请重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(send, "分享账单图"))
            } catch (_: Exception) {
                Toast.makeText(context, "未找到可分享的应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // 状态栏内边距必须由列表自己垫：悬浮顶栏（statusBarsPadding + 46dp）浮在其上，
                // 漏掉这层会让 contentPadding 只按 46dp 计算 → 顶栏标题压住汇总卡（重叠 bug）。
                .statusBarsPadding(),
            contentPadding = PaddingValues(
                // 顶部垫高 = 统一顶栏高度 + 起始留白（对齐搜索账单/月度明细页）
                top = RinklTopBarContentHeight + 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
            )
        ) {
            item(key = "summary") {
                DaySummaryCard(
                    dayStart = dayStart,
                    day = day,
                    count = bills.size,
                    totalExpense = totalExpense,
                    totalIncome = totalIncome
                )
            }

            if (bills.isEmpty()) {
                // 首帧 loading 时不闪空态，等 Flow 首次发射再判定
                if (!state.loading) {
                    item(key = "empty") { DayEmptyState() }
                }
            } else {
                item(key = "rows") {
                    DayBillListCard(bills = bills, onEditBill = onEditBill)
                }
            }
        }

        DayTopBar(
            title = pageTitle,
            canShare = bills.isNotEmpty(),
            sharing = sharing,
            onBack = onBack,
            onShare = { shareImage() }
        )
    }
}

// ---------------------------------------------------------------------------
// 页内 VM：只读观察当日账单（复用 observeBillsByMonth 的 [start, end) 查询，SQL 已过滤 deleted=0）
// ---------------------------------------------------------------------------

/** 当天账单页状态：账单由仓库 Flow 实时观察，软删（deleted=1）不会出现在结果里。 */
data class DayBillsState(
    val loading: Boolean = true,
    val bills: List<Bill> = emptyList()
)

/** 页内 VM：按 [dayStart, dayEnd) 观察当日账单；金额一律「分」。 */
class DayBillsViewModel(repository: BillRepository, dayStart: Long) : ViewModel() {

    val state: StateFlow<DayBillsState> = repository.observeBillsByMonth(dayStart, dayEndAfter(dayStart))
        .map { DayBillsState(loading = false, bills = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayBillsState())

    class Factory(
        private val repository: BillRepository,
        private val dayStart: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DayBillsViewModel(repository, dayStart) as T
    }
}

/** dayStart（当日 0 点）→ 次日 0 点（业务时区），复用 observeBillsByMonth 的 [start, end) 区间语义。 */
private fun dayEndAfter(dayStart: Long): Long =
    Instant.ofEpochMilli(dayStart).atZone(bookkeepingZone()).toLocalDate().plusDays(1)
        .atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()

// ---------------------------------------------------------------------------
// 汇总卡 / 账单卡 / 空态
// ---------------------------------------------------------------------------

/** 当日汇总卡：日期 + 星期 + 笔数做头，支出（红）/ 收入（绿）/ 结余三列，颜色走收支色令牌。 */
@Composable
private fun DaySummaryCard(
    dayStart: Long,
    day: LocalDate,
    count: Int,
    totalExpense: Long,
    totalIncome: Long
) {
    // 收入色走主题令牌（第 10 槽）；支出沿用主题 tertiary（= 支出色令牌，第 9 槽写进 tertiary）
    val incomeGreen = LocalRinklColors.current.incomeColor
    val expenseRed = MaterialTheme.colorScheme.tertiary
    val net = totalIncome - totalExpense
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .rinkShadow(DayCardShape)
            .clip(DayCardShape)
            .then(applyCardGlass(DayCardShape))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${day.monthValue}月${day.dayOfMonth}日 ${dayStart.toDayOfWeek()}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "共 $count 笔",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row {
            StatColumn("支出", Money.format(totalExpense), expenseRed, Modifier.weight(1f))
            StatColumn("收入", Money.format(totalIncome), incomeGreen, Modifier.weight(1f))
            StatColumn(
                "结余",
                (if (net >= 0) "+" else "-") + Money.format(abs(net)),
                if (net >= 0) incomeGreen else expenseRed,
                Modifier.weight(1f)
            )
        }
    }
}

/** 汇总卡单列：数值（粗）+ 标签（小灰）。 */
@Composable
private fun RowScope.StatColumn(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 当日账单卡：单日一张卡，行样式复用首页 [BillRowContent]，行间发丝分割线（右缩进 6dp 同款）。 */
@Composable
private fun DayBillListCard(bills: List<Bill>, onEditBill: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .fillMaxWidth()
            .rinkShadow(DayCardShape)
            .clip(DayCardShape)
            .then(applyCardGlass(DayCardShape))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        bills.forEachIndexed { index, bill ->
            if (index > 0) RinklDivider(endInset = 6.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditBill(bill.id) }
            ) {
                BillRowContent(
                    categoryName = bill.subCategoryName ?: bill.categoryName,
                    amountMinor = bill.amountMinor,
                    billType = bill.billType.value,
                    remark = bill.remark
                )
            }
        }
    }
}

/** 空态：当日还没有账单，给引导文案。 */
@Composable
private fun DayEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("这一天还没有账单", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "回首页记一笔吧",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// 顶栏：返回 + 居中标题 + 右侧分享（无账单置灰）
// ---------------------------------------------------------------------------

/** 悬浮返回顶栏：ArrowBack + 居中「M月d日 账单」+ 右侧分享，与搜索账单页同款无边框浮层。 */
@Composable
private fun DayTopBar(
    title: String,
    canShare: Boolean,
    sharing: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit
) {
    val textColor = LocalRinklColors.current.topBarTitleColor
    val shareEnabled = canShare && !sharing
    RinklTopBar(scrimAlpha = 0f, horizontalPadding = 8.dp) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .alpha(if (shareEnabled) 1f else 0.35f)
                .clickable(enabled = shareEnabled, onClick = onShare),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Share,
                contentDescription = "分享账单图",
                tint = textColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
