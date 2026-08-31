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
 * QQ 主动推送调度：进程内协程 + delay 循环（不做 Quartz/外部 cron）。
 *
 * 每 tick 对每个已绑定QQ的用户按「日历窗口 + push_log 去重」判定三类推送：
 *  - MONTHLY_SUMMARY：本月最后一天 ≥20:00 且本月有 ≥1 笔且本月未推（day_key="yyyy-MM"）
 *  - ANOMALY：anomalyCheck 有 alert 且当天未推（day_key="yyyy-MM-dd"）
 *  - HABIT：habitReminder 命中且当天未推（day_key="yyyy-MM-dd"）
 *
 * ai_disabled=true 的用户所有主动推送跳过（Q10=A：主动问账/AI 页注入仍走 LLM）。
 *
 * 可测：三类内容通过 suspend lambda 注入（provider 返回 null 即不推），send 为注入的 send lambda，
 * clock 可固定到目标时刻。LLM 调用藏在 provider 里，调度去重逻辑本身零网络依赖。
 */
class PushScheduler(
    private val userService: UserService,
    private val send: suspend (openid: String, content: String, msgId: String) -> Boolean,
    private val monthlyProvider: suspend (userId: Long, month: String) -> String?,
    private val anomalyProvider: suspend (userId: Long) -> String?,
    private val habitProvider: suspend (userId: Long) -> String?,
    private val clock: () -> LocalDateTime = { LocalDateTime.now(SHANGHAI) },
    private val intervalMs: Long = 30_000L,
    private val log: Logger
) {
    companion object {
        private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
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

        for (u in userService.findAllBoundQq()) {
            if (u.aiDisabled) continue        // Q10=A：关所有主动推送（问账不受影响）
            val openid = u.qqOpenid ?: continue
            try {
                if (isLastDayOfMonth && hour >= 20 && !alreadyPushed(u.id, "MONTHLY_SUMMARY", month)) {
                    val content = monthlyProvider(u.id, month)
                    if (content != null) {
                        if (send(openid, content, UUID.randomUUID().toString())) {
                            markPushed(u.id, "MONTHLY_SUMMARY", month)
                            log.info("月结已推送 user=${u.id}")
                        } else log.warn("月结发送失败 user=${u.id}")
                    }
                }
                if (!alreadyPushed(u.id, "ANOMALY", dayKey)) {
                    val content = anomalyProvider(u.id)
                    if (content != null) {
                        if (send(openid, content, UUID.randomUUID().toString())) {
                            markPushed(u.id, "ANOMALY", dayKey)
                            log.info("异常已推送 user=${u.id}")
                        } else log.warn("异常发送失败 user=${u.id}")
                    }
                }
                if (!alreadyPushed(u.id, "HABIT", dayKey)) {
                    val content = habitProvider(u.id)
                    if (content != null) {
                        if (send(openid, content, UUID.randomUUID().toString())) {
                            markPushed(u.id, "HABIT", dayKey)
                            log.info("习惯已推送 user=${u.id}")
                        } else log.warn("习惯发送失败 user=${u.id}")
                    }
                }
            } catch (e: Exception) {
                log.warn("push user=${u.id} failed: ${e.message}")
            }
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
