package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.ChallengeService
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.TimeUtil
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.tables.BotConfigTable
import io.ktor.util.logging.Logger
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 阶段 3 · 小盘主动照料：晨报 / 暮检补记 / 预算亮灯 / 邮件入账通知（全聚合、零备注外泄）。
 *
 * - [morningReport] / [eveningNudge] 由 PushScheduler 按 user+type+day 去重调用；
 *   未到配置时刻 / 开关关闭 / 无内容时返回 null 即不推。开关与时刻读 bot_config KV：
 *   butler_morning_enabled（默认 true）、butler_morning_time（默认 "08:30"）、
 *   butler_evening_enabled（默认 true）、butler_evening_time（默认 "21:30"）。
 *   KV 每次调用现读（量小无所谓）；非法值 log.warn 后回落默认，绝不让配置问题阻断推送。
 * - [budgetLight]：每笔 chat 记账落库后回调（PhoneIntentRouter.onBillBooked /
 *   ButlerIntentRouter.budgetLightHook 接线），分类预算占比跨过 85% / 100% 时返回告警文案
 *   （跨沿触发，同月同档只告一次；无分类预算回退总额预算）。
 * - [mailBookedNotice]：邮件账单入账后向绑定通道推一条确认消息；发送失败只落日志，绝不抛。
 *
 * 隐私红线：本类全部文案只由「分类 + 金额 + 日期」的聚合统计合成，不读单笔备注，不调 LLM。
 * 所有公开方法自身兜底（try/catch → log.warn → null/静默），异常绝不向上冒泡影响主流程。
 * 文案口吻遵循 docs/小盘语气指南.md（MascotVoice 同款锚点纪律，新文案落在本文件内，勿改 MascotVoice）。
 */
class ButlerCareService(
    private val billService: BillService,
    private val budgetService: BudgetService,
    private val challengeService: ChallengeService,
    private val userService: UserService,
    private val log: Logger,
    /** 可注入时钟（测试固定到目标时刻）；业务时区 Asia/Shanghai。 */
    private val clock: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now(TimeUtil.BOOKKEEPING_ZONE) }
) {

    /**
     * 通道发送器（Application.kt 装配后才有值）：按 飞书 > QQ 选用户已绑定通道发送。
     * null 时 [mailBookedNotice] 只记日志不发送。
     */
    var channelSender: (suspend (userId: Long, content: String) -> Boolean)? = null

    // ── bot_config KV 配置（每次现读，解析失败逐项回落默认并告警）──

    private companion object {
        const val KEY_MORNING_ENABLED = "butler_morning_enabled"
        const val KEY_MORNING_TIME = "butler_morning_time"
        const val KEY_EVENING_ENABLED = "butler_evening_enabled"
        const val KEY_EVENING_TIME = "butler_evening_time"

        const val DEFAULT_MORNING_TIME = "08:30"
        const val DEFAULT_EVENING_TIME = "21:30"

        /** 总额预算在去重 key 里使用的占位名（文案展示层用「总预算」，两者分离）。 */
        const val TOTAL_BUDGET_KEY = "__TOTAL__"

        /** 自动记账来源（App 端支付捕获入账，2026-10-03 方案 C），暮检对账段按此过滤。 */
        const val SOURCE_AUTO = "AUTO"

        /** 亮灯档位阈值（百分比整数）：85% 黄灯 / 100% 花完。 */
        const val LEVEL_WARN_PCT = 85L
        const val LEVEL_FULL_PCT = 100L

        /** 时刻配置格式 "HH:mm"（小时允许 1~2 位）。 */
        val TIME_PATTERN = Regex("""^(\d{1,2}):(\d{2})$""")

        /** 去重 key 的月份段。 */
        val MONTH_KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    }

    /** 晨/暮推送日程（时刻统一折算成当日分钟数，便于和 clock() 比较）。 */
    private data class CareSchedule(
        val morningEnabled: Boolean,
        val morningMinutes: Int,
        val eveningEnabled: Boolean,
        val eveningMinutes: Int
    )

    /** 同月同档只告一次的去重表（key = userId|label|yyyy-MM|L档位）。进程内存活，重启清零可接受。 */
    private val budgetAlertKeys = ConcurrentHashMap<String, Boolean>()

    /** 读 bot_config 单键（事务内单查）；表缺失等异常交给各公开方法的兜底。 */
    private fun kvValue(key: String): String? = transaction {
        BotConfigTable.selectAll()
            .where { BotConfigTable.key eq key }
            .singleOrNull()?.get(BotConfigTable.value)
    }

    /** 开关解析：未配置回落默认；非 "true"/"false"（忽略大小写与首尾空白）告警回落默认。 */
    private fun parseEnabled(raw: String?, key: String, default: Boolean): Boolean {
        if (raw.isNullOrBlank()) return default
        return when (raw.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> {
                log.warn("bot_config.$key 值非法（$raw），回落默认 $default")
                default
            }
        }
    }

    /** "HH:mm" → 当日分钟数；未配置回落默认（不告警），非法值告警回落默认。 */
    private fun parseTimeMinutes(raw: String?, key: String, defaultText: String): Int {
        val defaultMinutes = toMinutesOrNull(defaultText)
            ?: error("内置默认时刻非法：$defaultText")
        if (raw.isNullOrBlank()) return defaultMinutes
        val parsed = toMinutesOrNull(raw.trim())
        if (parsed == null) {
            log.warn("bot_config.$key 时刻非法（$raw，应为 HH:mm），回落默认 $defaultText")
            return defaultMinutes
        }
        return parsed
    }

    private fun toMinutesOrNull(text: String): Int? {
        val m = TIME_PATTERN.matchEntire(text) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun loadSchedule(): CareSchedule = CareSchedule(
        morningEnabled = parseEnabled(kvValue(KEY_MORNING_ENABLED), KEY_MORNING_ENABLED, default = true),
        morningMinutes = parseTimeMinutes(kvValue(KEY_MORNING_TIME), KEY_MORNING_TIME, DEFAULT_MORNING_TIME),
        eveningEnabled = parseEnabled(kvValue(KEY_EVENING_ENABLED), KEY_EVENING_ENABLED, default = true),
        eveningMinutes = parseTimeMinutes(kvValue(KEY_EVENING_TIME), KEY_EVENING_TIME, DEFAULT_EVENING_TIME)
    )

    /** 推送 gate：开关关闭或未到当日配置时刻（分钟粒度 >=，与 PushScheduler 日报口径一致）→ false。 */
    private fun scheduleAllows(now: LocalDateTime, enabled: Boolean, dueMinutes: Int): Boolean =
        enabled && (now.hour * 60 + now.minute) >= dueMinutes

    /** 业务时区下某日 0 点的 epoch 毫秒（与 bills.date「当日0点」口径一致）。 */
    private fun dayStartMillis(date: LocalDate): Long =
        date.atStartOfDay(TimeUtil.BOOKKEEPING_ZONE).toInstant().toEpochMilli()

    /** epoch 毫秒 → 业务时区的天序号（零消费日按「天」统计用）。 */
    private fun epochDayOf(dateMillis: Long): Long =
        Instant.ofEpochMilli(dateMillis).atZone(TimeUtil.BOOKKEEPING_ZONE).toLocalDate().toEpochDay()

    // ── 晨报 ──

    /** 晨报（昨日小结 + 本月预算余量 + 零消费日 / 挑战状态）；无内容返回 null 不推。 */
    suspend fun morningReport(userId: Long): String? = try {
        val now = clock()
        val schedule = loadSchedule()
        if (!scheduleAllows(now, schedule.morningEnabled, schedule.morningMinutes)) {
            null
        } else {
            buildMorningReport(userId, now)
        }
    } catch (e: Exception) {
        log.warn("晨报生成失败 user=$userId: ${e.message}")
        null
    }

    private fun buildMorningReport(userId: Long, now: LocalDateTime): String? {
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val todayStart = dayStartMillis(today)
        val yesterdayStart = dayStartMillis(yesterday)
        val monthStartDay = today.withDayOfMonth(1)
        val monthStart = dayStartMillis(monthStartDay)
        val nextMonthStart = dayStartMillis(monthStartDay.plusMonths(1))

        val bills = billService.allBills(userId).filter { !it.deleted }
        val budgets = budgetService.list(userId).filter { !it.deleted }
        // 全空不打扰：从无账单且无预算的用户不发晨报
        if (bills.isEmpty() && budgets.isEmpty()) return null

        // 昨日支出：合计 + 笔数
        val yesterdayExpenses = bills.filter {
            it.billType == "EXPENSE" && it.date >= yesterdayStart && it.date < todayStart
        }
        val yesterdaySpent = yesterdayExpenses.sumOf { it.amountMinor }

        // 本月总额预算（categoryId 与 subCategoryId 均空、monthStart 为本月 1 日 0 点）
        val totalBudget = budgets.firstOrNull {
            it.categoryId == null && it.subCategoryId == null && it.monthStart == monthStart
        }
        val stats = billService.monthlyStats(userId, monthStart, nextMonthStart)

        // 本月零消费天数（截至昨天）= 已过天数 - 有过支出账单的天数
        val spendDays = bills.asSequence()
            .filter { it.billType == "EXPENSE" && it.date >= monthStart && it.date < todayStart }
            .map { epochDayOf(it.date) }
            .toSet()
        // 今天是 1 日时「到昨天为止」为 0 天（昨天属于上月），coerce 兜住
        val daysElapsed = (ChronoUnit.DAYS.between(monthStartDay, yesterday) + 1).coerceAtLeast(0)
        val zeroSpendDays = (daysElapsed - spendDays.size).coerceAtLeast(0)

        // 进行中的挑战数（challengeService.list 含软删行，过滤掉）
        val activeChallenges = challengeService.list(userId).count { !it.deleted && it.status == "ACTIVE" }

        return buildString {
            append("☀️ 早安～ 昨天花了 ¥").append(Money.format(yesterdaySpent))
                .append("（").append(yesterdayExpenses.size).append("笔）。")
            // 预算段缺预算（或预算为 0 无法算占比）时整段省略
            if (totalBudget != null && totalBudget.amountMinor > 0) {
                val remaining = (totalBudget.amountMinor - stats.totalExpenseMinor).coerceAtLeast(0)
                val usedPct = stats.totalExpenseMinor * 100 / totalBudget.amountMinor
                append("本月预算还剩 ¥").append(Money.format(remaining))
                    .append("（已用 ").append(usedPct).append("%）。")
            }
            // 零消费日为 0（月初第 1~2 天）不硬凑句
            if (zeroSpendDays > 0) {
                append("这个月已经攒下 ").append(zeroSpendDays).append(" 个零消费日，继续冲！")
            }
            if (activeChallenges > 0) {
                append("进行中的省钱挑战：").append(activeChallenges).append(" 个，别掉链子哦～")
            }
        }
    }

    // ── 暮检 ──

    /** 暮检（当天有账 → 简短确认；没账 → 补记关怀）；无内容返回 null 不推。 */
    suspend fun eveningNudge(userId: Long): String? = try {
        val now = clock()
        val schedule = loadSchedule()
        if (!scheduleAllows(now, schedule.eveningEnabled, schedule.eveningMinutes)) {
            null
        } else {
            val todayStart = dayStartMillis(now.toLocalDate())
            val todayBills = billService.allBills(userId).filter { !it.deleted && it.date >= todayStart }
            if (todayBills.isEmpty()) {
                "🌙 今天好像还没记过账哦…是忘记啦还是没花钱？没花钱就回「今天没花钱」打卡，花了就补一笔给小盘～"
            } else {
                val expense = todayBills.filter { it.billType == "EXPENSE" }.sumOf { it.amountMinor }
                val income = todayBills.filter { it.billType == "INCOME" }.sumOf { it.amountMinor }
                val incomePart = if (income > 0) "（收入 ¥${Money.format(income)}）" else ""
                val base = "🌙 今天记了 ${todayBills.size} 笔，支出 ¥${Money.format(expense)}$incomePart，都记全了嘛～"
                // 自动记账对账段（2026-10-03 方案 C）：App 端捕获静默入账（source=AUTO）的账单
                // 同步上来后在此列清单供事后审计；商户取备注（自动记账落商户），空则回分类名。
                val autoBills = todayBills.filter { it.source == SOURCE_AUTO }
                val autoPart = if (autoBills.isEmpty()) "" else {
                    val items = autoBills.take(3).joinToString("、") {
                        "${it.remark?.takeIf { r -> r.isNotBlank() } ?: it.categoryName} ¥${Money.format(it.amountMinor)}"
                    }
                    val suffix = if (autoBills.size > 3) " 等${autoBills.size}笔" else ""
                    "\n其中 ${autoBills.size} 笔是小盘自动记的：$items$suffix。有不对的回「撤销」，或来 App 改～"
                }
                "$base$autoPart 晚安啦"
            }
        }
    } catch (e: Exception) {
        log.warn("暮检生成失败 user=$userId: ${e.message}")
        null
    }

    // ── 预算亮灯 ──

    /** 预算亮灯：返回追加到记账回执的告警文案；未跨沿 / 无预算返回 null。 */
    suspend fun budgetLight(categoryName: String, amountMinor: Long, userId: Long): String? = try {
        buildBudgetLight(categoryName, amountMinor, userId)
    } catch (e: Exception) {
        log.warn("预算亮灯计算失败 user=$userId category=$categoryName: ${e.message}")
        null
    }

    private fun buildBudgetLight(categoryName: String, amountMinor: Long, userId: Long): String? {
        val now = clock()
        val monthStartDay = now.toLocalDate().withDayOfMonth(1)
        val monthStart = dayStartMillis(monthStartDay)
        val nextMonthStart = dayStartMillis(monthStartDay.plusMonths(1))
        val monthKey = monthStartDay.format(MONTH_KEY_FORMAT)

        // 收入分类不参与亮灯：预算只盯支出，且收入金额不在支出聚合里，
        // 若回退总额预算会把 before 算小造成假跨沿
        val category = billService.getCategories().firstOrNull { it.name == categoryName }
        if (category != null && category.billType != "EXPENSE") return null

        val budgets = budgetService.list(userId).filter { !it.deleted && it.monthStart == monthStart }
        val categoryBudget = category?.let { c ->
            budgets.firstOrNull { it.categoryId == c.id && it.subCategoryId == null }
        }
        val isTotal = categoryBudget == null
        // 分类预算优先，没有则回退总额预算；都没有不告警
        val budget = categoryBudget
            ?: budgets.firstOrNull { it.categoryId == null && it.subCategoryId == null }
            ?: return null
        // 预算为 0/负无法算占比，不告警（防除零）
        if (budget.amountMinor <= 0) return null

        // 已用（本笔刚落库，spent 已包含它）：分类预算按该分类聚合，总额预算走月度聚合
        val spent = if (category != null && categoryBudget != null) {
            billService.allBills(userId)
                .filter {
                    !it.deleted && it.billType == "EXPENSE" &&
                        it.categoryId == category.id &&
                        it.date >= monthStart && it.date < nextMonthStart
                }
                .sumOf { it.amountMinor }
        } else {
            billService.monthlyStats(userId, monthStart, nextMonthStart).totalExpenseMinor
        }

        // 跨沿判定（全整数运算，不引入 Double）；L2（花完）优先于 L1（黄灯）
        val before = (spent - amountMinor).coerceAtLeast(0)
        val after = spent
        val budgetMinor = budget.amountMinor
        val level = when {
            before * 100 < LEVEL_FULL_PCT * budgetMinor &&
                after * 100 >= LEVEL_FULL_PCT * budgetMinor -> 2
            before * 100 < LEVEL_WARN_PCT * budgetMinor &&
                after * 100 >= LEVEL_WARN_PCT * budgetMinor -> 1
            else -> return null
        }

        // 同月同档只告一次；总额预算的去重名固定为 __TOTAL__（与展示层「总预算」分离）
        val keyLabel = if (isTotal) TOTAL_BUDGET_KEY else categoryName
        val key = "$userId|$keyLabel|$monthKey|L$level"
        if (budgetAlertKeys.putIfAbsent(key, true) != null) return null

        val displayLabel = if (isTotal) "总预算" else categoryName
        return if (level == 2) {
            "🚨 本月「$displayLabel」预算已花完（¥${Money.format(after)}/¥${Money.format(budgetMinor)}）！" +
                "接下来小盘陪你一起零消费冲关～"
        } else {
            val pct = after * 100 / budgetMinor
            val remaining = (budgetMinor - after).coerceAtLeast(0)
            "⚠️ 小盘黄灯预警：本月「$displayLabel」额度已用 $pct%（还剩 ¥${Money.format(remaining)}），悠着点哦～"
        }
    }

    // ── 邮件入账通知 ──

    /** 邮件账单入账通知（Application.kt 已把 MailIngestService.onBooked 接到这里）。 */
    suspend fun mailBookedNotice(userId: Long, merchant: String, amountMinor: Long, categoryName: String) {
        // 文案只含商户/金额/分类三类入账元数据（商户本就随邮件账单落库，无备注外泄）
        val catPart = if (categoryName.isBlank()) "" else "（$categoryName）"
        val content = "📬 邮件账单已入账：$merchant ¥${Money.format(amountMinor)}$catPart\n" +
            "分类不对？去 App 改一下就好～"
        val sender = channelSender
        if (sender == null) {
            log.info("邮件入账通知未发送（通道发送器未装配）user=$userId merchant=$merchant")
            return
        }
        try {
            if (sender(userId, content)) {
                log.info("邮件入账通知已发送 user=$userId merchant=$merchant")
            } else {
                log.warn("邮件入账通知发送失败 user=$userId merchant=$merchant")
            }
        } catch (e: Exception) {
            log.warn("邮件入账通知发送异常 user=$userId: ${e.message}")
        }
    }
}
