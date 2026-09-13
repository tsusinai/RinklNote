package com.example.rinklnote.ui.util

import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.domain.ACCOUNT_ICON_ALIPAY
import com.example.rinklnote.domain.ACCOUNT_ICON_OTHER
import com.example.rinklnote.domain.ACCOUNT_ICON_WALLET
import com.example.rinklnote.domain.ACCOUNT_ICON_WECHAT
import com.example.rinklnote.domain.resolveAccountIconKey
import com.example.rinklnote.ui.component.accountIconKeyRes

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
    "微信" -> accountIconKeyRes(ACCOUNT_ICON_WECHAT)
    "支付宝" -> accountIconKeyRes(ACCOUNT_ICON_ALIPAY)
    "无账户" -> accountIconKeyRes(ACCOUNT_ICON_OTHER)
    else -> accountIconKeyRes(ACCOUNT_ICON_WALLET)
}

/** 新账户图标协议优先，旧账户数据按名称回退。 */
fun accountIconRes(account: Account): Int =
    accountIconKeyRes(resolveAccountIconKey(account.iconKey, account.name))
