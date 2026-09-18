package com.example.rinklnote.util

/**
 * 支付通知文本解析（纯 JVM，可单测）——「支付通知监听记账」的核心规则。
 *
 * 场景：微信 / 支付宝 / 银行 App 的支付（收款）系统通知，如
 * - 微信：「微信支付凭证」标题 + 「￥25.00」「商户：全家便利店」正文；
 * - 支付宝：「支付成功」+「35.50元」；
 * - 银行：「您尾号1234的账户9月18日支出100.00元」（商家常缺省）。
 *
 * 产出：金额（分）+ 收支方向 + 商家 + 是否可信解析。金额提取规则与
 * [ShareTextParser] 同源（标签行 / 货币符号 / 单位后缀三档，**不做裸数字兜底**，
 * 银行通知里的卡号尾号、日期数字绝不误当金额）；方向按关键词判定
 * （收款 / 到账 / 入账 → 收入，其余默认支出）。
 *
 * 解析不出金额 → matched = false，调用方只弹通用「记一笔」入口不带预填（产品口径）。
 * 真机待办：微信 / 支付宝真实通知样例采集后按需收紧规则。
 */
object PayNotificationParser {

    /** 解析结果。[amountMinor] 为整数分（Long，本仓库金额唯一口径）。 */
    data class PayNotification(
        val matched: Boolean,
        val amountMinor: Long? = null,
        /** "EXPENSE" / "INCOME"，与 BillType.value 对齐。 */
        val billType: String = "EXPENSE",
        val merchant: String? = null
    )

    /** 金额标签行（合计/实付/金额/收款金额/交易金额 等）。 */
    private val labeledAmountRegex =
        Regex("""(?:收款金额|交易金额|支付金额|合计|总计|实付|应付|金额)[:：]?\s*[¥￥]?\s*(\d+(?:\.\d{1,2})?)""")

    /** 货币符号前缀（微信通知常见「￥25.00」）。 */
    private val symbolAmountRegex = Regex("""[¥￥]\s*(\d+(?:\.\d{1,2})?)""")

    /** 单位后缀（银行通知常见「支出100.00元」）。 */
    private val unitAmountRegex = Regex("""(\d+(?:\.\d{1,2})?)\s*[元圆块]""")

    /** 收入方向关键词（命中其一即视为收入）。 */
    private val incomeKeywords = listOf("收款", "到账", "入账", "收入", "退回", "退款", "红包")

    /** 商家 / 对方标签行。 */
    private val merchantRegex = Regex("""(?:商户|收款方|商家|对方)[:：]?\s*(\S{1,20})""")

    fun parse(title: String?, text: String?): PayNotification {
        val body = listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }, text?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" ")
        if (body.isEmpty()) return PayNotification(matched = false)

        val yuan = labeledAmountRegex.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: symbolAmountRegex.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: unitAmountRegex.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
        val amountMinor = yuan?.let { Money.yuanToMinor(it) } ?: return PayNotification(matched = false)

        val billType = if (incomeKeywords.any { body.contains(it) }) "INCOME" else "EXPENSE"
        val merchant = merchantRegex.find(body)?.groupValues?.get(1)
        return PayNotification(matched = true, amountMinor = amountMinor, billType = billType, merchant = merchant)
    }
}
