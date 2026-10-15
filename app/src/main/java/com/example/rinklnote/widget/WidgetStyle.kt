package com.example.rinklnote.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.color.ColorProvider
import com.example.rinklnote.ui.theme.billCategoryIconColor
import com.example.rinklnote.ui.theme.defaultRinklColors

/** 四款桌面组件共用尺度与配色；默认蓝色及分类色复用应用主题令牌。 */
internal object WidgetStyle {
    val Radius = 20.dp
    val ActionRadius = 8.dp
    val Padding = 12.dp
    private val light = defaultRinklColors(false)
    private val dark = defaultRinklColors(true)
    val Surface = ColorProvider(Color.White, Color(0xFF1B222C))
    val Ink = ColorProvider(Color(0xFF202A35), Color(0xFFEDF3FA))
    val Muted = ColorProvider(Color(0xFF69788B), Color(0xFFAAB8CA))
    val Neutral = ColorProvider(Color(0xFFF3F6FA), Color(0xFF283241))
    val Divider = ColorProvider(Color(0xFFE7EDF4), Color(0xFF344254))
    val PrimarySoft = ColorProvider(Color(0xFFE3F1FF), Color(0xFF253E58))
    val PrimaryInk = ColorProvider(Color(0xFF2468AC), Color(0xFFB3DCFF))
    val Primary = ColorProvider(light.themeColor, dark.themeColor)
    val Expense = ColorProvider(light.expenseColor, dark.expenseColor)
    val Income = ColorProvider(light.incomeColor, dark.incomeColor)

    fun category(name: String) = ColorProvider(
        light.billCategoryIconColor(name, false), dark.billCategoryIconColor(name, true)
    )
}
