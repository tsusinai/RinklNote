package com.example.rinklnote.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** 轮盘标签固定使用白底，深色模式下也保留足够的文字对比。 */
internal object BillWheelColors {
    val LabelBackground = Color.White
    val LabelContent = Color(0xFF132238)
    val LabelBorder = Color(0xFFDCE5EF)
}

/** 账单分类图标语义色：仅提供默认配色，不增加自定义主题槽位。 */
private object BillIconPalette {
    val Orange = Color(0xFFD88A32)
    val Blue = Color(0xFF458FD0)
    val Rose = Color(0xFFD6667E)
    val Green = Color(0xFF4A9B73)
    val Teal = Color(0xFF399E9C)
    val Purple = Color(0xFF9270C6)
    val Gold = Color(0xFFC3993A)
    val Gray = Color(0xFF8793A4)
}

/** 用户显式设置的 ICON 槽优先；默认分类色在深色主题下轻微提亮。 */
fun RinklColors.billCategoryIconColor(categoryName: String, dark: Boolean): Color {
    iconButtonColor?.let { return it }
    val base = when (categoryName) {
        "三餐", "餐饮", "母婴", "宠物" -> BillIconPalette.Orange
        "交通", "出行", "汽车", "居家", "数码" -> BillIconPalette.Blue
        "网购", "购物", "美妆个护", "服饰", "医疗", "人情", "红包礼金" -> BillIconPalette.Rose
        "运动", "工资", "兼职", "报销", "二手转卖" -> BillIconPalette.Green
        "日用", "旅行", "理财" -> BillIconPalette.Teal
        "娱乐" -> BillIconPalette.Purple
        "学习", "保险" -> BillIconPalette.Gold
        else -> BillIconPalette.Gray
    }
    return if (dark) lerp(base, Color.White, 0.2f) else base
}
