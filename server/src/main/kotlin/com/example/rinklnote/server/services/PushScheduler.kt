package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.PushLogTable
import io.ktor.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Bot 主动推送调度（多通道）：进程内协程 + delay 循环（不做 Quartz/外部 cron）。
 *
 * 每 tick 对每个已绑定任一「可推送」通道（飞书/企业微信/QQ，见 [UserService.findAllPushUsers]）
 * 的用户按「日历窗口 + push_log 去重」判定四类推送：
 *  - MONTHLY_SUMMARY：本月最后一天 ≥20:00 且本月有 ≥1 笔且本月未推（day_key="yyyy-MM"）
 *  - ANOMALY：anomalyCheck 有 alert 且当天未推（day_key="yyyy-MM-dd"）
 *  - HABIT：habitReminder 命中且当天未推（day_key="yyyy-MM-dd"）
 *  - DAILY_REPORT：用户开启 daily_report_enabled 且 ≥ 其设定时刻，当天未推（day_key="yyyy-MM-dd"）
 *
 * 目标通道按 飞书 > 企业微信 > QQ 取第一个已绑定通道（一个用户一次推送只落一个通道）；
 * 企微受限：仅全库企微绑定用户数 == 1 时可用（webhook 推送是群维度，多绑定会跨用户泄露），
 * 否则跳过落下一优先级。订阅号只收不推，不参与。user+type+day 单次推送语义不变。
 *
 * ai_disabled=true 的用户所有主动推送跳过（Q10=A：主动问账/AI 页注入仍走 LLM）。
 *
 * 可测：各类内容通过 suspend lambda 注入（provider 返回 null 即不推），send 为注入的 send lambda
 * （带通道维度 `(channel, targetId, content, msgId)`，通道分发在 Application.kt 组装），
 * clock 可固定到目标时刻。LLM 调用藏在 provider 里，调度去重逻辑本身零网络依赖。
 *
 * **失败重试（2026-09-18 Task 0.7）**：send 失败（返回 false 或抛异常）时任务进入进程内
 * 重试队列，指数退避（base、2×base、4×base）最多重试 [MAX_RETRIES] 次；push_log.status 同步标记：
 *  - RETRYING：失败已入队，重试期间管理面可见；
 *  - OK：某次尝试送达（含重试成功）；
 *  - FAILED：重试耗尽彻底失败。
 * 队列纯内存，重启丢弃可接受（push_log 里 RETRYING/FAILED 行可查）；RETRYING/FAILED 行同样
 * 参与 user+type+day 去重，避免 tick 循环与重试队列双头重复发送。push_log 只存元数据不存文案，
 * 状态标记不触碰隐私红线。
 *
 * 注意：四类推送一律走 [pushIfNeeded] 的同一模板，**不要**把新分支写进旧分支的 if 体内 ——
 * 那正是本类此前 DAILY_REPORT 静默失效的原因（日报曾被嵌在 HABIT 的 `if (content != null)` 里）。
 */
class PushScheduler(
    private val userService: UserService,
    private val send: suspend (channel: String, targetId: String, content: String, msgId: String) -> Boolean,
    private val monthlyProvider: suspend (userId: Long, month: String) -> String?,
    private val anomalyProvider: suspend (userId: Long) -> String?,
    private val habitProvider: suspend (userId: Long) -> String?,
    private val dailyReportProvider: suspend (userId: Long) -> String?,
    /** 周报（账单教练，2026-09-18 Task 1.4）：每周一 ≥9:00 推一次；默认 null（未接线不推，行为零变化）。 */
    private val weeklyProvider: suspend (userId: Long) -> String? = { null },
    private val clock: () -> LocalDateTime = { LocalDateTime.now(SHANGHAI) },
    private val intervalMs: Long = 30_000L,
    /** 重试指数退避基数：第 n 次重试延迟 = base × 2^(n-1)。测试注入小值。 */
    private val retryBaseDelayMs: Long = 60_000L,
    /** 重试队列扫描间隔（生产 1s；测试直接手动调 [drainRetries]，本值不影响确定性）。 */
    private val retryCheckIntervalMs: Long = 1_000L,
    /** epoch 毫秒时钟（重试计时 / pushedAt 用），测试注入固定值。 */
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val log: Logger
) {
    companion object {
        private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 月结最早发送小时（仅月末当天）。 */
        private const val MONTHLY_EARLIEST_HOUR = 20

        /** 周报（账单教练）最早发送小时（仅周一）。 */
        private const val WEEKLY_EARLIEST_HOUR = 9

        /** 首次发送失败后最多重试次数（总尝试 ≤ 1 + 3 次）。 */
        const val MAX_RETRIES = 3

        /** push_log.status 词表。 */
        const val STATUS_OK = "OK"
        const val STATUS_RETRYING = "RETRYING"
        const val STATUS_FAILED = "FAILED"

        /** 第 n 次（1 起）重试的退避延迟：base × 2^(n-1)。 */
        fun backoffDelayMs(retryNo: Int, baseMs: Long): Long = baseMs shl (retryNo - 1).coerceAtLeast(0)
    }

    /** 内存重试队列的一条任务（attempt 已含首次失败；nextAttemptAt 为下次重试的 epoch 毫秒）。 */
    private data class RetryTask(
        val userId: Long,
        val channel: String,
        val targetId: String,
        val type: String,
        val dayKey: String,
        val content: String,
        val msgId: String,
        val retryCount: Int,
        val nextAttemptAt: Long
    )

    private val retryQueue = ConcurrentLinkedQueue<RetryTask>()

    /** 当前挂起重试任务数（管理面/测试观测用）。 */
    fun pendingRetryCount(): Int = retryQueue.size

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) { log.warn("PushScheduler tick failed: ${e.message}") }
                delay(intervalMs)
            }
        }
        scope.launch {
            while (isActive) {
                try { drainRetries() } catch (e: Exception) { log.warn("PushScheduler retry drain failed: ${e.message}") }
                delay(retryCheckIntervalMs)
            }
        }
    }

    suspend fun tick() {
        val now = clock()
        val today = now.toLocalDate()
        val dayKey = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val month = today.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val isLastDayOfMonth = today.dayOfMonth == today.lengthOfMonth()
        val hour = now.hour

        // 全库企微绑定数每个 tick 查一次（不进用户循环）：企微通道可用性的判定依据，见下方注释
        val wecomBoundCount = userService.countWecomBoundUsers()
        for (u in userService.findAllPushUsers()) {
            if (u.aiDisabled) continue        // Q10=A：关所有主动推送（问账不受影响）
            // 目标通道按 飞书 > 企业微信(受限) > QQ 取第一个可用通道（一用户一次推送只落一个通道；
            // 订阅号只收不推，findAllPushUsers 已排除）。QQ 单通道用户行为与改造前完全一致。
            // 企微受限（安全评审修复）：webhook 推送是「群机器人维度」而非按人单聊，多用户绑企微时
            // 私有日报会发进同一个群造成跨用户泄露 —— 仅当全库企微绑定用户数 == 1（即目标本人是
            // 唯一绑定者）才允许走企微，否则跳过落下一优先级（飞书 > QQ；QQ 也没绑则整体跳过）。
            val (channel, targetId) = when {
                u.feishuOpenId != null -> BotCommands.SOURCE_FEISHU to u.feishuOpenId
                u.wecomUserid != null && wecomBoundCount == 1 -> BotCommands.SOURCE_WECOM to u.wecomUserid
                u.qqOpenid != null -> BotCommands.SOURCE_QQ to u.qqOpenid
                else -> continue
            }
            try {
                if (isLastDayOfMonth && hour >= MONTHLY_EARLIEST_HOUR) {
                    pushIfNeeded(u.id, channel, targetId, "MONTHLY_SUMMARY", month) { monthlyProvider(it, month) }
                }
                pushIfNeeded(u.id, channel, targetId, "ANOMALY", dayKey, anomalyProvider)
                pushIfNeeded(u.id, channel, targetId, "HABIT", dayKey, habitProvider)
                // 日报：用户子开关 + 到点后当天首推（>= 而非 ==，30s tick 不可能精确命中某分钟）。
                val nowMinutes = now.hour * 60 + now.minute
                val dueMinutes = u.dailyReportHour * 60 + u.dailyReportMinute
                if (u.dailyReportEnabled && nowMinutes >= dueMinutes) {
                    pushIfNeeded(u.id, channel, targetId, "DAILY_REPORT", dayKey, dailyReportProvider)
                }
                // 周报（账单教练）：每周一 ≥9:00 一条，dayKey 锚定本周一日期（一周一条，错峰重推安全）。
                if (now.dayOfWeek == java.time.DayOfWeek.MONDAY && hour >= WEEKLY_EARLIEST_HOUR) {
                    val weekKey = "weekly-" + today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                        .format(DateTimeFormatter.ISO_LOCAL_DATE)
                    pushIfNeeded(u.id, channel, targetId, "WEEKLY_REPORT", weekKey, weeklyProvider)
                }
            } catch (e: Exception) {
                log.warn("push user=${u.id} failed: ${e.message}")
            }
        }
    }

    /**
     * 扫描重试队列，把到期任务交回 [send]。与 tick 循环独立（间隔 [retryCheckIntervalMs]）；
     * 测试可直接调用以确定性地推进重试。
     */
    suspend fun drainRetries() {
        val nowMs = nowMillis()
        val deferred = ArrayList<RetryTask>()
        while (true) {
            val task = retryQueue.poll() ?: break
            if (task.nextAttemptAt > nowMs) {
                deferred.add(task)
                continue
            }
            val ok = try {
                send(task.channel, task.targetId, task.content, task.msgId)
            } catch (e: Exception) {
                log.warn("重试发送异常 user=${task.userId} type=${task.type}: ${e.message}")
                false
            }
            when {
                ok -> {
                    markPushStatus(task.userId, task.type, task.dayKey, STATUS_OK, task.channel)
                    log.info("推送重试成功 user=${task.userId} type=${task.type} 第${task.retryCount + 1}次重试")
                }
                task.retryCount + 1 >= MAX_RETRIES -> {
                    markPushStatus(task.userId, task.type, task.dayKey, STATUS_FAILED, task.channel)
                    log.warn("推送重试耗尽 user=${task.userId} type=${task.type} dayKey=${task.dayKey} channel=${task.channel}")
                }
                else -> {
                    val nextRetryNo = task.retryCount + 2
                    deferred.add(task.copy(retryCount = task.retryCount + 1, nextAttemptAt = nowMillis() + backoffDelayMs(nextRetryNo, retryBaseDelayMs)))
                }
            }
        }
        deferred.forEach { retryQueue.add(it) }
    }

    /**
     * 单条推送的完整语义：判重 → 取内容（null 即本次不发）→ 发送 → **成功才**落去重；
     * 失败则标记 RETRYING 并入内存重试队列（指数退避 ≤ [MAX_RETRIES] 次）。
     * RETRYING 行同样挡住后续 tick 的重复推送，重试所有权唯一在重试队列。
     * 四类推平共用这一个模板，从结构上杜绝「新分支被嵌进旧分支」的错位再次发生。
     */
    private suspend fun pushIfNeeded(
        userId: Long,
        channel: String,
        targetId: String,
        type: String,
        dayKey: String,
        provider: suspend (Long) -> String?
    ) {
        if (alreadyPushed(userId, type, dayKey)) return
        val content = provider(userId) ?: return
        // send 返回 false 或抛异常都视为「本次未送达」，统一走重试队列（异常不向上冒泡，
        // 避免 tick 的 per-user catch 抢先把失败吞成「无事发生」）。
        val delivered = try {
            send(channel, targetId, content, UUID.randomUUID().toString())
        } catch (e: Exception) {
            log.warn("$type 发送异常 user=$userId channel=$channel: ${e.message}")
            false
        }
        if (delivered) {
            markPushStatus(userId, type, dayKey, STATUS_OK, channel)
            log.info("$type 已推送 user=$userId channel=$channel")
        } else {
            log.warn("$type 发送失败 user=$userId channel=$channel，进入重试队列")
            markPushStatus(userId, type, dayKey, STATUS_RETRYING, channel)
            retryQueue.add(
                RetryTask(
                    userId = userId, channel = channel, targetId = targetId, type = type, dayKey = dayKey,
                    content = content, msgId = UUID.randomUUID().toString(),
                    retryCount = 0, nextAttemptAt = nowMillis() + backoffDelayMs(1, retryBaseDelayMs)
                )
            )
        }
    }

    private fun alreadyPushed(userId: Long, type: String, dayKey: String): Boolean = transaction {
        PushLogTable.selectAll().where {
            (PushLogTable.userId eq userId) and (PushLogTable.type eq type) and (PushLogTable.dayKey eq dayKey)
        }.empty().not()
    }

    /**
     * push_log 状态落库（upsert）：行不存在按给定状态插入（记首推时刻与通道）；
     * 已存在（RETRYING → OK / FAILED）只更新状态与通道，首推时刻保留原值。
     */
    private fun markPushStatus(userId: Long, type: String, dayKey: String, status: String, channel: String?) {
        transaction {
            val existing = PushLogTable.selectAll().where {
                (PushLogTable.userId eq userId) and (PushLogTable.type eq type) and (PushLogTable.dayKey eq dayKey)
            }.singleOrNull()
            if (existing == null) {
                PushLogTable.insert {
                    it[PushLogTable.userId] = userId
                    it[PushLogTable.type] = type
                    it[PushLogTable.dayKey] = dayKey
                    it[pushedAt] = nowMillis()
                    it[PushLogTable.channel] = channel
                    it[PushLogTable.status] = status
                }
            } else {
                PushLogTable.update({ PushLogTable.id eq existing[PushLogTable.id] }) {
                    it[PushLogTable.status] = status
                    if (channel != null) it[PushLogTable.channel] = channel
                }
            }
        }
    }
}
