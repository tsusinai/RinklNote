package com.example.rinklnote.domain

/** 账户图标协议 key。Android、服务端与 Web 必须保持一致。 */
const val ACCOUNT_ICON_WALLET = "WALLET"
const val ACCOUNT_ICON_BANK_CARD = "BANK_CARD"
const val ACCOUNT_ICON_CASH = "CASH"
const val ACCOUNT_ICON_WECHAT = "WECHAT"
const val ACCOUNT_ICON_ALIPAY = "ALIPAY"
const val ACCOUNT_ICON_CREDIT_CARD = "CREDIT_CARD"
const val ACCOUNT_ICON_INVESTMENT = "INVESTMENT"
const val ACCOUNT_ICON_OTHER = "OTHER"

val ACCOUNT_ICON_KEYS = listOf(
    ACCOUNT_ICON_WALLET,
    ACCOUNT_ICON_BANK_CARD,
    ACCOUNT_ICON_CASH,
    ACCOUNT_ICON_WECHAT,
    ACCOUNT_ICON_ALIPAY,
    ACCOUNT_ICON_CREDIT_CARD,
    ACCOUNT_ICON_INVESTMENT,
    ACCOUNT_ICON_OTHER,
)

/** 未知 key 统一回退到钱包。 */
fun normalizeAccountIconKey(iconKey: String?): String =
    iconKey?.takeIf { it in ACCOUNT_ICON_KEYS } ?: ACCOUNT_ICON_WALLET

/** 旧服务端没有 iconKey 时，按账户名推断一个合理默认值。 */
fun inferAccountIconKey(name: String): String = when (name) {
    "微信" -> ACCOUNT_ICON_WECHAT
    "支付宝" -> ACCOUNT_ICON_ALIPAY
    "无账户" -> ACCOUNT_ICON_OTHER
    else -> ACCOUNT_ICON_WALLET
}

/** 新字段优先；缺失或非法时按名称兼容旧数据。 */
fun resolveAccountIconKey(iconKey: String?, name: String): String =
    iconKey?.takeIf { it in ACCOUNT_ICON_KEYS } ?: inferAccountIconKey(name)
