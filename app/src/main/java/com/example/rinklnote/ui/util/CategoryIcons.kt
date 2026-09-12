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

/** 账户名 → 图标 drawable resId，记账抽屉与输入法上下文共用。 */
fun accountIconRes(name: String): Int = when (name) {
    "微信" -> R.drawable.ic_wechat
    "支付宝" -> R.drawable.ic_alipay
    "默认" -> R.drawable.ic_default_account
    else -> R.drawable.ic_default_account
}
