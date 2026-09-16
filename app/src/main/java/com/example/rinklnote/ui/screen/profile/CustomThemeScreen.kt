package com.example.rinklnote.ui.screen.profile

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.SettingsGroupCard
import com.example.rinklnote.ui.theme.DefaultCardBorder
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.PresetSwatches
import com.example.rinklnote.ui.theme.PresetTheme
import com.example.rinklnote.ui.theme.PresetThemes
import com.example.rinklnote.ui.theme.RinklThemeSlot
import com.example.rinklnote.ui.theme.colorToHex
import com.example.rinklnote.ui.theme.hexToColor
import com.example.rinklnote.domain.AchievementInput
import com.example.rinklnote.domain.DayKind
import com.example.rinklnote.domain.currentBookkeepingStreak
import com.example.rinklnote.domain.dayKind
import com.example.rinklnote.domain.evaluateAchievements
import com.example.rinklnote.domain.monthlyBudgetOutcomes
import com.example.rinklnote.domain.toDayStartEpoch
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
import java.time.LocalDate
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 「自定义主题」整页。
 *
 * 设计沿用 App 既有风格：纯白/照片背景 + 透明玻璃卡（[applyCardGlass]）+ 极简悬浮顶栏
 * + 4/8/12/16dp 间距梯度，和「我的」页的分组卡完全同构。
 *
 * 10 个可调槽位（[RinklThemeSlot]）：
 * 1. 字体颜色 2. 主题色 3. 顶栏标题色 4. 图标/按钮色 5. 边框色（分割线跟随）
 * 6. 热力图颜色（深浅自动分配） 7. 折线/柱状颜色（不影响饼图配色） 8. 导航图标色（底栏独立色）
 * 9. 支出颜色（支出金额与标记） 10. 收入颜色（收入金额与标记）
 *
 * 点任一行 → 底部色盘面板（预设色板 + HSV 自选色盘 + 十六进制输入 + 恢复默认）；
 * 「推荐配色」可一键写满 5 个槽位；追加三套**成就解锁预设**（晨曦/薄荷/琥珀，2026-09-15）——
 * 未解锁显示锁与条件文案（点击 Toast 提示），解锁后同样一键整套应用。
 * 所有改动即时写入 DataStore，`MainActivity` 收集后重建 `RinklColors`，全局立刻生效。
 */
@Composable
fun CustomThemeScreen(
    settingsManager: SettingsManager,
    backgroundUri: String?,
    hazeState: HazeState,
    onBack: () -> Unit
) {
    val custom by settingsManager.customThemeColors.collectAsStateWithLifecycle(initialValue = emptyMap())
    val colors = LocalRinklColors.current
    val scope = rememberCoroutineScope()
    var pickerSlot by remember { mutableStateOf<RinklThemeSlot?>(null) }

    // —— 成就解锁主题（晨曦/薄荷/琥珀）——
    val app = LocalContext.current.applicationContext as RinklNoteApp
    // 当前明暗态：镜像 MainActivity 的解析（SYSTEM 跟随系统 / LIGHT / DARK），解锁预设按此取明暗两套色值。
    val themeMode by settingsManager.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // 解锁谓词的引擎值在组合作用域现算：produceState 订阅「日统计 + 预算」两流（窗口与挑战页一致
    // = 今天 −400 天 ~ 明天），账单/预算变动后实时回落；琥珀复用挑战页同一条 budget-3months 口径。
    val unlockSignals by produceState(ThemeUnlockSignals()) {
        val zone = bookkeepingZone()
        val now = LocalDate.now(zone)
        combine(
            app.database.billDao().observeDailySpendStats(
                now.minusDays(400).toDayStartEpoch(zone),
                now.plusDays(1).toDayStartEpoch(zone),
            ),
            app.budgetRepository.observeBudgets(),
        ) { stats, budgets ->
            ThemeUnlockSignals(
                streakDays = currentBookkeepingStreak(stats, LocalDate.now(bookkeepingZone())),
                totalNoSpendDays = stats.count { dayKind(it) == DayKind.NO_SPEND },
                threeMonthNoOverrun = evaluateAchievements(
                    AchievementInput(
                        recordedDays = 0,
                        firstBillDate = null,
                        dailyStats = stats,
                        budgetOutcomes = monthlyBudgetOutcomes(stats, budgets),
                        achievedChallengeCount = 0,
                    )
                ).firstOrNull { it.id == "budget-3months" }?.unlocked ?: false,
            )
        }.collect { value = it }
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val topBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + 46.dp

    /** 槽位当前生效色（未自定义时给的是该处默认色，便于用户看到「默认长什么样」）。 */
    @Composable
    fun resolved(slot: RinklThemeSlot): Color = when (slot) {
        RinklThemeSlot.FONT -> colors.fontColor
        RinklThemeSlot.PRIMARY -> colors.themeColor
        RinklThemeSlot.TOP_BAR -> colors.topBarTitleColor
        RinklThemeSlot.ICON -> colors.iconButtonColor ?: colors.themeColor
        RinklThemeSlot.BORDER -> colors.borderColor
        RinklThemeSlot.HEATMAP -> colors.heatmapColor
        RinklThemeSlot.CHART -> colors.chartColor
        // 未自定义时底栏图标跟随 onSurface（与 CustomBottomBar 的既有回落一致；onSurface 已随字体色槽联动）。
        RinklThemeSlot.NAV_ICON -> colors.navIconColor ?: MaterialTheme.colorScheme.onSurface
        // 收支两槽一定非空：未自定义时 rinklColorsOf 已按 dark 给默认色（亮 ExpenseRed/IncomeGreen，暗深色变体）。
        RinklThemeSlot.EXPENSE -> colors.expenseColor
        RinklThemeSlot.INCOME -> colors.incomeColor
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 毛玻璃源：有自选照片时由 nav 层整窗铺满。
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = hazeState)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

            item(key = "preview") {
                PreviewCard()
            }

            item(key = "slots") {
                SettingsGroupCard(title = "颜色") {
                    RinklThemeSlot.entries.forEachIndexed { index, slot ->
                        if (index > 0) RinklDivider()
                        SlotRow(
                            label = slotLabel(slot),
                            description = slotDescription(slot),
                            color = resolved(slot),
                            isCustom = custom.containsKey(slot),
                            onClick = { pickerSlot = slot }
                        )
                    }
                }
            }

            item(key = "presets") {
                SettingsGroupCard(title = "推荐配色") {
                    PresetThemeList(
                        onApply = { preset ->
                            scope.launch {
                                // 整套写入：预设里为 null 的槽位显式清掉，回到该槽默认。
                                settingsManager.setCustomThemeColor(RinklThemeSlot.PRIMARY, preset.themeColor)
                                settingsManager.setCustomThemeColor(RinklThemeSlot.BORDER, preset.borderColor)
                                settingsManager.setCustomThemeColor(RinklThemeSlot.ICON, preset.iconColor)
                                settingsManager.setCustomThemeColor(RinklThemeSlot.FONT, preset.fontColor)
                                settingsManager.setCustomThemeColor(RinklThemeSlot.TOP_BAR, preset.topBarColor)
                            }
                        }
                    )
                    RinklDivider()
                    // 成就解锁预设（晨曦/薄荷/琥珀）：未解锁=锁图标 + 条件文案（点击 Toast 提示）；
                    // 解锁后与常规预设一致，一键整套应用（走 setCustomThemeColor 全槽写入）。
                    UnlockablePresetRows(
                        signals = unlockSignals,
                        isDark = isDark,
                        onApply = { preset ->
                            scope.launch {
                                settingsManager.setCustomThemeColor(RinklThemeSlot.PRIMARY, preset.themeColor)
                                settingsManager.setCustomThemeColor(RinklThemeSlot.BORDER, preset.borderColor)
                                settingsManager.setCustomThemeColor(RinklThemeSlot.ICON, preset.iconColor)
                                settingsManager.setCustomThemeColor(RinklThemeSlot.FONT, preset.fontColor)
                                settingsManager.setCustomThemeColor(RinklThemeSlot.TOP_BAR, preset.topBarColor)
                            }
                        }
                    )
                }
            }

            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(48.dp)) }
        }

        ThemeTopBar(
            title = "自定义主题",
            showReset = custom.isNotEmpty(),
            hasBackground = backgroundUri != null,
            onBack = onBack,
            onReset = { scope.launch { settingsManager.clearCustomTheme() } }
        )
    }

    pickerSlot?.let { slot ->
        ColorPickerSheet(
            slotLabel = slotLabel(slot),
            initialColor = resolved(slot),
            isCustom = custom.containsKey(slot),
            allowTransparent = slot == RinklThemeSlot.BORDER,
            onPick = { picked ->
                scope.launch { settingsManager.setCustomThemeColor(slot, picked) }
            },
            onDismiss = { pickerSlot = null }
        )
    }
}

// ---------------------------------------------------------------------------
// 文案
// ---------------------------------------------------------------------------

private fun slotLabel(slot: RinklThemeSlot): String = when (slot) {
    RinklThemeSlot.FONT -> "字体颜色"
    RinklThemeSlot.PRIMARY -> "主题色"
    RinklThemeSlot.TOP_BAR -> "顶栏标题色"
    RinklThemeSlot.ICON -> "图标与按钮色"
    RinklThemeSlot.BORDER -> "边框色"
    RinklThemeSlot.HEATMAP -> "热力图颜色"
    RinklThemeSlot.CHART -> "折线/柱状颜色"
    RinklThemeSlot.NAV_ICON -> "导航图标色"
    RinklThemeSlot.EXPENSE -> "支出颜色"
    RinklThemeSlot.INCOME -> "收入颜色"
}

private fun slotDescription(slot: RinklThemeSlot): String = when (slot) {
    RinklThemeSlot.FONT -> "正文与标题文字"
    RinklThemeSlot.PRIMARY -> "按钮、开关、选中态与指示条"
    RinklThemeSlot.TOP_BAR -> "无自选背景时的页面标题"
    RinklThemeSlot.ICON -> "设置项与各页图标"
    RinklThemeSlot.BORDER -> "卡片框线，可设为透明（不描边）；分割线跟随"
    RinklThemeSlot.HEATMAP -> "月历格子深浅自动分配"
    RinklThemeSlot.CHART -> "不影响饼图配色"
    RinklThemeSlot.NAV_ICON -> "底部导航栏图标，独立于字体色"
    RinklThemeSlot.EXPENSE -> "支出金额与标记"
    RinklThemeSlot.INCOME -> "收入金额与标记"
}

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

@Composable
private fun ThemeTopBar(
    title: String,
    showReset: Boolean,
    hasBackground: Boolean,
    onBack: () -> Unit,
    onReset: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    // 与其他页顶栏一致：有照片背景时用白字（靠照片/遮罩衬托），否则用自定义的顶栏标题色。
    val textColor = if (hasBackground) Color.White else LocalRinklColors.current.topBarTitleColor
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
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
        if (showReset) {
            TextButton(onClick = { showConfirm = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text("恢复默认", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("恢复默认主题") },
            text = { Text("将清空全部自定义颜色，回到 App 默认配色。") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onReset()
                }) { Text("恢复", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 卡片 / 行
// ---------------------------------------------------------------------------


/**
 * 颜色圆点（全页统一）：透明色**画棋盘格** —— 否则「透明」在浅色底上会被看成一个白点，
 * 或者干脆看不见，用户无法确认自己选了什么。其余颜色直接铺色。
 * 统一加一圈浅灰描边（[DefaultCardBorder]），保证白色/浅色圆点也有边界。
 */
@Composable
private fun ColorDot(
    color: Color,
    size: Dp,
    outline: Color = DefaultCardBorder,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, outline, CircleShape)
    ) {
        if (color.alpha == 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cell = this.size.minDimension / 4f
                drawRect(color = Color.White)
                for (row in 0..3) {
                    for (col in 0..3) {
                        if ((row + col) % 2 == 0) {
                            drawRect(
                                color = DefaultCardBorder,
                                topLeft = Offset(col * cell, row * cell),
                                size = Size(cell, cell)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单个颜色槽：左标签+说明，右侧当前色圆点（未自定义时带「默认」小字）+「>」。 */
@Composable
private fun SlotRow(
    label: String,
    description: String,
    color: Color,
    isCustom: Boolean,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!isCustom) {
            Text("默认", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(8.dp))
        }
        ColorDot(color = color, size = 26.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// 实时预览
// ---------------------------------------------------------------------------

/** 预览卡：用真实组件（色点/文字/金额/分割线/图标/按钮）展示 5 个槽位改动后的样子。 */
@Composable
private fun PreviewCard() {
    SettingsGroupCard(title = "预览") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("午餐", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("公司楼下", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "-¥28.00",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        RinklDivider(modifier = Modifier.padding(start = 16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_wallet),
                contentDescription = null,
                tint = LocalRinklColors.current.iconButtonColor ?: MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("图标与按钮色", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text("按钮", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 整套预设
// ---------------------------------------------------------------------------

@Composable
private fun PresetThemeList(onApply: (PresetTheme) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PresetThemes.forEachIndexed { index, preset ->
            if (index > 0) RinklDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onApply(preset) }
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preset.name,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                listOfNotNull(preset.themeColor, preset.iconColor, preset.borderColor).forEach { c ->
                    ColorDot(color = c, size = 22.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("应用", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 色盘面板
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPickerSheet(
    slotLabel: String,
    initialColor: Color,
    isCustom: Boolean,
    /** 是否允许选「透明」——目前只有「边框色」槽需要（透明 = 卡片不描边）。 */
    allowTransparent: Boolean = false,
    onPick: (Color?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0f) }
    var value by remember { mutableFloatStateOf(0f) }
    var hexText by remember { mutableStateOf(colorToHex(initialColor)) }
    // 当前是否选中「透明」：以已存值初始化（流被外部改动时同步重置），
    // 用户一动色盘/预设色/十六进制就切回不透明。
    var transparent by remember(initialColor) { mutableStateOf(initialColor.alpha == 0f) }

    // 初始色 → HSV（借系统 API 换算，避免手写转换在灰阶/边界上的偏差）。
    LaunchedEffect(initialColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        hexText = colorToHex(initialColor)
    }

    val draft = Color.hsv(hue, sat, value)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    slotLabel,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                ColorDot(color = if (transparent) Color.Transparent else draft, size = 28.dp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (allowTransparent) {
                    "选一个预设色，或用下方色盘自选；也可直接选「透明」不描边"
                } else {
                    "选一个预设色，或用下方色盘自选"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 「透明」入口：只给边框色槽。透明 = 卡片不画框线（applyCardGlass 会跳过 1dp 描边），
            // 这是「不要边框」唯一显式开关；分割线不会跟着消失（dividerColor 回落到默认灰）。
            if (allowTransparent) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, DefaultCardBorder, RoundedCornerShape(12.dp))
                        .clickable {
                            transparent = true
                            onPick(Color.Transparent)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ColorDot(color = Color.Transparent, size = 24.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "透明（不描边）",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "卡片不再画框线；分割线保持默认灰，不会一起消失",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (transparent) {
                        Text("已选", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 预设色板（两行 × 8 列）
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PresetSwatches.chunked(8).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowColors.forEach { c ->
                            ColorDot(
                                color = c,
                                size = 32.dp,
                                modifier = Modifier.clickable {
                                    transparent = false
                                    onPick(c)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 自选色盘：饱和度/明度方块 + 色相条
            SaturationValuePicker(
                hue = hue,
                sat = sat,
                value = value,
                onChange = { s, v ->
                    sat = s
                    value = v
                    transparent = false
                    hexText = colorToHex(Color.hsv(hue, s, v))
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            HueSlider(
                hue = hue,
                onChange = { h ->
                    hue = h
                    transparent = false
                    hexText = colorToHex(Color.hsv(h, sat, value))
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = hexText,
                onValueChange = { text ->
                    hexText = text
                    hexToColor(text)?.let { picked ->
                        transparent = picked.alpha == 0f
                        val hsv = FloatArray(3)
                        android.graphics.Color.colorToHSV(picked.toArgb(), hsv)
                        hue = hsv[0]
                        sat = hsv[1]
                        value = hsv[2]
                    }
                },
                label = {
                    Text(
                        if (allowTransparent) "十六进制（可带透明度，如 #801E88E5）"
                        else "十六进制（如 #1E88E5）"
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        onPick(null)
                        onDismiss()
                    },
                    enabled = isCustom
                ) { Text("恢复默认") }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    onClick = {
                        onPick(if (transparent) Color.Transparent else (hexToColor(hexText) ?: draft))
                    }
                ) { Text("确定") }
            }
        }
    }
}

/** HSV 饱和度/明度方形选择区：横轴=饱和度，纵轴=明度（上亮下暗）。 */
@Composable
private fun SaturationValuePicker(
    hue: Float,
    sat: Float,
    value: Float,
    onChange: (Float, Float) -> Unit
) {
    val hueColor = Color.hsv(hue, 1f, 1f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    if (w > 0f && h > 0f) {
                        onChange(
                            (change.position.x / w).coerceIn(0f, 1f),
                            (1f - change.position.y / h).coerceIn(0f, 1f)
                        )
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    if (w > 0f && h > 0f) {
                        onChange(
                            (pos.x / w).coerceIn(0f, 1f),
                            (1f - pos.y / h).coerceIn(0f, 1f)
                        )
                    }
                }
            }
    ) {
        drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        // 当前选点
        val cx = sat * size.width
        val cy = (1f - value) * size.height
        drawCircle(color = Color.White, radius = 9f, center = Offset(cx, cy))
        drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = 9f, center = Offset(cx, cy), style = Stroke(width = 2f))
    }
}

/** 色相条（0..360）。 */
@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    val rainbow = listOf(
        Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val w = size.width.toFloat()
                    if (w > 0f) onChange((change.position.x / w).coerceIn(0f, 1f) * 360f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat()
                    if (w > 0f) onChange((pos.x / w).coerceIn(0f, 1f) * 360f)
                }
            }
    ) {
        drawRect(brush = Brush.horizontalGradient(rainbow))
        val x = (hue / 360f) * size.width
        drawCircle(color = Color.White, radius = size.height * 0.85f, center = Offset(x, size.height / 2f))
        drawCircle(
            color = Color.Black.copy(alpha = 0.5f),
            radius = size.height * 0.85f,
            center = Offset(x, size.height / 2f),
            style = Stroke(width = 2f)
        )
    }
}

// ---------------------------------------------------------------------------
// 成就解锁预设（晨曦 / 薄荷 / 琥珀）
// ---------------------------------------------------------------------------

/** 主题解锁谓词的实时引擎值快照（由 ChallengeEngine 从日统计/预算现算，见 produceState）。 */
private data class ThemeUnlockSignals(
    /** 当前连续记账天数（晨曦：≥ 30 解锁）。 */
    val streakDays: Int = 0,
    /** 累计无消费天数（薄荷：≥ 100 解锁）。 */
    val totalNoSpendDays: Int = 0,
    /** 连续 3 个月预算不超支（琥珀；复用挑战页 budget-3months 成就口径）。 */
    val threeMonthNoOverrun: Boolean = false,
)

/**
 * 一套成就解锁预设：id 与挑战页「主题解锁」行的 ThemeUnlockState 对齐（dawn/mint/amber），
 * 明暗各一套 [PresetTheme]（font/topBar 为 null = 跟随系统明暗的默认字体色），
 * 应用时按当前明暗态取对应套写入。
 */
internal data class UnlockableThemePreset(
    val id: String,
    val name: String,
    /** 解锁条件文案（不含「解锁」二字，行内文案与 Toast 拼接用）。 */
    val requirement: String,
    val light: PresetTheme,
    val dark: PresetTheme,
)

/**
 * 三套成就解锁预设：晨曦=暖橙粉调、薄荷=青绿调、琥珀=金棕调；明暗两套、格式与常规预设一致。
 * internal：挑战区「主题换装间」（screen/challenge）陈列与预览切换复用同一份数据，避免两处漂移。
 */
internal val UnlockableThemePresets = listOf(
    UnlockableThemePreset(
        id = "dawn",
        name = "晨曦",
        requirement = "连续记账 30 天",
        light = PresetTheme("晨曦", themeColor = Color(0xFFEF7D68), borderColor = Color(0xFFF8E3DC), iconColor = Color(0xFFD9644F)),
        dark = PresetTheme("晨曦", themeColor = Color(0xFFF5A08C), borderColor = Color(0xFF513B34), iconColor = Color(0xFFF7AC9A)),
    ),
    UnlockableThemePreset(
        id = "mint",
        name = "薄荷",
        requirement = "累计无消费 100 天",
        light = PresetTheme("薄荷", themeColor = Color(0xFF2FA98C), borderColor = Color(0xFFDCEFE8), iconColor = Color(0xFF238A72)),
        dark = PresetTheme("薄荷", themeColor = Color(0xFF5BC4AB), borderColor = Color(0xFF32473F), iconColor = Color(0xFF7BD4BE)),
    ),
    UnlockableThemePreset(
        id = "amber",
        name = "琥珀",
        requirement = "连续 3 个月预算不超支",
        light = PresetTheme("琥珀", themeColor = Color(0xFFC08A3F), borderColor = Color(0xFFF1E5D0), iconColor = Color(0xFFA2732E)),
        dark = PresetTheme("琥珀", themeColor = Color(0xFFD9AC66), borderColor = Color(0xFF4A3E2C), iconColor = Color(0xFFE5BE83)),
    ),
)

/** 解锁阈值（与挑战页 ThemeUnlockState 的口径逐字一致）。 */
private const val DAWN_STREAK_REQUIREMENT = 30
private const val MINT_NO_SPEND_REQUIREMENT = 100

/**
 * 成就解锁预设行（接在常规预设之后）：未解锁=名称灰显 + 条件文案 + 锁图标，点击 Toast「完成 XX 后解锁」；
 * 解锁后与常规预设行同构（色点 + 「应用」），点击按当前明暗态整套应用。
 */
@Composable
private fun UnlockablePresetRows(
    signals: ThemeUnlockSignals,
    isDark: Boolean,
    onApply: (PresetTheme) -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        UnlockableThemePresets.forEachIndexed { index, preset ->
            if (index > 0) RinklDivider()
            val unlocked = when (preset.id) {
                "dawn" -> signals.streakDays >= DAWN_STREAK_REQUIREMENT
                "mint" -> signals.totalNoSpendDays >= MINT_NO_SPEND_REQUIREMENT
                else -> signals.threeMonthNoOverrun
            }
            val colors = if (isDark) preset.dark else preset.light
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (unlocked) {
                            onApply(colors)
                        } else {
                            Toast.makeText(context, "完成「${preset.requirement}」后解锁", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.name,
                        fontSize = 16.sp,
                        color = if (unlocked) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = if (unlocked) "已达成「${preset.requirement}」" else "${preset.requirement}解锁",
                        fontSize = 12.sp,
                        color = if (unlocked) {
                            LocalRinklColors.current.themeColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (unlocked) {
                    listOfNotNull(colors.themeColor, colors.iconColor, colors.borderColor).forEach { c ->
                        ColorDot(color = c, size = 22.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("应用", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock),
                        contentDescription = "未解锁",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
