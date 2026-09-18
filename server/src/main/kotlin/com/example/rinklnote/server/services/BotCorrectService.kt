package com.example.rinklnote.server.services

/**
 * Bot 多轮修正 —— 「最近一单」的 改金额 / 改分类 / 删上一笔 / 撤销（2026-09-18 Task 1.1）。
 *
 * 四通道处理器（QQ / 飞书 / 企微 / 订阅号）在推送开关、登录码短路之后、自然语言路由之前，
 * 统一调用 [handle]：命中修正指令 → 返回回执文案（含改前→改后摘要）；未命中 → 返回 null，
 * 处理器照旧走 PhoneIntentRouter。**执行逻辑只此一份**，通道处理器零复制。
 *
 * 「最近一单」不新增存储：直接查该用户最新一笔未删账单（[BillService.latestBill]）。
 * 软删后该账单不可再被修改/删除（update/delete 均带 `deleted = false` 条件），且「最近一单」
 * 自动落到再前一笔 —— 撤销两笔 = 连发两次「撤销」。
 *
 * 回执格式（改前→改后摘要）：「已修改：三餐 ¥20.00 → 交通 ¥20.00」；
 * 金额类：「已修改：三餐 ¥20.00 → ¥30.00」；删除类：「已撤销最近一单：…」。
 */
object BotCorrectService {

    /** 入口：命中修正指令返回回执文案，未命中返回 null（调用方继续原有路由）。 */
    fun handle(content: String, userId: Long, billService: BillService): String? = when {
        BotCommands.EDIT_AMOUNT.containsMatchIn(content) -> editAmount(content, userId, billService)
        BotCommands.EDIT_CATEGORY.containsMatchIn(content) -> editCategory(content, userId, billService)
        BotCommands.DELETE_LAST.containsMatchIn(content) -> deleteLast(userId, billService)
        else -> null
    }

    // ── 改金额 ──

    private fun editAmount(content: String, userId: Long, billService: BillService): String {
        val yuan = BotCommands.EDIT_AMOUNT.find(content)?.groupValues?.get(1)?.toDoubleOrNull()
        if (yuan == null || yuan <= 0) {
            return "没看懂要改成多少～ 说「改金额30」或「金额改成25.5」就行"
        }
        val target = billService.latestBill(userId)
            ?: return "还没有账单可以修正，先记一笔吧～"
        val before = target.amountMinor
        val updated = billService.updateBillAmount(target.id, userId, Money.toMinor(yuan))
            ?: return "最近一单刚被删掉了，没有可修正的账单～"
        return "已修改：${target.categoryName} ¥${Money.format(before)} → ¥${Money.format(updated.amountMinor)}"
    }

    // ── 改分类 ──

    private fun editCategory(content: String, userId: Long, billService: BillService): String {
        val rawName = BotCommands.EDIT_CATEGORY.find(content)?.groupValues?.get(1)?.trim()
        if (rawName.isNullOrEmpty()) {
            return "没看懂要改成哪个分类～ 说「改分类 交通」就行（分类名参考 App 分类页）"
        }
        val target = billService.latestBill(userId)
            ?: return "还没有账单可以修正，先记一笔吧～"
        val cat = resolveCategory(rawName, billService)
            ?: return "没有找到「$rawName」这个分类～ 说「改分类 分类名」，分类名参考 App 分类页（如 三餐/交通/日用）"
        val updated = billService.updateBillCategory(target.id, userId, cat.first, cat.second)
            ?: return "最近一单刚被删掉了，没有可修正的账单～"
        return "已修改：${target.categoryName} ¥${Money.format(target.amountMinor)} → " +
            "${updated.categoryName} ¥${Money.format(updated.amountMinor)}"
    }

    /**
     * 分类名解析：先精确匹配，再用「输入包含分类名」做包含匹配（容忍「改分类交通费」这类
     * 带后缀的输入）。包含匹配出现多个候选视为歧义，返回 null 让用户说得更具体。
     */
    private fun resolveCategory(rawName: String, billService: BillService): Pair<Long, String>? {
        val categories = billService.getCategories()
        categories.firstOrNull { it.name == rawName }?.let { return it.id to it.name }
        val partial = categories.filter { rawName.contains(it.name) }
        return partial.singleOrNull()?.let { it.id to it.name }
    }

    // ── 删上一笔 / 撤销 ──

    private fun deleteLast(userId: Long, billService: BillService): String {
        val target = billService.latestBill(userId)
            ?: return "还没有账单可以撤销，先记一笔吧～"
        return if (billService.deleteBill(target.id, userId)) {
            "已撤销最近一单：${target.categoryName} ¥${Money.format(target.amountMinor)}（${formatDate(target.date)}）"
        } else {
            "撤销失败，最近一单可能刚被其他设备删掉，稍后再试～"
        }
    }

    /** epoch 毫秒 → 「M/d」（业务时区），与账单回执既有格式一致。 */
    private fun formatDate(epochMs: Long): String {
        val d = java.time.LocalDate.ofInstant(
            java.time.Instant.ofEpochMilli(epochMs), TimeUtil.BOOKKEEPING_ZONE
        )
        return "${d.monthValue}/${d.dayOfMonth}"
    }
}
