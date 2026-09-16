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
        dailyReportMinute: Int = 0,
        feishuOpenId: String? = null,
        wechatOpenid: String? = null,
        wecomUserid: String? = null
    ) {
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.phone] = "1380000$id"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
                it[UsersTable.qqNumber] = openid
                it[UsersTable.qqOpenid] = openid
                it[UsersTable.feishuOpenId] = feishuOpenId
                it[UsersTable.wechatOpenid] = wechatOpenid
                it[UsersTable.wecomUserid] = wecomUserid
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
            send = { _, _, content, _ -> sent.add(content); true },
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
            send = { _, _, content, _ -> sent.add(content); true },
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
            send = { _, _, content, _ -> sent.add(content); true },
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
            send = { _, _, content, _ -> sent.add(content); true },
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
            send = { _, _, content, _ -> sent.add(content); true },
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
            send = { _, _, content, _ -> sent.add(content); true },
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

    // ── B1 通道底座：分通道推送目标选择 ──

    @Test
    fun `qq-only user pushes over QQ channel unchanged`() {
        // QQ 单通道用户：行为与改造前一致，channel=QQ、target=qqOpenid
        insertUser(1, "openid-1", dailyReportEnabled = true)
        val pushed = mutableListOf<Pair<String, String>>() // channel to targetId
        val sched = PushScheduler(
            userService,
            send = { channel, targetId, _, _ -> pushed.add(channel to targetId); true },
            monthlyProvider = { _, _ -> null },
            anomalyProvider = { _ -> null },
            habitProvider = { _ -> null },
            clock = { LocalDateTime.of(2026, 8, 15, 10, 0) },
            dailyReportProvider = { _ -> "✅ 今日账单总结" },
            intervalMs = 30_000, log = log
        )
        runBlocking { sched.tick() }
        assertEquals(1, pushed.size)
        assertEquals(BotCommands.SOURCE_QQ to "openid-1", pushed[0])
    }

    @Test
    fun `push channel priority is feishu over wecom over qq`() {
        // 同时绑定飞书+企微 → 只落飞书（优先级最高，企微受限与否不影响）
        insertUser(1, "openid-1", dailyReportEnabled = true, feishuOpenId = "ou-feishu", wecomUserid = "wecom-1")
        // 只绑企微+QQ → 全库已有 2 个企微绑定用户，企微通道受限（群 webhook 维度会跨用户泄露），
        // 跳过落下一优先级 QQ（安全评审修复后的新语义；旧实现此处会落企微）
        insertUser(2, "openid-2", dailyReportEnabled = true, wecomUserid = "wecom-2")
        val pushed = mutableListOf<Pair<String, String>>()
        val sched = PushScheduler(
            userService,
            send = { channel, targetId, _, _ -> pushed.add(channel to targetId); true },
            monthlyProvider = { _, _ -> null },
            anomalyProvider = { _ -> null },
            habitProvider = { _ -> null },
            clock = { LocalDateTime.of(2026, 8, 15, 10, 0) },
            dailyReportProvider = { _ -> "✅ 今日账单总结" },
            intervalMs = 30_000, log = log
        )
        runBlocking { sched.tick() }
        assertEquals(2, pushed.size)
        assertEquals(BotCommands.SOURCE_FEISHU to "ou-feishu", pushed[0])
        assertEquals("双企微绑定时企微受限，应落 QQ", BotCommands.SOURCE_QQ to "openid-2", pushed[1])
    }

    @Test
    fun `sole wecom-bound user still pushes over wecom channel`() {
        // 全库唯一企微绑定用户：企微通道可用（安全评审修复保留的合法场景）
        insertUser(1, openid = null, dailyReportEnabled = true, wecomUserid = "wecom-solo")
        val pushed = mutableListOf<Pair<String, String>>()
        val sched = PushScheduler(
            userService,
            send = { channel, targetId, _, _ -> pushed.add(channel to targetId); true },
            monthlyProvider = { _, _ -> null },
            anomalyProvider = { _ -> null },
            habitProvider = { _ -> null },
            clock = { LocalDateTime.of(2026, 8, 15, 10, 0) },
            dailyReportProvider = { _ -> "✅ 今日账单总结" },
            intervalMs = 30_000, log = log
        )
        runBlocking { sched.tick() }
        assertEquals(1, pushed.size)
        assertEquals(BotCommands.SOURCE_WECOM to "wecom-solo", pushed[0])
    }

    @Test
    fun `双用户绑企微且无其他通道时整体跳过推送`() {
        // 两个用户都只绑企微：企微推送是群 webhook 维度，私有日报发同一群会跨用户泄露 ——
        // 企微通道对两者都禁用，又没有 QQ 可落，整体跳过（宁可不推也不泄露，安全评审修复）
        insertUser(1, openid = null, dailyReportEnabled = true, wecomUserid = "wecom-a")
        insertUser(2, openid = null, dailyReportEnabled = true, wecomUserid = "wecom-b")
        val pushed = mutableListOf<Pair<String, String>>()
        val sched = PushScheduler(
            userService,
            send = { channel, targetId, _, _ -> pushed.add(channel to targetId); true },
            monthlyProvider = { _, _ -> null },
            anomalyProvider = { _ -> null },
            habitProvider = { _ -> null },
            clock = { LocalDateTime.of(2026, 8, 15, 10, 0) },
            dailyReportProvider = { _ -> "✅ 今日账单总结" },
            intervalMs = 30_000, log = log
        )
        runBlocking { sched.tick() }
        assertEquals("双企微绑定时不得经企微推送任何人", 0, pushed.size)
    }

    @Test
    fun `wechat-mp-only user is never pushed`() {
        // 只绑订阅号（只收不推）的用户不参与任何主动推送
        insertUser(1, openid = null, wechatOpenid = "mp-openid-1", dailyReportEnabled = true)
        val sent = mutableListOf<String>()
        val sched = PushScheduler(
            userService,
            send = { _, _, content, _ -> sent.add(content); true },
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
