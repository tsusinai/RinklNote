package com.example.rinklnote.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 「自定义主题」可调的 8 个槽位。与设置项一一对应，也是 DataStore 的存储键。
 *
 * 设计约束：
 * - 每个槽位**默认为 null**（未自定义）→ 渲染时回落到各处的既有默认色，保证「不动设置 = 观感不变」。
 * - 用户在自定义主题页选了颜色才写入。
 */
enum class RinklThemeSlot {
    FONT,    // 1. 字体颜色（正文/标题）
    PRIMARY, // 2. 主题色（按钮、开关、选中态、指示条）
    TOP_BAR, // 3. 顶栏标题色（无自选背景时）
    ICON,    // 4. 图标 / 按钮色
    BORDER,  // 5. 边框色（分割线跟随）
    HEATMAP, // 6. 热力图颜色（月历格子按支出强度自动分配深浅/透明度）
    CHART,   // 7. 折线/柱状图颜色（不影响饼图配色）
    NAV_ICON // 8. 底栏导航图标色（独立于字体色/图标按钮色）
}

/**
 * 全局自定义配色（运行时解析后的结果，非空）。
 *
 * - [fontColor] / [themeColor] / [topBarTitleColor] / [borderColor] / [dividerColor] 一定非空，
 *   由 [rinklColorsOf] 按「用户覆盖 ?: 默认」算出。
 * - [iconButtonColor] 允许为 null：默认不加 tint，各处按自己的既有默认色（设置页图标=主题色、
 *   底栏图标=文字色），一旦用户自定义则全 App 统一生效。
 * - [navIconColor] 允许为 null：底栏导航图标的独立色槽。null = 未自定义，消费端回落现状
 *   （`iconButtonColor ?: onSurface`）；不随 dark 反转（null 时消费端自会按当前明暗取色）。
 * - [heatmapColor] / [chartColor] 一定非空：未自定义时取各自的现状默认色
 *   （热力=Blue40、图表=折线红 [DefaultChartColor]），深浅/高亮由使用方组件处理。
 */
@Immutable
data class RinklColors(
    val fontColor: Color,
    val themeColor: Color,
    val topBarTitleColor: Color,
    val iconButtonColor: Color?,
    val borderColor: Color,
    val dividerColor: Color,
    val heatmapColor: Color,
    val chartColor: Color,
    val navIconColor: Color? = null
) {
    /** 无自选背景（纯白/纯黑底）时顶栏标题色；滚动后略淡，保留层级。 */
    val topBarTitleColorScrolled: Color get() = topBarTitleColor.copy(alpha = 0.62f)
}

/**
 * 全局自定义配色的 CompositionLocal。默认值给出「未自定义」时的标准色，
 * 便于 `@Preview` 与未包裹主题的局部组件直接取值。
 */
val LocalRinklColors = staticCompositionLocalOf { defaultRinklColors(dark = false) }

/** 默认（未自定义）配色。[dark] 只影响字体色/顶栏色这类需要随明暗反转的槽位。 */
fun defaultRinklColors(dark: Boolean): RinklColors = RinklColors(
    fontColor = if (dark) Color.White else Color.Black,
    themeColor = Blue80,
    topBarTitleColor = if (dark) Color.White else Color.Black,
    iconButtonColor = null,
    // 2026-09-14 起未自定义 = 卡片默认**不描边**（透明）；想要框线在自定义主题 → 边框色里选。
    borderColor = Color.Transparent,
    dividerColor = DefaultDividerGray,
    // 热力/图表两槽不随明暗反转：热力图浅底→槽色做深浅渐变、图表色本身够深，明暗底上都可读。
    heatmapColor = Blue40,
    chartColor = DefaultChartColor,
    // 导航图标槽默认 null（未自定义）：消费端回落现状，不随 dark 反转（回落色由消费端按明暗自取）。
    navIconColor = null
)

/**
 * 按用户覆盖算出最终配色。
 *
 * - 分割线**跟随边框色**：用户没改边框 → 用「每日账单」的同款灰；改了 → 与边框同色。
 * - 例外：边框色被设为**透明**（用户显式「不描边」，也是未自定义时的默认值）时，
 *   分割线**回落到默认灰**——分割线是行与行之间的可读性依赖，不能跟着一起消失。
 */
fun rinklColorsOf(
    dark: Boolean,
    themeColor: Color? = null,
    fontColor: Color? = null,
    topBarTitleColor: Color? = null,
    iconButtonColor: Color? = null,
    borderColor: Color? = null,
    heatmapColor: Color? = null,
    chartColor: Color? = null,
    navIconColor: Color? = null
): RinklColors {
    val baseFont = if (dark) Color.White else Color.Black
    val resolvedFont = fontColor ?: baseFont
    return RinklColors(
        fontColor = resolvedFont,
        themeColor = themeColor ?: Blue80,
        // 未单独设顶栏色时跟随字体色——顶栏标题本身就是正文。
        topBarTitleColor = topBarTitleColor ?: resolvedFont,
        iconButtonColor = iconButtonColor,
        // 未自定义 = 不描边（透明）；显式透明时分割线回落默认灰（见上）。
        borderColor = borderColor ?: Color.Transparent,
        dividerColor = borderColor?.takeIf { it.alpha > 0f } ?: DefaultDividerGray,
        // 热力/图表两槽不随明暗反转，未自定义取现状默认观感（热力 Blue40、图表折线红）。
        heatmapColor = heatmapColor ?: Blue40,
        chartColor = chartColor ?: DefaultChartColor,
        // 导航图标槽：null = 未自定义（消费端回落现状），透传即可，不随 dark 反转。
        navIconColor = navIconColor
    )
}

/**
 * 「色板/圆点」的**内置描边灰**：一档可辨识的浅灰 `#CFCFCF`。
 *
 * 2026-09-14 起：未自定义时全 App 卡片默认**不描边**（边框槽默认透明），本常量不再作为
 * 卡片默认框线，仅保留两处用途——
 * 1. 「自定义主题」页色点/透明棋盘格的描边（保证白色/浅色圆点在浅底上有边界）；
 * 2. 「推荐配色」预设里的边框色（预设显式给卡片配同色系浅框）。
 * 想要卡片框线直接在「自定义主题 → 边框色」里选色，或选「透明」恢复不描边。
 */
val DefaultCardBorder = Color(0xFFCFCFCF)

/** 「每日账单」卡片里那根分割线的同款色值（#A5A5A5 发丝灰）。 */
val DefaultDividerGray = Color(0xFFA5A5A5)

/**
 * 折线/柱状图槽（[RinklThemeSlot.CHART]）的**默认色**：月度明细图表现用的深红 `#CA3032`。
 * 取现值是为了「未自定义 = 观感不变」（今天的 primary 高亮不受本槽影响）；饼图配色
 * （PiePalette）独立于本槽，永远不跟随。
 */
val DefaultChartColor = Color(0xFFCA3032)

// ---------------------------------------------------------------------------
// 颜色与十六进制字符串互转（DataStore 存储用 "#AARRGGBB"）
// ---------------------------------------------------------------------------

/** `Color` → `"#AARRGGBB"`。 */
fun colorToHex(color: Color): String = String.format("#%08X", color.toArgb())

/** `"#AARRGGBB"` / `"#RRGGBB"` → `Color`；无法解析返回 null。 */
fun hexToColor(hex: String?): Color? {
    val raw = hex?.trim()?.removePrefix("#") ?: return null
    if (raw.isEmpty()) return null
    return runCatching {
        when (raw.length) {
            6 -> Color(0xFF000000.toInt() or raw.toLong(16).toInt())
            8 -> Color(raw.toLong(16).toInt())
            else -> null
        }
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// 预设色板 & 整套预设配色（自定义主题页用）
// ---------------------------------------------------------------------------

/** 自选色盘之外的快捷预设色（一行 8 个 × 2 行）。 */
val PresetSwatches: List<Color> = listOf(
    Color(0xFF000000), Color(0xFF555555), Color(0xFF9E9E9E), Color(0xFFCFCFCF),
    Color(0xFFEAEAEA), Color(0xFFFFFFFF), Color(0xFFE53935), Color(0xFFFF7043),
    Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF00897B), Color(0xFF29B6F6),
    Color(0xFF1E88E5), Color(0xFF3949AB), Color(0xFF8E24AA), Color(0xFFD81B60)
)

/**
 * 一整套预设配色：一键把 5 个槽位写成同一风格。
 * [font] / [topBar] / [icon] 为 null 表示「跟随各自默认」。
 */
@Immutable
data class PresetTheme(
    val name: String,
    val themeColor: Color,
    val borderColor: Color,
    val iconColor: Color? = null,
    val fontColor: Color? = null,
    val topBarColor: Color? = null
)

/** 内置的几套预设配色（浅色底为主，边框都取同色系的极浅版本）。 */
val PresetThemes: List<PresetTheme> = listOf(
    PresetTheme(name = "清新蓝", themeColor = Blue80, borderColor = DefaultCardBorder, iconColor = Blue40),
    PresetTheme(name = "暖阳橙", themeColor = Color(0xFFF2994A), borderColor = Color(0xFFF2E0CE), iconColor = Color(0xFFDE8A38)),
    PresetTheme(name = "森野绿", themeColor = Color(0xFF3FAE7A), borderColor = Color(0xFFD9EAE1), iconColor = Color(0xFF2F8F63)),
    PresetTheme(name = "暮云紫", themeColor = Color(0xFF8E6FCB), borderColor = Color(0xFFE3DDF1), iconColor = Color(0xFF7A5CBB)),
    PresetTheme(name = "石墨灰", themeColor = Color(0xFF5A6472), borderColor = Color(0xFFDFE2E6), iconColor = Color(0xFF4A5260))
)
