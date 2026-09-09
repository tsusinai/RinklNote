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

    private fun insertUser(id: Long, openid: String?, aiDisabled: Boolean = false) {
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.phone] = "1380000$id"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
                it[UsersTable.qqNumber] = openid
                it[UsersTable.qqOpenid] = openid
                it[UsersTable.aiDisabled] = aiDisabled
            }
        }
    }

    @Test
    fun `monthly pushes once per month then dedup`() {
        insertUser(1, "openid-1", aiDisabled = false)
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
    fun `non-last-day skips monthly but anomaly pushes when provider non-null`() {
        insertUser(1, "openid-1", aiDisabled = false)
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
}
