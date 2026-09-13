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
    "医疗" -> R.drawable.ic_category_medical
    "居家" -> R.drawable.ic_category_home
    "人情" -> R.drawable.ic_category_social
    "宠物" -> R.drawable.ic_category_pet
    "美妆个护" -> R.drawable.ic_category_beauty
    "服饰" -> R.drawable.ic_category_clothing
    "母婴" -> R.drawable.ic_category_baby
    "汽车" -> R.drawable.ic_category_car
    "数码" -> R.drawable.ic_category_digital
    "保险" -> R.drawable.ic_category_insurance
    "旅行" -> R.drawable.ic_category_travel
    "工资" -> R.drawable.ic_category_salary
    "兼职" -> R.drawable.ic_category_parttime
    "理财" -> R.drawable.ic_category_finance
    "其他" -> R.drawable.ic_category_other
    "报销" -> R.drawable.ic_category_reimburse
    "二手转卖" -> R.drawable.ic_category_resale
    "红包礼金" -> R.drawable.ic_category_redpacket
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
