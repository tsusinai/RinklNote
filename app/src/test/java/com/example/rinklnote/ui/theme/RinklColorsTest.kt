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

    /** 未自定义时：边框=内置默认浅灰、分割线=「每日账单」灰、图标色未指定（各处用自己的默认）。 */
    @Test
    fun defaultsUseProfileBorderAndDailyBillDivider() {
        val c = rinklColorsOf(dark = false)
        assertEquals(DefaultCardBorder, c.borderColor)
        assertEquals(DefaultDividerGray, c.dividerColor)
        assertNull(c.iconButtonColor)
        assertEquals(Blue80, c.themeColor)
        assertEquals(Color.Black, c.fontColor)
    }

    /**
     * 默认边框必须是一档**可辨识的中性灰** `#CFCFCF`。
     *
     * 缘由：边框规则已统一为「有/无自选背景同一套色」，而 `#EAEAEA` 这类近白灰在自选照片上
     * 几乎看不出边界（2026-09-11 用户实测后拍板抬深一档）。默认值一动即影响全 App 卡片边界，
     * 所以在这里用色值钉死，避免被顺手改回去。
     */
    @Test
    fun defaultBorderIsVisibleNeutralGray() {
        assertEquals(Color(0xFFCFCFCF), DefaultCardBorder)
        assertEquals(DefaultCardBorder, rinklColorsOf(dark = false).borderColor)
        // 中性灰：三通道相等，加在照片上不会被照片色相带跑。
        assertEquals(DefaultCardBorder.red, DefaultCardBorder.green, 0.001f)
        assertEquals(DefaultCardBorder.green, DefaultCardBorder.blue, 0.001f)
    }

    /** 深色模式默认：字体色反白、边框改用白色低透明度（暗底上黑框线不可见）。 */
    @Test
    fun darkDefaultsFlipFontAndBorder() {
        val c = rinklColorsOf(dark = true)
        assertEquals(Color.White, c.fontColor)
        assertTrue("深色底需要浅色边框", c.borderColor.red > 0.5f)
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
