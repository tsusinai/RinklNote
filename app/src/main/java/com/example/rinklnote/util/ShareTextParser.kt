package com.example.rinklnote.util

/**
 * 系统分享文本 → 快速记账预填参数的解析器（纯 JVM，可单测）。
 *
 * 场景：微信 / 支付宝支付成功页、账单详情通过系统「分享」出的纯文本，以及任意
 * 用户手动分享的记账口吻文本（「打车花了15元」）。
 *
 * 金额提取顺序（命中即止，**刻意不做裸数字兜底**——账单分享文本里常含订单号 /
 * 日期 / 卡号后四位，裸取首个数字极易误当金额）：
 * 1. 「金额」标签行：`金额: 12.34`（冒号与货币符号可有可无）；
 * 2. 货币符号前缀：`¥12.34`（全角 / 半角均认）；
 * 3. 带单位后缀（阿拉伯数字）：`12.34元/圆/块`（与语音记账同款规则）；
 * 4. 带单位后缀（中文数字）：`十五元/贰拾块`（复用 [VoiceParser.cnNumToDouble] 归一）。
 *
 * 分类关键词直接复用 [VoiceParser] 的词表（分享文本里的商户名常含「超市 / 外卖 /
 * 打车」等口语关键词，规则同源不另维护一套）。
 *
 * 未解析出金额时不阻塞预填：amount = null，调用方把原文放入备注（产品口径：
 * 「先记下来，金额回头补」）。商户名解析自 `商户/收款方/商家/对方` 标签行。
 *
 * 真机待办：微信 / 支付宝真实分享样例采集后按需收紧规则（当前按公开常见格式编写）。
 */
object ShareTextParser {

    /** 解析结果（amount 与 [VoiceResult.amount] 同口径，「元」浮点，仅供预填输入框）。 */
    data class ShareParseResult(
        val amount: Double?,
        val categoryName: String?,
        val remark: String,
        val merchant: String?
    )

    /** 备注长度上限：超长分享文本（网页摘要等）截断，避免抽屉备注框溢出。 */
    private const val REMARK_MAX = 200

    /** 「金额」标签行：冒号可有可无，货币符号可有可无。 */
    private val labeledAmountRegex = Regex("""金额[:：]?\s*[¥￥]?\s*(\d+(?:\.\d{1,2})?)""")

    /** 货币符号前缀（¥ 全角 ￥ 均认）。 */
    private val symbolAmountRegex = Regex("""[¥￥]\s*(\d+(?:\.\d{1,2})?)""")

    /** 阿拉伯数字 + 单位后缀（与 VoiceParser 同款）。 */
    private val unitAmountRegex = Regex("""(\d+\.?\d*)\s*[元圆块]""")

    /** 中文数字 + 单位后缀（字符集镜像 VoiceParser.CN_NUM_CHARS）。 */
    private val cnUnitAmountRegex = Regex("""([${VoiceParser.CN_NUM_CHARS}]+)\s*[元圆块]""")

    /** 商户 / 收款方标签行：冒号可有可无（微信常见「商户 全家」空格形态），取行内非空白值。 */
    private val merchantRegex = Regex("""(?:商户|收款方|商家|对方)[:：]?\s*(\S+)""")

    fun parse(rawText: String?): ShareParseResult {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return ShareParseResult(null, null, "", null)

        val amount = extractAmount(text)
        val merchant = merchantRegex.find(text)?.groupValues?.get(1)
        // 分类关键词：对「原文 + 商户名」整体匹配，提高「美团外卖」「永辉超市」类命中
        val categoryName = VoiceParser.parse(merchant?.let { "$it $text" } ?: text).categoryName

        // 备注策略：有商户 → 商户名；无商户但解析出金额 → 留空（金额已足够）；
        // 未解析出金额 → 原文入备注（产品口径），超长截断。
        val remark = when {
            merchant != null -> merchant
            amount != null -> ""
            else -> text.take(REMARK_MAX)
        }
        return ShareParseResult(amount, categoryName, remark, merchant)
    }

    /** 按文档顺序逐规则尝试；每档先过 [Money.isPlausibleParsedYuan]（超界数字=解析噪声，
     *  放行会在调用方转分时溢出崩溃），未命中则落到下一档；全部未命中返回 null（不做裸数字兜底）。 */
    private fun extractAmount(text: String): Double? {
        labeledAmountRegex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { Money.isPlausibleParsedYuan(it) }?.let { return it }
        symbolAmountRegex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { Money.isPlausibleParsedYuan(it) }?.let { return it }
        unitAmountRegex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { Money.isPlausibleParsedYuan(it) }?.let { return it }
        cnUnitAmountRegex.find(text)?.groupValues?.get(1)?.let { VoiceParser.cnNumToDouble(it) }
            ?.takeIf { Money.isPlausibleParsedYuan(it) }?.let { return it }
        return null
    }
}
