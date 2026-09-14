package com.example.rinklnote.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题令牌的纯函数测试：默认值、覆盖规则（分割线跟随边框色）、配色换算与 hex 往返。
 * 这些规则是「自定义主题」页与全局渲染的唯一真相源，改动时先看这里。
 */
class RinklColorsTest {

    /** 未自定义时：边框=透明（2026-09-14 拍板默认无边框）、分割线=「每日账单」灰、图标色未指定（各处用自己的默认）。 */
    @Test
    fun defaultsUseProfileBorderAndDailyBillDivider() {
        val c = rinklColorsOf(dark = false)
        assertEquals(Color.Transparent, c.borderColor)
        assertEquals(DefaultDividerGray, c.dividerColor)
        assertNull(c.iconButtonColor)
        assertEquals(Blue80, c.themeColor)
        assertEquals(Color.Black, c.fontColor)
        // 2026-09-14 新增两槽：热力图 / 折线柱状（饼图不走主题槽）。
        assertEquals(Blue40, c.heatmapColor)
        assertEquals(DefaultChartColor, c.chartColor)
        // 2026-09-14 第 8 槽 NAV_ICON：默认 null（未自定义，消费端回落现状），明暗两种模式下都不反转。
        assertNull(c.navIconColor)
        assertNull(rinklColorsOf(dark = true).navIconColor)
    }

    /** 用户给 NAV_ICON 槽选了色 → navIconColor 原样透传（不与图标/按钮色、字体色联动）。 */
    @Test
    fun navIconColorPassesThroughIndependently() {
        val nav = Color(0xFF00AA66)
        val c = rinklColorsOf(dark = false, navIconColor = nav)
        assertEquals(nav, c.navIconColor)
        // 独立槽：未同时设置 ICON/FONT 时不得互相串色。
        assertNull(c.iconButtonColor)
        assertEquals(Color.Black, c.fontColor)
        // 数据类默认值兜底：不传 navIconColor 的旧调用点拿到 null，行为不变。
        assertNull(rinklColorsOf(dark = false, iconButtonColor = Color(0xFF112233)).navIconColor)
    }

    /**
     * 默认边框 = 透明（无边框）。
     *
     * 缘由：2026-09-11 曾拍板默认灰 `#CFCFCF`；2026-09-14 目标文档改为「边框颜色默认无边框」，
     * 透明即用户显式「不描边」，`applyCardGlass` 对 alpha==0 不挂 1dp 线，分割线回落默认灰不受影响。
     * [DefaultCardBorder] 常量保留，仅供自定义主题页色板圆点描边等 UI 元素使用。
     */
    @Test
    fun defaultBorderIsTransparent() {
        assertEquals(Color.Transparent, rinklColorsOf(dark = false).borderColor)
        assertEquals(Color.Transparent, rinklColorsOf(dark = true).borderColor)
        // 色板描边常量仍是中性灰（三通道相等），仅作 UI 元素用。
        assertEquals(DefaultCardBorder.red, DefaultCardBorder.green, 0.001f)
        assertEquals(DefaultCardBorder.green, DefaultCardBorder.blue, 0.001f)
    }

    /** 深色模式默认：字体色反白；边框与浅色一致为透明（无边框）。 */
    @Test
    fun darkDefaultsFlipFontAndBorder() {
        val c = rinklColorsOf(dark = true)
        assertEquals(Color.White, c.fontColor)
        assertEquals(Color.Transparent, c.borderColor)
    }

    /** 用户改了边框色 → 分割线跟随（用户已确认要「跟随边框色」）。 */
    @Test
    fun dividerFollowsCustomBorderColor() {
        val custom = Color(0xFF123456)
        val c = rinklColorsOf(dark = false, borderColor = custom)
        assertEquals(custom, c.borderColor)
        assertEquals(custom, c.dividerColor)
    }

    /** 顶栏标题色默认跟随字体色（标题本身就是正文），单独设了才独立。 */
    @Test
    fun topBarTitleFallsBackToFontColor() {
        val font = Color(0xFF112233)
        val followed = rinklColorsOf(dark = false, fontColor = font)
        assertEquals(font, followed.topBarTitleColor)

        val explicit = Color(0xFFFF8800)
        val independent = rinklColorsOf(dark = false, fontColor = font, topBarTitleColor = explicit)
        assertEquals(explicit, independent.topBarTitleColor)
    }

    /** 边框色设为「透明」＝用户显式选择不描边；此时分割线必须回落到默认灰，不能跟着一起消失。 */
    @Test
    fun transparentBorderKeepsDefaultDivider() {
        val c = rinklColorsOf(dark = false, borderColor = Color.Transparent)
        assertEquals(0f, c.borderColor.alpha, 0.001f)
        assertEquals(DefaultDividerGray, c.dividerColor)
    }

    /** hex 往返不丢精度（含带 alpha 的颜色）。 */
    @Test
    fun hexRoundTripKeepsColor() {
        listOf(Color(0xFF1E88E5), Color(0x80129999), Color.Black, Color.White, DefaultDividerGray)
            .forEach { original ->
                assertEquals(original, hexToColor(colorToHex(original)))
            }
    }

    /** 非法输入返回 null；6 位十六进制自动补齐为不透明。 */
    @Test
    fun hexParsingHandlesInvalidAndShortInput() {
        assertNull(hexToColor(null))
        assertNull(hexToColor(""))
        assertNull(hexToColor("#ZZZZZZ"))
        assertNull(hexToColor("#12345"))
        assertEquals(Color(0xFFFF0000), hexToColor("#FF0000"))
        assertEquals(Color(0xFFFF0000), hexToColor("FF0000"))
    }

    /** 每套预设配色都必须给出主题色与边框色（否则预设应用后会出现半套配置）。 */
    @Test
    fun presetThemesAreComplete() {
        assertTrue(PresetThemes.isNotEmpty())
        PresetThemes.forEach { preset ->
            assertTrue(preset.name.isNotBlank())
            assertEquals(1f, preset.themeColor.alpha, 0.001f)
            assertEquals(1f, preset.borderColor.alpha, 0.001f)
        }
    }
}
