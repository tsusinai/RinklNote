package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.PushLogTable
import com.example.rinklnote.server.tables.UsersTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

class PushSchedulerTest {

    private val userService = UserService(jwtSecret = "test-secret", jwtIssuer = "test", jwtAudience = "rinklnote")
    private val log = LoggerFactory.getLogger("PushSchedulerTest")

    @Before
    fun setup() {
        TestDatabase.connect("pushschedtest")
        transaction {
            SchemaUtils.create(UsersTable, PushLogTable)
            PushLogTable.deleteAll()
            UsersTable.deleteAll()
        }
    }

    private fun insertUser(
        id: Long,
        openid: String?,
        aiDisabled: Boolean = false,
        dailyReportEnabled: Boolean = false,
        dailyReportHour: Int = 9,
        dailyReportMinute: Int = 0
    ) {
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.phone] = "1380000$id"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
                it[UsersTable.qqNumber] = openid
                it[UsersTable.qqOpenid] = openid
                it[UsersTable.aiDisabled] = aiDisabled
                it[UsersTable.dailyReportEnabled] = dailyReportEnabled
                it[UsersTable.dailyReportHour] = dailyReportHour
                it[UsersTable.dailyReportMinute] = dailyReportMinute
            }
        }
    }

    @Test
    fun `monthly pushes once per month then dedup`() {
        insertUser(1, "openid-1", aiDisabled = false, dailyReportEnabled = true)
        val sent = mutableListOf<String>()
        val sched = PushScheduler(
            userService,
            send = { _, content, _ -> sent.add(content); true },
            monthlyProvider = { _, _ -> "📊 本月总结\n..." },
            anomalyProvider = { _ -> null },
            habitProvider = { _ -> null },
            clock = { LocalDateTime.of(2026, 8, 31, 20, 0) }, // 月末 ≥20:00
            dailyReportProvider = { null },
            intervalMs = 30_000, log = log
        )
        runBlocking { sched.tick() }
        runBlocking { sched.tick() } // 同一 tick 内再次执行 → push_log 去重
        assertEquals(1, sent.size)
    }

    @Test
    fun `ai_disabled user receives nothing`() {
        insertUser(1, "openid-1", aiDisabled = true)
        val sent = mutableListOf<String>()
        val sched = PushScheduler(
            userService,
            send = { _, content, _ -> sent.add(content); true },
            monthlyProvider = { _, _ -> "📊 本月总结" },
            anomalyProvider = { _ -> "⚠️ 今天超支" },
            habitProvider = { _ -> "该记午餐了" },
            clock = { LocalDateTime.of(2026, 8, 31, 20, 0) },
            dailyReportProvider = { null },
            intervalMs = 30_000, log = log
        )
        runBlocking { sched.tick() }
        assertEquals(0, sent.size)
    }

    @Test
    fun `daily report is pushed even when habit provider returns null`() {
        insertUser(1, "openid-1", aiDisabled = false, dailyReportEnabled = true)
        val sent = mutableListOf<String>()
        val sched = PushScheduler(
            userService,
            send = { _, content, _ -> sent.add(content); true },
            monthlyProvider = { _, _ -> null },
            anomalyProvider = { _ -> null },
            habitProvider = { _ -> null },   // 常态：今天没有习惯提醒
            clock = { LocalDateTime.of(2026, 8, 15, 10, 0) },
            dailyReportProvider = { _ -> "✅ 今日账单总结" },
            intervalMs = 30_000, log = log
        )
        runBlocking { sched.tick() }
        assertEquals(1, sent.size)
        assertEquals("✅ 今日账单总结", sent[0])
    }

    @Test
    fun `non-last-day skips monthly but anomaly pushes when provider non-null`() {
        insertUser(1, "openid-1", aiDisabled = false, dailyReportEnabled = true)
        val sent = mutableListOf<String>()
        val sched = PushScheduler(
            userService,
            send = { _, content, _ -> sent.add(content); true },
            monthlyProvider = { _, _ -> "monthly" },   // 月中不应推
            anomalyProvider = { _ -> "⚠️ 今天超支" },
            habitProvider = { _ -> null },
            clock = { LocalDateTime.of(2026, 8, 15, 10, 0) }, // 非月末
            dailyReportProvider = { null },
            intervalMs = 30_000, log = log
        )
        runBlocking { sched.tick() }
        assertEquals(1, sent.size)
        assertEquals("⚠️ 今天超支", sent[0])
    }

    @Test
    fun `daily report respects user time window`() {
        // 用户设 21:30：21:29 不到点不推；21:31 到点推（>= 语义，当天首推）
        insertUser(1, "openid-1", aiDisabled = false, dailyReportEnabled = true, dailyReportHour = 21, dailyReportMinute = 30)
        val sent = mutableListOf<String>()
        fun schedAt(hour: Int, minute: Int) = PushScheduler(
            userService,
            send = { _, content, _ -> sent.add(content); true },
            monthlyProvider = { _, _ -> null },
            anomalyProvider = { _ -> null },
            habitProvider = { _ -> null },
            clock = { LocalDateTime.of(2026, 8, 15, hour, minute) },
            dailyReportProvider = { _ -> "✅ 今日账单总结" },
            intervalMs = 30_000, log = log
        )
        runBlocking { schedAt(21, 29).tick() }
        assertEquals("21:29 尚未到点，不应推送", 0, sent.size)
        runBlocking { schedAt(21, 31).tick() }
        assertEquals("21:31 到点后应推送", 1, sent.size)
    }

    @Test
    fun `daily report disabled user never pushed even past default due time`() {
        // 默认 dailyReportEnabled=false：即便过了默认 9:00 也绝不推（开关在用户手里）
        insertUser(1, "openid-1", aiDisabled = false)
        val sent = mutableListOf<String>()
        val sched = PushScheduler(
            userService,
            send = { _, content, _ -> sent.add(content); true },
            monthlyProvider = { _, _ -> null },
            anomalyProvider = { _ -> null },
            habitProvider = { _ -> null },
            clock = { LocalDateTime.of(2026, 8, 15, 10, 0) },
            dailyReportProvider = { _ -> "✅ 今日账单总结" },
            intervalMs = 30_000, log = log
        )
        runBlocking { sched.tick() }
        assertEquals(0, sent.size)
    }
}
