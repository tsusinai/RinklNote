package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.coach.MascotVoice

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
 *
 * 阶段 2 增强（2026-10-03，QQ 消息管家）：在既有三指令**之前**追加两类模式 ——
 *  1. 多笔撤回：「撤销两笔」「删除最近3笔」→ 循环 latestBill+deleteBill 逐笔软删；
 *  2. 属性指代删除：「删除刚才买奶茶的」→ 按分类名 / 备注关键词匹配最近 10 笔删最近那笔。
 * 两者必须排在既有三指令前：「撤销两笔」包含 DELETE_LAST 的「撤销」字样，先判多笔
 * 才不会被单笔语义吞掉；既有三指令的匹配顺序与行为完全不变（回归由 BotCorrectServiceTest 把关）。
 */
object BotCorrectService {

    /** 入口：命中修正指令返回回执文案，未命中返回 null（调用方继续原有路由）。 */
    fun handle(content: String, userId: Long, billService: BillService): String? {
        // ── 阶段 2 增强：多笔撤回（先于既有三指令，否则「撤销两笔」被 DELETE_LAST 吞成单笔）──
        MULTI_UNDO.find(content)?.let { match ->
            return multiUndo(match, userId, billService)
        }
        // ── 阶段 2 增强：属性指代删除（未命中返回 null 落回旧链路）──
        referredDelete(content, userId, billService)?.let { return it }
        return when {
            BotCommands.EDIT_AMOUNT.containsMatchIn(content) -> editAmount(content, userId, billService)
            BotCommands.EDIT_CATEGORY.containsMatchIn(content) -> editCategory(content, userId, billService)
            BotCommands.DELETE_LAST.containsMatchIn(content) -> deleteLast(userId, billService)
            else -> null
        }
    }

    // ── 改金额 ──

    private fun editAmount(content: String, userId: Long, billService: BillService): String {
        val yuan = BotCommands.EDIT_AMOUNT.find(content)?.groupValues?.get(1)?.toDoubleOrNull()
        // 「元 → 分」可能失败：过小（0.001 元 → 0 分）、超大（longValueExact 溢出）。
        // 必须在这里兜住回引导文案 —— 让 IAE/ArithmeticException 穿出去只会被处理器
        // 的 catch-all 吞掉，用户收不到任何回复。
        val targetMinor = if (yuan != null && yuan > 0) {
            runCatching { Money.toMinor(yuan) }.getOrNull()
        } else null
        if (targetMinor == null || targetMinor <= 0) {
            return "没看懂要改成多少～ 说「改金额30」或「金额改成25.5」就行"
        }
        val target = billService.latestBill(userId)
            ?: return "还没有账单可以修正，先记一笔吧～"
        val before = target.amountMinor
        val updated = billService.updateBillAmount(target.id, userId, targetMinor)
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
            // 小盘人格化（Task 1.5）：撤销回执统一走 MascotVoice（锚点「已撤销最近一单」由指南锁定）
            MascotVoice.undoReceipt(
                target.categoryName, target.amountMinor, formatDate(target.date)
            )
        } else {
            "撤销失败，最近一单可能刚被其他设备删掉，稍后再试～"
        }
    }

    // ── 阶段 2 增强 · 多笔撤回 ──

    /**
     * 「撤销两笔」「删除最近3笔」「撤回前面2笔」→ 一次软删 N 笔（N ∈ 1..5）。
     * 软删后 latestBill 自然前移，循环逐次拿下一笔；中途没有账单了就报「没有更多账单可撤销」。
     * 匹配到了但笔数越界（0 / >5 / 混乱输入）→ 给引导，不落入 DELETE_LAST 免得只删一笔。
     */
    private fun multiUndo(match: MatchResult, userId: Long, billService: BillService): String {
        val requested = parseSmallCount(match.groupValues[1])
        if (requested == null || requested !in 1..5) {
            return "一次最多撤销 5 笔哦～ 回「撤销3笔」这样就行"
        }
        val lines = mutableListOf<String>()
        var done = 0
        repeat(requested) {
            val target = billService.latestBill(userId) ?: run {
                lines += "没有更多账单可撤销"
                return@repeat
            }
            if (billService.deleteBill(target.id, userId)) {
                // 逐行沿用单笔撤销回执（锚点「已撤销最近一单」保持既有用户认知）
                lines += MascotVoice.undoReceipt(target.categoryName, target.amountMinor, formatDate(target.date))
                done++
            } else {
                lines += "没有更多账单可撤销"
            }
        }
        lines += "共撤销 $done 笔"
        return lines.joinToString("\n")
    }

    /** 小数字解析：1~2 位阿拉伯数字，或 一/二/两/三/四/五；其余返回 null。 */
    private fun parseSmallCount(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        return when (raw) {
            "一" -> 1
            "二", "两" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            else -> null
        }
    }

    // ── 阶段 2 增强 · 属性指代删除 ──

    /**
     * 「删除刚才买奶茶的」「删除最近喝的咖啡那笔」→ 取关键词，在该用户最近 10 笔
     * （按日期/创建时间倒序）里按「分类名包含 || 备注包含」匹配：
     * 命中 1 笔 → 软删 + MascotVoice 删除回执（锚点「已删除」）；
     * 命中多笔 → 删最近那笔并注明「（命中 n 笔，先删了最近这单）」；
     * 0 笔 → 引导；关键词为空 / 纯虚词 → 返 null 落回旧链路。
     */
    private fun referredDelete(content: String, userId: Long, billService: BillService): String? {
        // 先用廉价条件挡掉绝大多数消息，再做正则提取
        if (!content.contains("删") || !(content.contains("刚才") || content.contains("最近"))) return null
        val raw = REFERRED_DELETE.find(content)?.groupValues?.get(1)?.trim() ?: return null
        // 「喝的咖啡」这类句式会把「的」并进关键词（懒惰组 + 可选尾缀的回溯结果），剥掉再匹配
        val keyword = raw.removePrefix("的")
        if (keyword.isEmpty() || keyword in FILLER_WORDS) return null

        val recent = billService.allBills(userId)
            .sortedWith(
                compareByDescending<BillDTO> { it.date }
                    .thenByDescending { it.createdAt }
                    .thenByDescending { it.id }
            )
            .take(10)
        val hits = recent.filter {
            it.categoryName.contains(keyword) || it.remark?.contains(keyword) == true
        }
        if (hits.isEmpty()) {
            return "最近10笔里没找到「$keyword」相关的账单～"
        }
        val target = hits.first() // 倒序后的第一笔即最近那笔
        if (!billService.deleteBill(target.id, userId)) {
            return "删除失败，这笔账可能刚被其他设备删掉，稍后再试～"
        }
        val receipt = MascotVoice.deleteReceipt(target.categoryName, target.amountMinor, formatDate(target.date))
        return if (hits.size == 1) receipt else "$receipt（命中${hits.size}笔，先删了最近这单）"
    }

    /** epoch 毫秒 → 「M/d」（业务时区），与账单回执既有格式一致。 */
    private fun formatDate(epochMs: Long): String {
        val d = java.time.LocalDate.ofInstant(
            java.time.Instant.ofEpochMilli(epochMs), TimeUtil.BOOKKEEPING_ZONE
        )
        return "${d.monthValue}/${d.dayOfMonth}"
    }

    /**
     * 多笔撤回：动词（撤销/撤回/删除/删掉）+ 可选「最近/前面」+ 中文或阿拉伯笔数 + 「笔」。
     * 注意「删除上一笔」的「上」不在数字字符类里，不会误伤 DELETE_LAST 的既有句式。
     */
    private val MULTI_UNDO = Regex("(?:撤销|撤回|删除|删掉)(?:最近|前面)?\\s*([一二三四五两\\d]{1,2})笔")

    /** 属性指代删除：删（除/掉）?（一下）?（刚才|最近）+ 消费动词/「的」 + 关键词 + 可选尾缀。
     *  尾缀要把「的那笔/的这笔」作为整体候选排在「的」前面，否则「吃三餐的那笔」会因
     *  懒惰组回溯把「的」吞进关键词（变成搜「三餐的」而查无此账）。 */
    private val REFERRED_DELETE = Regex("(?:删(?:除|掉)?)(?:一下)?(?:刚才|最近)(?:买|吃|点|喝|叫|的)+(.+?)(?:那笔|这笔|的那笔|的这笔|的)?$")

    /** 提取结果为这些纯虚词时视为无效关键词，返 null 落回旧链路。 */
    private val FILLER_WORDS = setOf("那笔", "这笔", "那", "这", "笔", "了", "的", "一下", "东西")
}
