package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.PushLogTable
import com.example.rinklnote.server.tables.UsersTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Task 0.7：推送失败重试队列 + push_log 状态标记的单测（假通道 send lambda，零网络）。
 * 退避基数注入 60_000ms，时钟用 nowMillis 注入手动推进，确定性验证退避节奏。
 */
class PushSchedulerRetryTest {

    private val userService = UserService(jwtSecret = "test-secret", jwtIssuer = "test", jwtAudience = "rinklnote")
    private val log = LoggerFactory.getLogger("PushSchedulerRetryTest")

    /** 手动时钟：测试里直接推进。 */
    private var now = 1_000_000L

    @Before
    fun setup() {
        TestDatabase.connect("pushretry")
        transaction {
            SchemaUtils.create(UsersTable, PushLogTable)
            PushLogTable.deleteAll()
            UsersTable.deleteAll()
        }
        now = 1_000_000L
    }

    private fun insertUser(id: Long, openid: String?) {
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.phone] = "1380000$id"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
                it[UsersTable.qqOpenid] = openid
                it[UsersTable.qqNumber] = openid
                it[dailyReportEnabled] = true
            }
        }
    }

    private fun statusOf(userId: Long, type: String, dayKey: String): String? = transaction {
        PushLogTable.selectAll()
            .where {
                (PushLogTable.userId eq userId) and
                    (PushLogTable.type eq type) and
                    (PushLogTable.dayKey eq dayKey)
            }
            .singleOrNull()?.get(PushLogTable.status)
    }

    private fun sched(
        dailyReportProvider: suspend (Long) -> String? = { "✅ 今日账单总结" },
        // 注意 send 放最后：测试用尾随 lambda 传假通道
        send: suspend (String, String, String, String) -> Boolean
    ) = PushScheduler(
        userService,
        send = send,
        monthlyProvider = { _, _ -> null },
        anomalyProvider = { null },
        habitProvider = { null },
        clock = { LocalDateTime.of(2026, 8, 15, 10, 0) },
        dailyReportProvider = dailyReportProvider,
        retryBaseDelayMs = 60_000L,
        nowMillis = { now },
        intervalMs = 30_000, log = log
    )

    @Test
    fun `首次失败标记RETRYING，到期重试成功转OK`() = runBlocking {
        insertUser(1, "openid-1")
        var fail = true
        val attempts = mutableListOf<Long>()
        val s = sched { _, _, _, _ -> attempts.add(now); if (fail) false else true }
        s.tick()
        assertEquals("首次发送失败", 1, attempts.size)
        assertEquals("失败后 push_log 应标记 RETRYING", PushScheduler.STATUS_RETRYING, statusOf(1, "DAILY_REPORT", "2026-08-15"))
        assertEquals(1, s.pendingRetryCount())

        // 退避未到期（30s < 60s）：drain 不发送
        now += 30_000
        s.drainRetries()
        assertEquals("退避未到期不应重试", 1, attempts.size)

        // 第 1 次重试（60s）到期且成功 → 状态转 OK
        fail = false
        now += 30_000
        s.drainRetries()
        assertEquals(2, attempts.size)
        assertEquals("重试成功后状态应为 OK", PushScheduler.STATUS_OK, statusOf(1, "DAILY_REPORT", "2026-08-15"))
        assertEquals("成功后队列清空", 0, s.pendingRetryCount())

        // 已 OK：再 tick 也不会重复推送（去重仍然生效）
        s.tick()
        assertEquals(2, attempts.size)
    }

    @Test
    fun `重试耗尽三次后标记FAILED不再尝试`() = runBlocking {
        insertUser(1, "openid-1")
        val attempts = mutableListOf<Long>()
        val s = sched { _, _, _, _ -> attempts.add(now); false }
        s.tick()
        assertEquals(1, attempts.size)

        // 第 1 次重试：+60s 失败；第 2 次：再 +120s 失败；第 3 次：再 +240s 失败 → FAILED
        now += 60_000; s.drainRetries()
        now += 120_000; s.drainRetries()
        now += 240_000; s.drainRetries()
        assertEquals("1 次首发 + 3 次重试 = 4 次尝试", 4, attempts.size)
        assertEquals("重试耗尽应标记 FAILED", PushScheduler.STATUS_FAILED, statusOf(1, "DAILY_REPORT", "2026-08-15"))
        assertEquals(0, s.pendingRetryCount())

        // FAILED 后再 drain / tick 都不再发送
        now += 1_000_000
        s.drainRetries()
        s.tick()
        assertEquals("耗尽后不应再有尝试", 4, attempts.size)
    }

    @Test
    fun `指数退避节奏为60s_120s_240s`() = runBlocking {
        insertUser(1, "openid-1")
        val attempts = mutableListOf<Long>()
        val s = sched { _, _, _, _ -> attempts.add(now); false }
        s.tick()
        // 第 1 次重试最早在 +60s
        now += 59_999; s.drainRetries(); assertEquals(1, attempts.size)
        now += 1; s.drainRetries(); assertEquals(2, attempts.size)
        // 第 2 次重试最早在 +120s
        now += 119_999; s.drainRetries(); assertEquals(2, attempts.size)
        now += 1; s.drainRetries(); assertEquals(3, attempts.size)
        // 第 3 次重试最早在 +240s
        now += 239_999; s.drainRetries(); assertEquals(3, attempts.size)
        now += 1; s.drainRetries(); assertEquals(4, attempts.size)
    }

    @Test
    fun `RETRYING期间tick不会重复入队或重复发送`() = runBlocking {
        insertUser(1, "openid-1")
        val attempts = mutableListOf<Long>()
        val s = sched { _, _, _, _ -> attempts.add(now); false }
        s.tick()
        s.tick() // RETRYING 行挡住重复推送
        s.tick()
        assertEquals("RETRYING 期间 tick 不重复发送", 1, attempts.size)
        assertEquals("队列里只有一条任务", 1, s.pendingRetryCount())
    }

    @Test
    fun `send抛异常同样进入重试`() = runBlocking {
        insertUser(1, "openid-1")
        val attempts = mutableListOf<Long>()
        val s = sched { _, _, _, _ -> attempts.add(now); error("通道炸了") }
        s.tick() // send 的异常在 pushIfNeeded 内被转为「未送达」，不冒泡
        assertEquals(PushScheduler.STATUS_RETRYING, statusOf(1, "DAILY_REPORT", "2026-08-15"))
        assertEquals(1, attempts.size)
    }

    // ── AdminService 通道健康度 ──

    @Test
    fun `通道健康度按通道聚合计数`() {
        transaction {
            // QQ：1 成功 1 失败；FEISHU：2 成功；WECOM：1 重试中；无通道旧数据：1 成功
            PushLogTable.insert {
                it[userId] = 1; it[type] = "DAILY_REPORT"; it[dayKey] = "2026-08-14"
                it[pushedAt] = now; it[channel] = "QQ"; it[status] = "OK"
            }
            PushLogTable.insert {
                it[userId] = 2; it[type] = "DAILY_REPORT"; it[dayKey] = "2026-08-15"
                it[pushedAt] = now + 1; it[channel] = "QQ"; it[status] = "FAILED"
            }
            PushLogTable.insert {
                it[userId] = 3; it[type] = "DAILY_REPORT"; it[dayKey] = "2026-08-15"
                it[pushedAt] = now + 2; it[channel] = "FEISHU"; it[status] = "OK"
            }
            PushLogTable.insert {
                it[userId] = 4; it[type] = "DAILY_REPORT"; it[dayKey] = "2026-08-15"
                it[pushedAt] = now + 3; it[channel] = "FEISHU"; it[status] = "OK"
            }
            PushLogTable.insert {
                it[userId] = 5; it[type] = "DAILY_REPORT"; it[dayKey] = "2026-08-15"
                it[pushedAt] = now + 4; it[channel] = "WECOM"; it[status] = "RETRYING"
            }
            PushLogTable.insert {
                it[userId] = 6; it[type] = "DAILY_REPORT"; it[dayKey] = "2026-08-01"
                it[pushedAt] = now; it[channel] = null; it[status] = "OK"
            }
            // 窗口外的旧行不计入
            PushLogTable.insert {
                it[userId] = 7; it[type] = "DAILY_REPORT"; it[dayKey] = "2026-07-01"
                it[pushedAt] = now - AdminService.HEALTH_WINDOW_MS - 1; it[channel] = "QQ"; it[status] = "OK"
            }
        }
        val admin = AdminService(nowMillis = { now })
        val health = admin.channelHealth()
        val byCh = health.associateBy { it.channel }
        assertEquals(4, health.size)
        // 展示顺序：QQ 在 FEISHU 前，FEISHU 在 WECOM 前，UNKNOWN 最后
        assertEquals(listOf("QQ", "FEISHU", "WECOM", "UNKNOWN"), health.map { it.channel })
        assertEquals(1L, byCh.getValue("QQ").okCount)
        assertEquals(1L, byCh.getValue("QQ").failedCount)
        assertEquals(2L, byCh.getValue("FEISHU").okCount)
        assertEquals(1L, byCh.getValue("WECOM").retryingCount)
        assertEquals(1L, byCh.getValue("UNKNOWN").okCount)
        assertEquals(now + 1, byCh.getValue("QQ").lastFailureAt)
        assertEquals(now, byCh.getValue("QQ").lastSuccessAt)
        assertNull(byCh.getValue("WECOM").lastSuccessAt)
    }
}
