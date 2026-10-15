package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.TimeUtil
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 阶段 1 · 富查账：补齐 PhoneIntentRouter 覆盖不了的时间范围/口径（全聚合、零 LLM、零备注外泄）：
 *   - 「上周买衣服花了多少」「上周吃饭花了多少」（上周 × 分类）
 *   - 「最近哪天花钱最多」「哪天开销最大」（近 30 天最大单日支出）
 *   - 「每天还能花多少」「日均还能花多少」（本月剩余预算 ÷ 剩余天数）
 *
 * 实现：三个意图按序匹配，命中即回文案，全都不命中返回 null（路由层落回 PhoneIntentRouter）。
 * 聚合基于 BillService.allBills（已过滤软删）在内存完成，只使用 分类/金额/日期 元数据，
 * 不读备注、不送 LLM（隐私红线）。日期边界一律用业务时区 [TimeUtil.BOOKKEEPING_ZONE]。
 */
class RichQueryService(
    private val billService: BillService,
    private val budgetService: BudgetService
) {

    private val ruleParser = RuleBasedParser()

    /** 命中富查账句式返回回复文案；未命中返回 null（路由层落回 PhoneIntentRouter）。 */
    suspend fun tryAnswer(text: String, userId: Long): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return answerWeeklyCategory(trimmed, userId)
            ?: answerBusiestDay(trimmed, userId)
            ?: answerDailyBudget(trimmed, userId)
    }

    // ── 意图 1：上周/本周 × 分类查账 ───────────────────────────

    /** 「上周「餐饮」花了 ¥xx（n笔）」；上周 = 上个 ISO 周一 0 点 → 本周一 0 点。 */
    private fun answerWeeklyCategory(text: String, userId: Long): String? {
        val period = WEEK_RE.find(text) ?: return null
        if (!WEEK_VERB_RE.containsMatchIn(text)) return null
        val category = resolveCategory(text) ?: return null

        val today = LocalDate.now(TimeUtil.BOOKKEEPING_ZONE)
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val label: String
        val start: LocalDate
        val end: LocalDate?
        when (period.value) {
            "上周" -> {
                label = "上周"
                start = thisMonday.minusWeeks(1)
                end = thisMonday
            }
            else -> {
                label = if (period.value == "这周") "这周" else "本周"
                start = thisMonday
                end = null // 本周到当前（下面用 now 兜上限，未来日期的账单不计入）
            }
        }
        val startMillis = start.atStartOfDay(TimeUtil.BOOKKEEPING_ZONE).toInstant().toEpochMilli()
        // 上周终点 = 本周一 0 点（左闭右开：本周一的账单归本周）；本周终点 = 当前时刻
        val endMillis = end?.atStartOfDay(TimeUtil.BOOKKEEPING_ZONE)?.toInstant()?.toEpochMilli()
            ?: System.currentTimeMillis()

        val bills = billService.allBills(userId).filter {
            it.billType == "EXPENSE" && it.categoryName == category &&
                it.date >= startMillis && it.date < endMillis
        }
        if (bills.isEmpty()) {
            return "🧾 ${label}还没记过「$category」相关的账哦"
        }
        val totalMinor = bills.sumOf { it.amountMinor }
        return "📊 ${label}「$category」花了 ¥${Money.format(totalMinor)}（${bills.size}笔）"
    }

    /**
     * 富查账的分类解析：先走规则词表（含品牌词），词表未命中再用
     * 「分类名出现在文本里」的唯一包含命中；多义 / 无命中返回 null（该意图不接管）。
     */
    private fun resolveCategory(text: String): String? {
        ruleParser.parse(text, emptyList())?.let { return it }
        val hits = billService.getCategories().filter { text.contains(it.name) }
        return hits.singleOrNull()?.name
    }

    // ── 意图 2：近 30 天最花钱的一天 ───────────────────────────

    /** 「近30天花得最多的一天是 {M月d日}，花了 ¥xx（n笔）」；并列取最近的一天。 */
    private fun answerBusiestDay(text: String, userId: Long): String? {
        if (!BUSIEST_DAY_RE.containsMatchIn(text)) return null
        val today = LocalDate.now(TimeUtil.BOOKKEEPING_ZONE)
        val startMillis = today.minusDays(29).atStartOfDay(TimeUtil.BOOKKEEPING_ZONE)
            .toInstant().toEpochMilli()
        val nowMillis = System.currentTimeMillis()
        val expenses = billService.allBills(userId).filter {
            it.billType == "EXPENSE" && it.date >= startMillis && it.date <= nowMillis
        }
        if (expenses.isEmpty()) {
            return "🧾 近30天还没有支出记录，先记一笔吧～"
        }
        val byDay = expenses.groupBy { it.date }
        // 按天聚合金额，金额并列时取最近的一天（比较键：先总金额、再日期）
        val best = byDay.entries
            .map { entry -> Triple(entry.key, entry.value.sumOf { it.amountMinor }, entry.value.size) }
            .maxWithOrNull(compareBy({ it.second }, { it.first }))
            ?: return null
        val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(best.first), TimeUtil.BOOKKEEPING_ZONE)
        return "🔍 近30天花得最多的一天是 ${date.monthValue}月${date.dayOfMonth}日，" +
            "花了 ¥${Money.format(best.second)}（${best.third}笔）"
    }

    // ── 意图 3：日均可用（本月剩余预算 ÷ 剩余天数） ─────────────

    private fun answerDailyBudget(text: String, userId: Long): String? {
        if (!DAILY_BUDGET_RE.containsMatchIn(text)) return null
        val today = LocalDate.now(TimeUtil.BOOKKEEPING_ZONE)
        val monthStart = today.withDayOfMonth(1).atStartOfDay(TimeUtil.BOOKKEEPING_ZONE)
            .toInstant().toEpochMilli()

        val totalBudget = budgetService.list(userId).firstOrNull {
            !it.deleted && it.categoryId == null && it.subCategoryId == null && it.monthStart == monthStart
        } ?: return "📊 这个月还没设预算哦，去 App 或网页设一个吧～"

        val nextMonthStart = today.with(TemporalAdjusters.firstDayOfNextMonth())
            .atStartOfDay(TimeUtil.BOOKKEEPING_ZONE).toInstant().toEpochMilli()
        val spentMinor = billService.monthlyStats(userId, monthStart, nextMonthStart).totalExpenseMinor
        val remaining = totalBudget.amountMinor - spentMinor
        // 当月剩余天数（含今天）
        val daysLeft = today.lengthOfMonth() - today.dayOfMonth + 1
        if (remaining < 0) {
            return "⚠️ 本月预算已超 ¥${Money.format(-remaining)}，接下来几天留意支出节奏吧～"
        }
        // 长除取整数分；remaining >= 0 且 daysLeft >= 1，无负数取整歧义
        val dailyMinor = remaining / daysLeft
        return "📊 本月预算还剩 ¥${Money.format(remaining)}，这个月还剩 $daysLeft 天，" +
            "平均每天还能花 ¥${Money.format(dailyMinor)} 哦"
    }

    private companion object {
        /** 上周 / 本周 / 这周（「这周」在回复里原样回给用户）。 */
        val WEEK_RE = Regex("(上周|本周|这周)")

        /** 查账动词集合（与富查账语义匹配，避免把纯记账句误当查账）。 */
        val WEEK_VERB_RE = Regex("花|支出|用了|多少")

        /** 最花钱的一天句式：哪天…最多/最大、花钱最多、开销最大。 */
        val BUSIEST_DAY_RE = Regex("哪天.*(最多|最大)|花钱最多|开销最大")

        /** 日均可用句式。 */
        val DAILY_BUDGET_RE = Regex("每天还能花|日均还能花|一天还能花|还能花多少")
    }
}
