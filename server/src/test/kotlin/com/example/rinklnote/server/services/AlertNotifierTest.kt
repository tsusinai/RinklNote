package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Task 0.8：全局异常告警单测 —— 文案纯函数（无敏感 body）、主账号通道解析、限流。
 */
class AlertNotifierTest {

    private val log = LoggerFactory.getLogger("AlertNotifierTest")
    private var now = 1_000_000L
    private var adminEnv: String? = null

    @Before
    fun setup() {
        TestDatabase.connect("alertnotifier")
        transaction {
            SchemaUtils.create(UsersTable)
            UsersTable.deleteAll()
        }
        now = 1_000_000L
    }

    private fun insertPushUser(id: Long, phone: String?, qqOpenid: String? = null, feishuOpenId: String? = null) {
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
                it[UsersTable.phone] = phone
                it[UsersTable.qqOpenid] = qqOpenid
                it[UsersTable.qqNumber] = qqOpenid
                it[UsersTable.feishuOpenId] = feishuOpenId
            }
        }
    }

    private fun notifier(
        adminIdentityRaw: () -> String?,
        sent: MutableList<String>
    ) = AlertNotifier(
        userService = UserService(jwtSecret = "test-secret", jwtIssuer = "test", jwtAudience = "rinklnote"),
        send = { _, _, content -> sent.add(content); true },
        adminIdentityRaw = adminIdentityRaw,
        nowMillis = { now },
        log = log
    )

    // ── 文案纯函数 ──

    @Test
    fun `告警文案只含路径与摘要不含请求体`() {
        val cause = RuntimeException("SQLException: SELECT * FROM users WHERE phone='13800000000'")
        val text = AlertNotifier.buildAlertText("POST", "/api/bills", cause, LocalDateTime.of(2026, 9, 18, 3, 0, 0))
        assertTrue(text.contains("POST /api/bills"))
        assertTrue(text.contains("RuntimeException"))
        assertTrue(text.contains("SQLException"))
        // 隐私红线：文案是异常摘要，不含请求体字段；这里断言不含「body=」类标记（请求体从不进文案）
        assertFalse(text.contains("body"))
    }

    @Test
    fun `超长异常消息截断到200字`() {
        val cause = RuntimeException("x".repeat(500))
        val text = AlertNotifier.buildAlertText("GET", "/x", cause, LocalDateTime.now())
        val summaryLine = text.lineSequence().first { it.startsWith("异常:") }
        // 摘要本体 ≤200 字；整行 = 前缀 + 异常类名 + 摘要，上限断言防整段 500 字进文案
        assertTrue("摘要行应被截断（实际 ${summaryLine.length}）", summaryLine.length <= 230)
    }

    // ── 主账号通道解析 ──

    @Test
    fun `主账号按手机号解析并优先飞书通道`() {
        insertPushUser(1, phone = "13800000001", qqOpenid = "qq-1", feishuOpenId = "ou-1")
        val sent = mutableListOf<String>()
        val n = notifier({ "13800000001" }, sent)
        val target = n.resolveAdminTarget()
        assertEquals(BotCommands.SOURCE_FEISHU to "ou-1", target)
    }

    @Test
    fun `主账号无飞书时回落QQ`() {
        insertPushUser(2, phone = "13800000002", qqOpenid = "qq-2")
        val n = notifier({ "13800000002" }, mutableListOf())
        assertEquals(BotCommands.SOURCE_QQ to "qq-2", n.resolveAdminTarget())
    }

    @Test
    fun `名单支持userId且查不到通道用户时返回null`() {
        insertPushUser(3, phone = null, qqOpenid = null) // 无任何可推送通道
        val n = notifier({ "13800000009, 3 " }, mutableListOf())
        // 第一个手机号不存在，第二个 userId=3 存在但无通道 → 整体 null
        assertNull(n.resolveAdminTarget())
    }

    @Test
    fun `名单为空时不告警`() {
        insertPushUser(4, phone = "13800000004", qqOpenid = "qq-4")
        val sent = mutableListOf<String>()
        val n = notifier({ null }, sent)
        runBlockingAlert(n)
        assertEquals(0, sent.size)
    }

    // ── 限流与送达 ──

    @Test
    fun `同路径同异常一分钟内只告警一次`() = kotlinx.coroutines.runBlocking {
        insertPushUser(5, phone = "13800000005", qqOpenid = "qq-5")
        val sent = mutableListOf<String>()
        val n = notifier({ "13800000005" }, sent)
        assertTrue(n.alert("GET", "/api/boom", RuntimeException("a")))
        assertFalse(n.alert("GET", "/api/boom", RuntimeException("b"))) // 同 key 限流
        now += 61_000
        assertTrue(n.alert("GET", "/api/boom", RuntimeException("c"))) // 过了窗口 → 放行
        assertEquals(2, sent.size)
    }

    @Test
    fun `不同异常类型各自限流`() = kotlinx.coroutines.runBlocking {
        insertPushUser(6, phone = "13800000006", qqOpenid = "qq-6")
        val sent = mutableListOf<String>()
        val n = notifier({ "13800000006" }, sent)
        assertTrue(n.alert("GET", "/api/boom", RuntimeException("a")))
        assertTrue(n.alert("GET", "/api/boom", IllegalStateException("b"))) // 不同异常类 → 各自一条
        assertEquals(2, sent.size)
    }

    /** runBlocking 的轻量替身（避免整文件引协程依赖别名混乱）。 */
    private fun runBlockingAlert(n: AlertNotifier) {
        kotlinx.coroutines.runBlocking { n.deliver("测试告警") }
    }
}
