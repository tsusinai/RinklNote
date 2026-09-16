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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

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
    private val clock: () -> LocalDateTime = { LocalDateTime.now(SHANGHAI) },
    private val intervalMs: Long = 30_000L,
    private val log: Logger
) {
    companion object {
        private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 月结最早发送小时（仅月末当天）。 */
        private const val MONTHLY_EARLIEST_HOUR = 20
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) { log.warn("PushScheduler tick failed: ${e.message}") }
                delay(intervalMs)
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
            } catch (e: Exception) {
                log.warn("push user=${u.id} failed: ${e.message}")
            }
        }
    }

    /**
     * 单条推送的完整语义：判重 → 取内容（null 即本次不发）→ 发送 → **成功才**落去重。
     *
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
        if (send(channel, targetId, content, UUID.randomUUID().toString())) {
            markPushed(userId, type, dayKey)
            log.info("$type 已推送 user=$userId channel=$channel")
        } else {
            log.warn("$type 发送失败 user=$userId channel=$channel")
        }
    }

    private fun alreadyPushed(userId: Long, type: String, dayKey: String): Boolean = transaction {
        PushLogTable.selectAll().where {
            (PushLogTable.userId eq userId) and (PushLogTable.type eq type) and (PushLogTable.dayKey eq dayKey)
        }.empty().not()
    }

    private fun markPushed(userId: Long, type: String, dayKey: String) {
        transaction {
            val dup = PushLogTable.selectAll().where {
                (PushLogTable.userId eq userId) and (PushLogTable.type eq type) and (PushLogTable.dayKey eq dayKey)
            }.any()
            if (!dup) PushLogTable.insert {
                it[PushLogTable.userId] = userId
                it[PushLogTable.type] = type
                it[PushLogTable.dayKey] = dayKey
                it[pushedAt] = System.currentTimeMillis()
            }
        }
    }
}
