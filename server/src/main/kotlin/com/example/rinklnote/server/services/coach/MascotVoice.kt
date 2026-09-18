package com.example.rinklnote.server.services.coach

import com.example.rinklnote.server.services.Money

/**
 * 小盘人格化文案层（2026-09-18 Task 1.5）：四通道推送 / 回执 / 告警的统一口吻出口。
 * 口吻细则见 `docs/小盘语气指南.md`（称呼、口癖、禁止项、固定锚点）。
 *
 * 约定：
 * - 文案函数全部为纯函数（可单测：非空、锚点关键词、无连续感叹号）；
 * - 「已记录 / 已删除 / 已撤销最近一单」等锚点是既有用户认知与测试锁定项，改口吻不改锚点；
 * - 新场景先在这里落函数再接线，处理器/路由里不裸写推送文案。
 */
object MascotVoice {

    /** 周报标题（CoachService.weeklyPushCopy 使用）。 */
    const val WEEKLY_REPORT_TITLE = "🧾 小盘周报"

    /** 异常告警标题（AlertNotifier.buildAlertText 使用）。 */
    const val ALERT_TITLE = "🚨 小盘异常汇报"

    /** 记账成功回执（随机三选一，全部含锚点「已记录」）。 */
    fun bookkeepingReceipt(categoryName: String, amountMinor: Long): String = listOf(
        "已记录：$categoryName ¥${Money.format(amountMinor)}，小盘帮你盯着呢～",
        "已记录成功～ $categoryName ¥${Money.format(amountMinor)}，记完心里就有数啦",
        "好嘞，已记录 $categoryName ¥${Money.format(amountMinor)}"
    ).random()

    /** 删除回执（含锚点「已删除」；dateText 形如「9/18」，可空）。 */
    fun deleteReceipt(categoryName: String, amountMinor: Long, dateText: String?): String {
        val suffix = if (dateText.isNullOrBlank()) "" else "（$dateText）"
        return "已删除：$categoryName ¥${Money.format(amountMinor)}$suffix"
    }

    /** 撤销最近一单回执（随机两选一，全部含锚点「已撤销最近一单」）。 */
    fun undoReceipt(categoryName: String, amountMinor: Long, dateText: String?): String {
        val suffix = if (dateText.isNullOrBlank()) "" else "（$dateText）"
        return listOf(
            "已撤销最近一单：$categoryName ¥${Money.format(amountMinor)}$suffix",
            "好嘞，已撤销最近一单 $categoryName ¥${Money.format(amountMinor)}$suffix，少一笔是一笔～"
        ).random()
    }

    /** 问候语（bot 开场）。 */
    fun greeting(): String = listOf(
        "你好呀，我是小盘～ 你的记账小帮手！说「午餐20元」就帮你记，问「这个月花了多少」我帮你查",
        "嗨，小盘在呢～ 记账、查账、删账、总结都在行，说「打车25元」试试",
        "在呢～ 需要记账直接说金额，比如「午餐20元」；想知道小盘会什么，回复【帮助】"
    ).random()

    /**
     * 语气清洗：把既有的模板文案统一到小盘口吻的安全网——去连续感叹号、去句尾生硬的
     * 「哦。」改「哦～」。给尚未完全迁移的 provider 模板兜底用。
     */
    fun soften(text: String): String = text
        .replace(Regex("！{2,}"), "！")
        .replace(Regex("!{2,}"), "！")
        .replace(Regex("哦。$"), "哦～")
        .trim()
}
