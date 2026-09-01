package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.NLUService
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure-natural-language intent routing for the QQ bot.
 *
 * The bot speaks plain Chinese ("午餐20元", "今天花了多少") with no command prefix.
 * Routing priority: help → delete → query → bookkeeping → ask-for-amount → LLM fallback.
 * The hard constraint is not to misclassify bookkeeping: a message carrying a concrete
 * amount ("打车花了20") is treated as bookkeeping unless it also contains an explicit
 * ask-word like 多少/几笔. Query replies are computed from the same services the web
 * and Android clients use (BillService/BudgetService/InsightService); anything that is
 * neither a known query nor bookkeeping falls through to [InsightService.naturalQuery].
 *
 * This class is a pure computation unit — it never sends messages. The caller
 * ([QQMessageProcessor]) resolves openid, binding, and delivery (C2C vs group).
 */
class QQIntentRouter(
    private val billService: BillService,
    private val budgetService: BudgetService,
    private val insightService: InsightService,
    private val nluService: NLUService,
    private val ruleBasedParser: RuleBasedParser = RuleBasedParser()
) {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    suspend fun route(content: String, userId: Long): String {
        // A. Help (smallest whitelist, checked first)
        if (HELP.containsMatchIn(content)) return helpText()

        // B. Delete — must be triggered by a delete verb; reference words alone
        // (刚才/那笔) only narrow down the target, never trigger deletion.
        if (DELETE_VERB.containsMatchIn(content)) return deleteIntent(content, userId)

        // C. Query
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
            if (SUMMARY.containsMatchIn(content)) return monthlySummary(userId)
            if (ANOMALY.containsMatchIn(content)) return anomaly(userId)
            if (SUGGEST.containsMatchIn(content)) return suggest(userId)
        }

        // D. Bookkeeping
        val result = nluService.parse(content, userId)
        if (result.amount != null && result.amount > 0) {
            val bill = billService.createBill(userId, result.amount, result.categoryName, result.remark, "QQ")
            return listOf(
                "已记录：${bill.categoryName} ¥${"%.2f".format(bill.amount)}",
                "已记录成功～ ${bill.categoryName} ¥${"%.2f".format(bill.amount)}",
                "好嘞，已记录 ${bill.categoryName} ¥${"%.2f".format(bill.amount)}"
            ).random()
        }

        // E. A category was recognised but no amount — keep the bookkeeping UX alive.
        if (result.categoryName != null) {
            return "请补金额～ 说「${result.categoryName}20元」就帮你记上"
        }

        // F. LLM fallback for anything that is neither a known query nor bookkeeping.
        return insightService.naturalQuery(userId, content).answer
    }

    // ── Query intents ──

    private fun todayExpense(userId: Long): String {
        val todayStart = LocalDate.now(shanghai).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val bills = billService.allBills(userId)
            .filter { it.billType == "EXPENSE" && it.date >= todayStart }
        val total = Money.cents(bills.sumOf { it.amount })
        return "今天已花 ¥${"%.2f".format(total)}（${bills.size}笔），悠着点哦"
    }

    private fun monthExpense(userId: Long): String {
        val (monthStart, nextMonthStart) = currentMonthRange()
        val stats = billService.monthlyStats(userId, monthStart, nextMonthStart)
        return listOf(
            "本月花销：支出 ¥${"%.2f".format(stats.totalExpense)}，收入 ¥${"%.2f".format(stats.totalIncome)}",
            "这个月你花 ¥${"%.2f".format(stats.totalExpense)}，进账 ¥${"%.2f".format(stats.totalIncome)}",
            "本月账单：支出 ¥${"%.2f".format(stats.totalExpense)}，收入 ¥${"%.2f".format(stats.totalIncome)}"
        ).random()
    }

    private fun categoryQuery(category: String, userId: Long): String {
        val (monthStart, nextMonthStart) = currentMonthRange()
        val bills = billService.allBills(userId)
            .filter { it.categoryName == category && it.date >= monthStart && it.date < nextMonthStart }
        val total = Money.cents(bills.sumOf { it.amount })
        return "$category 这个月花了 ¥${"%.2f".format(total)}（${bills.size}笔）"
    }

    private fun budget(userId: Long): String {
        val (monthStart, nextMonthStart) = currentMonthRange()
        val budget = budgetService.list(userId).firstOrNull { it.monthStart == monthStart && !it.deleted }
            ?: return "这个月还没设预算哦，去网页设一个吧～"
        val spent = billService.monthlyStats(userId, monthStart, nextMonthStart).totalExpense
        val remaining = Money.cents(budget.amount - spent)
        val pct = if (budget.amount > 0) ((spent / budget.amount) * 100).toInt() else 0
        return "本月预算 ¥${"%.2f".format(budget.amount)}，已用 ¥${"%.2f".format(spent)}，剩余 ¥${"%.2f".format(remaining)}（已用${pct}%）"
    }

    private fun topCategories(userId: Long): String {
        val (monthStart, nextMonthStart) = currentMonthRange()
        val top = billService.monthlyStats(userId, monthStart, nextMonthStart).topExpenseCategories
        if (top.isEmpty()) return "这个月还没有支出，先记一笔吧～"
        return "这个月花得最多的几类：\n" + top.mapIndexed { i, (name, amt) ->
            "${i + 1}. $name ¥${"%.2f".format(amt)}"
        }.joinToString("\n")
    }

    private fun recentBills(content: String, userId: Long): String {
        val n = Regex("""(\d+)\s*笔""").find(content)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val bills = billService.allBills(userId).sortedByDescending { it.date }.take(n)
        if (bills.isEmpty()) return "还没有任何账单，来记第一笔吧～"
        return bills.mapIndexed { i, b ->
            "${i + 1}. ${formatDate(b.date)} ${b.categoryName} ¥${"%.2f".format(b.amount)} ${b.remark?.take(20) ?: ""}".trimEnd()
        }.joinToString("\n")
    }

    private suspend fun monthlySummary(userId: Long): String {
        val ym = LocalDate.now(shanghai).format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val resp = insightService.monthlySummary(userId, ym)
        val sb = StringBuilder(resp.summary)
        if (resp.highlights.isNotEmpty()) {
            sb.append("\n").append(resp.highlights.joinToString("\n") { "· $it" })
        }
        return sb.toString()
    }

    private suspend fun anomaly(userId: Long): String {
        val alerts = insightService.anomalyCheck(userId).alerts
        if (alerts.isEmpty()) return "今日暂无异常"
        return alerts.joinToString("\n") { it.message }
    }

    private fun suggest(userId: Long): String {
        val pattern = insightService.suggestDailyPattern(userId)
            ?: return "还没摸到你常花的习惯，多记几笔我就懂你啦～"
        val amount = pattern["amount"]?.toDoubleOrNull() ?: 0.0
        return listOf(
            "${pattern["label"]}时段你常点 ${pattern["categoryName"]} ¥${"%.2f".format(amount)}，记一笔正好",
            "${pattern["label"]}到饭点了，你平时那个 ${pattern["categoryName"]} ¥${"%.2f".format(amount)} 别忘了记～"
        ).random()
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
                    amount != null -> candidates.firstOrNull { Money.cents(it.amount) == Money.cents(amount) }
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
            "已删除：${target.categoryName} ¥${"%.2f".format(target.amount)}（${formatDate(target.date)}）",
            "已删除成功～ ${target.categoryName} ¥${"%.2f".format(target.amount)}（${formatDate(target.date)}）"
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
        最近: 最近几笔 / 最近10笔
        删除: 删除午餐 / 删掉刚才那笔
        总结: 分析一下这个月
        异常: 今日异常
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

    private companion object {
        val HELP = Regex("帮助|怎么用|你能做什么|会什么|指令|功能|help", RegexOption.IGNORE_CASE)
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
    }
}
