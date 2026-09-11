package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.insight.MonthlyAnomalyResponse
import com.example.rinklnote.server.services.nlu.NLUService
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure-natural-language intent routing for the QQ bot and phone AI assistants.
 *
 * The bot speaks plain Chinese ("午餐20元", "今天花了多少") with no command prefix.
 * Routing priority: help → balance → delete → query → bookkeeping → ask-for-amount → LLM fallback.
 * The hard constraint is not to misclassify bookkeeping: a message carrying a concrete
 * amount ("打车花了20") is treated as bookkeeping unless it also contains an explicit
 * ask-word like 多少/几笔. Query replies are computed from the same services the web
 * and Android clients use (BillService/BudgetService/InsightService); anything that is
 * neither a known query nor bookkeeping falls through to [InsightService.naturalQuery].
 *
 * This class is a pure computation unit — it never sends messages. The caller
 * ([QQMessageProcessor]) resolves openid, binding, and delivery (C2C vs group).
 * Phone AI assistants (小爱同学等) pass `source="AI"` so the created bill records
 * the correct source.
 */
class PhoneIntentRouter(
    private val billService: BillService,
    private val budgetService: BudgetService,
    private val insightService: InsightService,
    private val nluService: NLUService,
    private val ruleBasedParser: RuleBasedParser = RuleBasedParser()
) {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    suspend fun route(content: String, userId: Long, source: String = "QQ"): String {
        // Greeting — say hi back; never fall into bookkeeping/「请补金额」.
        if (isGreeting(content)) return greetingText()

        // A. Help (smallest whitelist, checked first)
        if (HELP.containsMatchIn(content)) return helpText()

        // B. Delete — must be triggered by a delete verb; reference words alone
        // (刚才/那笔) only narrow down the target, never trigger deletion.
        if (DELETE_VERB.containsMatchIn(content)) return deleteIntent(content, userId)

        // C. Balance — explicit intent words, checked before the amount gate so
        // "余额" doesn't get misread as a bookkeeping attempt.
        if (BALANCE.containsMatchIn(content)) return balance(userId)

        // D. Summary / Anomaly — explicit intent words, checked before the amount gate so
        // a historical month ("分析一下8月", "8月异常") doesn't get its "8" misread as an
        // amount and pushed into bookkeeping. Supports any month via resolveYearMonth.
        if (SUMMARY.containsMatchIn(content)) return monthlySummary(content, userId)
        if (ANOMALY.containsMatchIn(content)) return anomaly(content, userId)

        // E. Query
        val hasAmount = extractAmount(content) != null
        val askWord = ASK_WORD.containsMatchIn(content)
        if (!(hasAmount && !askWord)) {
            // Setting a budget is not supported in chat — don't silently record it.
            if (BUDGET.containsMatchIn(content) && hasAmount) {
                return "抱歉，机器人还不能设预算，去网页调整一下就行～"
            }
            // Category query ("打车花了多少") before the generic query intents.
            ruleBasedParser.parse(content, emptyList())?.let { cat ->
                if (CATEGORY_QUERY.containsMatchIn(content)) return categoryQuery(cat, userId)
            }
            if (TODAY.containsMatchIn(content) && SPEND.containsMatchIn(content)) return todayExpense(userId)
            if (MONTH.containsMatchIn(content) && SPEND.containsMatchIn(content)) return monthExpense(userId)
            if (BUDGET.containsMatchIn(content)) return budget(userId)
            if (TOP.containsMatchIn(content)) return topCategories(userId)
            if (RECENT.containsMatchIn(content) && RECENT_NOUN.containsMatchIn(content)) return recentBills(content, userId)
            if (SUGGEST.containsMatchIn(content)) return suggest(userId)
        }

        // F. Bookkeeping
        val result = nluService.parse(content, userId)
        if (result.amount != null && result.amount > 0) {
            val bill = billService.createBill(userId, Money.toMinor(result.amount), result.categoryName, result.remark, source)
            return listOf(
                "已记录：${bill.categoryName} ¥${Money.format(bill.amountMinor)}",
                "已记录成功～ ${bill.categoryName} ¥${Money.format(bill.amountMinor)}",
                "好嘞，已记录 ${bill.categoryName} ¥${Money.format(bill.amountMinor)}"
            ).random()
        }

        // G. A category was recognised but no amount — keep the bookkeeping UX alive.
        // Only for a genuine half-finished bookkeeping entry (a real category keyword or
        // bookkeeping verb present), never for random chat/greetings that the LLM happened
        // to label with a catch-all category like 「其他」.
        if (result.categoryName != null && isBookkeepingAttempt(content)) {
            return "请补金额～ 说「${result.categoryName}20元」就帮你记上"
        }

        // H. LLM fallback for anything that is neither a known query nor bookkeeping.
        return insightService.naturalQuery(userId, content).answer
    }

    // ── Query intents ──

    private fun todayExpense(userId: Long): String {
        val todayStart = LocalDate.now(shanghai).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val bills = billService.allBills(userId)
            .filter { it.billType == "EXPENSE" && it.date >= todayStart }
        val total = bills.sumOf { it.amountMinor }
        return "今天已花 ¥${Money.format(total)}（${bills.size}笔），悠着点哦"
    }

    private fun monthExpense(userId: Long): String {
        val (monthStart, nextMonthStart) = currentMonthRange()
        val stats = billService.monthlyStats(userId, monthStart, nextMonthStart)
        return listOf(
            "本月花销：支出 ¥${Money.format(stats.totalExpenseMinor)}，收入 ¥${Money.format(stats.totalIncomeMinor)}",
            "这个月你花 ¥${Money.format(stats.totalExpenseMinor)}，进账 ¥${Money.format(stats.totalIncomeMinor)}",
            "本月账单：支出 ¥${Money.format(stats.totalExpenseMinor)}，收入 ¥${Money.format(stats.totalIncomeMinor)}"
        ).random()
    }

    private fun categoryQuery(category: String, userId: Long): String {
        val (monthStart, nextMonthStart) = currentMonthRange()
        val bills = billService.allBills(userId)
            .filter { it.categoryName == category && it.date >= monthStart && it.date < nextMonthStart }
        val total = bills.sumOf { it.amountMinor }
        return "$category 这个月花了 ¥${Money.format(total)}（${bills.size}笔）"
    }

    private fun budget(userId: Long): String {
        val (monthStart, nextMonthStart) = currentMonthRange()
        val budget = budgetService.list(userId).firstOrNull { it.monthStart == monthStart && !it.deleted }
            ?: return "这个月还没设预算哦，去网页设一个吧～"
        val spent = billService.monthlyStats(userId, monthStart, nextMonthStart).totalExpenseMinor
        val remaining = budget.amountMinor - spent
        val pct = if (budget.amountMinor > 0) ((spent.toDouble() / budget.amountMinor) * 100).toInt() else 0
        return "本月预算 ¥${Money.format(budget.amountMinor)}，已用 ¥${Money.format(spent)}，剩余 ¥${Money.format(remaining)}（已用${pct}%）"
    }

    private fun topCategories(userId: Long): String {
        val (monthStart, nextMonthStart) = currentMonthRange()
        val top = billService.monthlyStats(userId, monthStart, nextMonthStart).topExpenseCategories
        if (top.isEmpty()) return "这个月还没有支出，先记一笔吧～"
        return "这个月花得最多的几类：\n" + top.mapIndexed { i, (name, amt) ->
            "${i + 1}. $name ¥${Money.format(amt)}"
        }.joinToString("\n")
    }

    private fun recentBills(content: String, userId: Long): String {
        val n = Regex("""(\d+)\s*笔""").find(content)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val bills = billService.allBills(userId).sortedByDescending { it.date }.take(n)
        if (bills.isEmpty()) return "还没有任何账单，来记第一笔吧～"
        return bills.mapIndexed { i, b ->
            "${i + 1}. ${formatDate(b.date)} ${b.categoryName} ¥${Money.format(b.amountMinor)} ${b.remark?.take(20) ?: ""}".trimEnd()
        }.joinToString("\n")
    }

    private suspend fun monthlySummary(content: String, userId: Long): String {
        val now = LocalDate.now(shanghai)
        val target = InsightService.resolveYearMonth(content, now) ?: now.withDayOfMonth(1)
        val ym = target.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val resp = insightService.monthlyReview(userId, ym)
        val sb = StringBuilder(resp.summary)
        if (resp.highlights.isNotEmpty()) {
            sb.append("\n").append(resp.highlights.joinToString("\n") { "· $it" })
        }
        return sb.toString()
    }

    private suspend fun anomaly(content: String, userId: Long): String {
        val now = LocalDate.now(shanghai)
        val target = InsightService.resolveYearMonth(content, now)
        // Historical month → monthly anomaly facts; no month → today's alert check.
        if (target == null) {
            val alerts = insightService.anomalyCheck(userId).alerts
            if (alerts.isEmpty()) return "今日暂无异常"
            return alerts.joinToString("\n") { it.message }
        }
        val ym = target.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        return renderMonthlyAnomaly(insightService.monthlyAnomaly(userId, ym))
    }

    private fun renderMonthlyAnomaly(resp: MonthlyAnomalyResponse): String =
        "${resp.month} 异常分析：\n${resp.analysis}"

    private fun suggest(userId: Long): String {
        val pattern = insightService.suggestDailyPattern(userId)
            ?: return "还没摸到你常花的习惯，多记几笔我就懂你啦～"
        val amountStr = pattern["amount"] ?: "0.00"
        return listOf(
            "${pattern["label"]}时段你常点 ${pattern["categoryName"]} ¥$amountStr，记一笔正好",
            "${pattern["label"]}到饭点了，你平时那个 ${pattern["categoryName"]} ¥$amountStr 别忘了记～"
        ).random()
    }

    private fun balance(userId: Long): String {
        val accounts = billService.accountsFor(userId)
        if (accounts.isEmpty()) return "还没有账户，先去 App 加一个吧～"
        val total = accounts.sumOf { it.balanceMinor }
        return "账户余额合计 ¥${Money.format(total)}：\n" +
            accounts.joinToString("\n") { "${it.name} ¥${Money.format(it.balanceMinor)}" }
    }

    // ── Delete intent ──

    private fun deleteIntent(content: String, userId: Long): String {
        // Only look at the most recent 10 bills — never wander into older history.
        val candidates = billService.allBills(userId).sortedByDescending { it.date }.take(10)
        if (candidates.isEmpty()) return "没有可删除的账单"

        val target = when {
            DELETE_REFERENCE.containsMatchIn(content) -> candidates.firstOrNull()
            else -> {
                val cat = ruleBasedParser.parse(content, emptyList())
                val amount = extractAmountWithSuffix(content)
                when {
                    cat != null -> candidates.firstOrNull { it.categoryName == cat }
                    amount != null -> candidates.firstOrNull { it.amountMinor == Money.toMinor(amount) }
                    else -> null
                }
            }
        }

        if (target == null) {
            return if (ruleBasedParser.parse(content, emptyList()) == null &&
                extractAmountWithSuffix(content) == null &&
                !DELETE_REFERENCE.containsMatchIn(content)
            ) {
                "想删哪一笔？说「删除午餐」或「删掉刚才那笔」就行～"
            } else {
                "最近10笔里没找到匹配的账单，再说具体点～"
            }
        }
        billService.deleteBill(target.id, userId)
        return listOf(
            "已删除：${target.categoryName} ¥${Money.format(target.amountMinor)}（${formatDate(target.date)}）",
            "已删除成功～ ${target.categoryName} ¥${Money.format(target.amountMinor)}（${formatDate(target.date)}）"
        ).random()
    }

    // ── Help ──

    private fun helpText(): String = """
        我是你的记账小帮手，直接说就行，比如：
        记账: 午餐20元 / 工资8000
        今日: 今天花了多少
        本月: 这个月花了多少
        分类: 打车花了多少
        预算: 这个月预算
        余额: 看看我的余额
        最近: 最近几笔 / 最近10笔
        删除: 删除午餐 / 删掉刚才那笔
        总结: 分析一下这个月 / 8月
        异常: 今日异常 / 8月异常
        建议: 给点建议
    """.trimIndent()

    // ── Helpers ──

    private fun currentMonthRange(): Pair<Long, Long> {
        val now = LocalDate.now(shanghai)
        val monthStart = now.withDayOfMonth(1).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val nextMonthStart = now.withDayOfMonth(1).plusMonths(1).atStartOfDay(shanghai).toInstant().toEpochMilli()
        return monthStart to nextMonthStart
    }

    private fun formatDate(epochMs: Long): String =
        LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), shanghai)
            .let { "${it.monthValue}/${it.dayOfMonth}" }

    /** Same amount heuristic as DefaultNLUService.extractAmount: 元/块 suffix, else last number. */
    private fun extractAmount(text: String): Double? {
        val withSuffix = Regex("""(\d+\.?\d*)\s*[元块]""").find(text)
        if (withSuffix != null) return withSuffix.groupValues[1].toDoubleOrNull()
        val allNumbers = Regex("""(\d+\.?\d*)""").findAll(text).toList()
        if (allNumbers.isNotEmpty()) return allNumbers.last().groupValues[1].toDoubleOrNull()
        return null
    }

    /** Amount only when it has an explicit 元/块 suffix (used for delete matching). */
    private fun extractAmountWithSuffix(text: String): Double? =
        Regex("""(\d+\.?\d*)\s*[元块]""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Bare greeting (你好/hi/在吗/早上好…) with no bookkeeping intent. */
    private fun isGreeting(content: String): Boolean {
        val t = content.trim()
        return t.length in 1..14 && GREETING.matches(t)
    }

    /** True when the message looks like a real half-finished bookkeeping entry (no amount yet). */
    private fun isBookkeepingAttempt(content: String): Boolean {
        if (extractAmount(content) != null) return true
        if (ruleBasedParser.parse(content, emptyList()) != null) return true
        return BOOKKEEPING_VERB.containsMatchIn(content)
    }

    private fun greetingText(): String = listOf(
        "你好呀，我是你的记账小帮手～ 直接说「午餐20元」就帮你记，问「这个月花了多少」我帮你查",
        "嗨！记账、查账、删账、总结都在行，说「午餐20元」试试～",
        "在呢～ 需要记账就说金额，比如「打车25元」；想知道我能做什么，回复【帮助】"
    ).random()

    private companion object {
        val HELP = Regex("帮助|怎么用|你能做什么|会什么|指令|功能|help|干什么|干嘛|做什么|你是谁|用途|能干嘛", RegexOption.IGNORE_CASE)
        val DELETE_VERB = Regex("删|删除|去掉|撤销|记错了|错了|不要了")
        val DELETE_REFERENCE = Regex("刚才|上一笔|最新|最后|这笔|那笔|刚记")
        val ASK_WORD = Regex("多少|多少钱|几笔|哪几笔|哪些|剩多少")
        val CATEGORY_QUERY = Regex("多少|花了|支出|用了")
        val TODAY = Regex("今天|今日")
        val MONTH = Regex("这个月|本月")
        val SPEND = Regex("花|用|支出|消费|账")
        val BUDGET = Regex("预算|额度|还剩|剩")
        val TOP = Regex("分类|top|排名|哪类|最多|大头", RegexOption.IGNORE_CASE)
        val RECENT = Regex("最近|几笔|记录|账单")
        val RECENT_NOUN = Regex("笔|记录|账单")
        val SUMMARY = Regex("总结|分析|复盘|怎么样")
        val ANOMALY = Regex("异常|预警|超支|超标")
        val SUGGEST = Regex("建议|推荐")
        val BALANCE = Regex("余额|还剩|balance|总资产|账户里有|卡里", RegexOption.IGNORE_CASE)
        val GREETING = Regex("^(你好|您好|哈喽|嗨|hi|hello|在吗|在不在|早上好|上午好|下午好|晚上好|晚安|早|早安)[呀哈哇啦哦]?[\\s!！。~～，,]*$", RegexOption.IGNORE_CASE)
        val BOOKKEEPING_VERB = Regex("记账|记一笔|记一下|记录|帮我记|帮记")
    }
}
