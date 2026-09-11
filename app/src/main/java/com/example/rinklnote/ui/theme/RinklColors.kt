package com.example.rinklnote.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 「自定义主题」可调的 5 个槽位。与设置项一一对应，也是 DataStore 的存储键。
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
    BORDER   // 5. 边框色（分割线跟随）
}

/**
 * 全局自定义配色（运行时解析后的结果，非空）。
 *
 * - [fontColor] / [themeColor] / [topBarTitleColor] / [borderColor] / [dividerColor] 一定非空，
 *   由 [rinklColorsOf] 按「用户覆盖 ?: 默认」算出。
 * - [iconButtonColor] 允许为 null：默认不加 tint，各处按自己的既有默认色（设置页图标=主题色、
 *   底栏图标=文字色），一旦用户自定义则全 App 统一生效。
 */
@Immutable
data class RinklColors(
    val fontColor: Color,
    val themeColor: Color,
    val topBarTitleColor: Color,
    val iconButtonColor: Color?,
    val borderColor: Color,
    val dividerColor: Color
) {
    /** 无自选背景（纯白/纯黑底）时顶栏标题色；滚动后略淡，保留层级。 */
    val topBarTitleColorScrolled: Color get() = topBarTitleColor.copy(alpha = 0.62f)
}

/**
 * 全局自定义配色的 CompositionLocal。默认值给出「未自定义」时的标准色，
 * 便于 `@Preview` 与未包裹主题的局部组件直接取值。
 */
val LocalRinklColors = staticCompositionLocalOf { defaultRinklColors(dark = false) }

/** 默认（未自定义）配色。[dark] 只影响字体色/边框色这类需要随明暗反转的槽位。 */
fun defaultRinklColors(dark: Boolean): RinklColors = RinklColors(
    fontColor = if (dark) Color.White else Color.Black,
    themeColor = Blue80,
    topBarTitleColor = if (dark) Color.White else Color.Black,
    iconButtonColor = null,
    borderColor = if (dark) Color(0x1FFFFFFF) else DefaultCardBorder,
    dividerColor = DefaultDividerGray
)

/**
 * 按用户覆盖算出最终配色。
 *
 * - 分割线**跟随边框色**：用户没改边框 → 用「每日账单」的同款灰；改了 → 与边框同色。
 * - 例外：边框色被设为**透明**（用户显式「不描边」）时，分割线**回落到默认灰**——
 *   分割线是行与行之间的可读性依赖，不能跟着一起消失。
 */
fun rinklColorsOf(
    dark: Boolean,
    themeColor: Color? = null,
    fontColor: Color? = null,
    topBarTitleColor: Color? = null,
    iconButtonColor: Color? = null,
    borderColor: Color? = null
): RinklColors {
    val baseFont = if (dark) Color.White else Color.Black
    val resolvedFont = fontColor ?: baseFont
    return RinklColors(
        fontColor = resolvedFont,
        themeColor = themeColor ?: Blue80,
        // 未单独设顶栏色时跟随字体色——顶栏标题本身就是正文。
        topBarTitleColor = topBarTitleColor ?: resolvedFont,
        iconButtonColor = iconButtonColor,
        borderColor = borderColor ?: if (dark) Color(0x1FFFFFFF) else DefaultCardBorder,
        dividerColor = borderColor?.takeIf { it.alpha > 0f } ?: DefaultDividerGray
    )
}

/**
 * 卡片边框的**内置默认色**：一档可辨识的浅灰 `#CFCFCF`。
 *
 * 语义 = 「未自定义主题」时、以及「恢复默认」后，全 App 卡片框线用的色值
 * （[defaultRinklColors] / [rinklColorsOf] 都引用本常量，保证只有一处定义）。
 *
 * 为什么不是更浅的 `#EAEAEA`（2026-09-11 用户拍板调整）：`#EAEAEA` 在纯白底上尚可，
 * 但在**自选背景照片**上几乎看不出边界——而边框规则已统一为「有无背景同一套色」，
 * 所以整体抬深一档到 `#CFCFCF`，白底上仍克制、照片上也能立住。
 * 想要更浅/更深直接在「自定义主题 → 边框色」里覆盖，或选「透明」不描边。
 */
val DefaultCardBorder = Color(0xFFCFCFCF)

/** 「每日账单」卡片里那根分割线的同款色值（#A5A5A5 发丝灰）。 */
val DefaultDividerGray = Color(0xFFA5A5A5)

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
