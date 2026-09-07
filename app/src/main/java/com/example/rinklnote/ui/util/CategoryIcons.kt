package com.example.rinklnote.ui.util

import com.example.rinklnote.R

/**
 * 分类名 → 图标 drawable resId 的纯映射（非 @Composable）。
 * App 内 QuickAdd 抽屉与主屏 Glance 小组件共用，避免两处重复维护。
 */
fun categoryIconRes(name: String): Int = when (name) {
    "三餐" -> R.drawable.ic_category_meals
    "日用" -> R.drawable.ic_category_daily
    "交通" -> R.drawable.ic_category_transport
    "学习" -> R.drawable.ic_category_study
    "运动" -> R.drawable.ic_category_sports
    "娱乐" -> R.drawable.ic_category_entertainment
    "网购" -> R.drawable.ic_category_shopping
    else -> R.drawable.ic_category_meals
}
