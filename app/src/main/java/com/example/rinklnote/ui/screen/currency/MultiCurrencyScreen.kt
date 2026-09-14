package com.example.rinklnote.ui.screen.currency

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.SettingsGroupCard
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.theme.DefaultCardBorder
import com.example.rinklnote.ui.theme.LocalRinklColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import java.util.Locale

/** 币种定义：ISO 4217 代码 + 常用符号 + 中文名。 */
private data class CurrencyDef(val code: String, val symbol: String, val name: String)

/** 支持的币种清单（首项为本位币默认值 CNY，顺序即页面展示顺序）。 */
private val SUPPORTED_CURRENCIES = listOf(
    CurrencyDef("CNY", "¥", "人民币"),
    CurrencyDef("USD", "$", "美元"),
    CurrencyDef("EUR", "€", "欧元"),
    CurrencyDef("JPY", "¥", "日元"),
    CurrencyDef("GBP", "£", "英镑"),
    CurrencyDef("HKD", "HK$", "港币"),
    CurrencyDef("KRW", "₩", "韩元")
)

/**
 * 演示汇率表（静态常量，相对 CNY 的近似牌价）。
 *
 * **演示数据：记账金额换算为后续接入点（bills 金额仍为整数分本位币）**——
 * 当前仅做静态展示，不参与任何记账/统计链路，也不改动 Room schema；
 * 后续接入实时汇率时整表替换即可。
 */
private val DEMO_RATES_VS_CNY: Map<String, Double> = mapOf(
    "CNY" to 1.00,
    "USD" to 7.10,
    "EUR" to 7.80,
    "JPY" to 0.0480,
    "GBP" to 9.00,
    "HKD" to 0.91,
    "KRW" to 0.0052
)

/**
 * 多币种预实现页（独立路由 `multi-currency`，**该路由由主会话接线**）。
 *
 * 页面自包含：不依赖 ViewModel，本位币读写经 [settingsManager] 直连 DataStore
 * （键 `base_currency`，默认 CNY）；汇率为静态演示数据（见 [DEMO_RATES_VS_CNY]），
 * **不改 Room schema、不改记账链路，金额约定仍为整数分（Long）本位币**。
 *
 * 背景处理对齐 [com.example.rinklnote.ui.screen.plan.CategoryBudgetScreen]：
 * 有自选照片时由 nav 层整窗铺满；无照片时本页自铺 [DefaultHazeBackground]
 * （hazeState 未接时内部兜底自建，保证 blur 源可用）。
 * 顶栏为悬浮式：返回键（AutoMirrored ArrowBack 24dp）+ 居中标题「多币种」20sp Medium。
 *
 * @param backgroundUri 自选背景照片 URI；`null` 时本页自铺默认背景
 * @param hazeState 毛玻璃状态；可空——导航层按「有照片」条件传，无照片为 null 时本页自建兜底
 * @param settingsManager 设置存储（读写本位币键 `base_currency`）
 * @param onBack 返回上一页
 */
@Composable
fun MultiCurrencyScreen(
    backgroundUri: String?,
    hazeState: HazeState?,
    settingsManager: SettingsManager,
    onBack: () -> Unit
) {
    // 兜底 blur 源：导航层未透传 hazeState（无照片场景）时自建，避免 DefaultHazeBackground 拿不到状态。
    val effectiveHazeState = hazeState ?: remember { HazeState() }
    val scope = rememberCoroutineScope()
    // 本位币：DataStore 流；先按默认值渲染，DataStore 首次发射后自动校正
    val baseCurrency by settingsManager.baseCurrency
        .collectAsStateWithLifecycle(initialValue = SettingsManager.DEFAULT_BASE_CURRENCY)

    val listState = rememberLazyListState()
    val topBarHeight = rememberRinklTopBarHeight()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (backgroundUri != null || listScrolled) 1f else 0f,
        label = "multiCurrencyTopBarScrim"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 毛玻璃源：有自选照片时由 nav 层整窗铺满；无照片时本页自铺默认背景。
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = effectiveHazeState)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 顶栏是浮层：首项垫到它下面（不留整块空白）。
            item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

            // 本位币设置：单选行，写入 DataStore 的 base_currency 键
            item(key = "base-currency") {
                SettingsGroupCard(title = "本位币") {
                    SUPPORTED_CURRENCIES.forEachIndexed { index, currency ->
                        CurrencyRow(
                            currency = currency,
                            isSelected = currency.code == baseCurrency,
                            onClick = {
                                scope.launch { settingsManager.setBaseCurrency(currency.code) }
                            }
                        )
                        if (index != SUPPORTED_CURRENCIES.lastIndex) {
                            RinklDivider()
                        }
                    }
                }
            }

            // 汇率演示表：静态牌价（相对 CNY），本位币 CNY 自身不列（1 CNY ≈ 1 CNY 无意义）
            item(key = "demo-rates") {
                SettingsGroupCard(title = "参考汇率（演示）") {
                    val quoted = SUPPORTED_CURRENCIES.filter { it.code != "CNY" }
                    quoted.forEachIndexed { index, currency ->
                        RateRow(currency = currency)
                        if (index != quoted.lastIndex) {
                            RinklDivider()
                        }
                    }
                }
            }

            // 页面脚注：明确演示数据边界（换算为后续接入点）
            item(key = "footnote") {
                Text(
                    text = "演示数据：记账金额换算为后续接入点（bills 金额仍为整数分本位币）。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }

            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(16.dp)) }
        }

        // 悬浮顶栏：返回 + 居中标题（对齐分类预算页）。
        MultiCurrencyTopBar(
            scrimAlpha = topBarScrimAlpha,
            hasPhoto = backgroundUri != null,
            listScrolled = listScrolled,
            onBack = onBack
        )
    }
}

/**
 * 本位币单选行：币种符号 + 中文名 + ISO 代码 + 右侧单选圆点。
 *
 * 行高对齐 SettingsRow（48dp / 横向 16dp 竖向 8dp），选中态标签与符号转 primary 色。
 */
@Composable
private fun CurrencyRow(
    currency: CurrencyDef,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = currency.symbol,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 36.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = currency.name,
            fontSize = 16.sp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = currency.code,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(10.dp))
        RadioDot(isSelected = isSelected)
    }
}

/** 汇率演示行：左「符号 + 中文名」，右「1 代码 ≈ x.xx CNY」。 */
@Composable
private fun RateRow(currency: CurrencyDef) {
    val rate = DEMO_RATES_VS_CNY[currency.code] ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${currency.symbol} ${currency.name}",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "1 ${currency.code} ≈ ${formatRate(rate)} CNY",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 单选圆点：选中实心 primary，未选中空心（边框色跟随主题边框槽），同快捷记账抽屉的分类/账户行。 */
@Composable
private fun RadioDot(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .then(
                if (!isSelected) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = LocalRinklColors.current.borderColor.takeIf { it.alpha > 0f }
                            ?: DefaultCardBorder,
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            )
    )
}

/** 多币种页顶栏：返回（AutoMirrored ArrowBack 24dp）+ 居中标题「多币种」20sp Medium。 */
@Composable
private fun MultiCurrencyTopBar(
    scrimAlpha: Float,
    hasPhoto: Boolean,
    listScrolled: Boolean,
    onBack: () -> Unit
) {
    // 文字色三态：有背景→白；无背景→自定义主题「顶栏标题色」，滚动后略淡。
    val textColor = when {
        hasPhoto -> Color.White
        !listScrolled -> LocalRinklColors.current.topBarTitleColor
        else -> LocalRinklColors.current.topBarTitleColorScrolled
    }
    RinklTopBar(
        scrimAlpha = scrimAlpha,
        horizontalPadding = 8.dp
    ) {
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
            text = "多币种",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** 汇率展示：常规两位小数；JPY/KRW 这类小汇率给 4 位，避免显示成「0.00」。 */
private fun formatRate(rate: Double): String =
    if (rate >= 0.01) String.format(Locale.US, "%.2f", rate)
    else String.format(Locale.US, "%.4f", rate)
