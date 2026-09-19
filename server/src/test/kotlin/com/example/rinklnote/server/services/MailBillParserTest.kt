package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId

/**
 * Task 4.4：邮件账单解析与入账单测（样例为脱敏文件，位于 test/resources/mail/）。
 * IMAP 真连接不进单测：轮询循环用假 MailInbox 注入，零网络。
 * 隐私红线自查：正文只在内存解析；日志/告警内容只含发件人与解析结果摘要。
 */
class MailBillParserTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/mail/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun dayStart(date: String): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    // ── 脱敏样例解析 ──

    @Test
    fun `支付宝表格版式样例解析`() {
        val parsed = MailBillParser.parse(sample("alipay-bill-sample.html"))!!
        assertEquals(3550L, parsed.amountMinor)
        assertEquals("瑞幸咖啡（文一路店）", parsed.merchant)
        assertEquals(dayStart("2026-09-17"), parsed.paidAtMillis)
    }

    @Test
    fun `微信行内版式样例解析`() {
        val parsed = MailBillParser.parse(sample("wechat-bill-sample.html"))!!
        assertEquals(12800L, parsed.amountMinor)
        assertEquals("山姆会员商店", parsed.merchant)
        assertEquals(dayStart("2026-09-17"), parsed.paidAtMillis)
    }

    // ── 纯文本与边界 ──

    @Test
    fun `千分位金额按完整数值入账`() {
        // 「1,234.56」不能在逗号处截断成 1.00 元（错账）
        assertEquals(123456L, MailBillParser.parse("商户：老王包子铺\n金额：￥1,234.56")!!.amountMinor)
        assertEquals(
            1234567890L,
            MailBillParser.parse("商户：老王包子铺\n金额：￥12,345,678.90")!!.amountMinor
        )
        // 无千分位的普通金额不受影响
        assertEquals(3550L, MailBillParser.parse("商户：老王包子铺\n金额：￥35.50")!!.amountMinor)
    }

    @Test
    fun `纯文本邮件解析`() {
        val parsed = MailBillParser.parse("商户：老王包子铺\n支付时间：2026-09-18 08:00\n金额：￥8.00")!!
        assertEquals(800L, parsed.amountMinor)
        assertEquals("老王包子铺", parsed.merchant)
        assertEquals(dayStart("2026-09-18"), parsed.paidAtMillis)
    }

    @Test
    fun `HTML实体与标签剥离`() {
        assertEquals("¥9.90 优惠", MailBillParser.stripHtml("<p>&yen;9.90&nbsp;优惠</p>"))
        // style 块与注释不进正文
        val text = MailBillParser.stripHtml("<style>.a{}</style><!-- 注释 -->￥1.00")
        assertTrue(text.contains("￥1.00"))
        assertFalse(text.contains(".a{}"))
        assertFalse(text.contains("注释"))
    }

    @Test
    fun `缺金额或缺商家返回null`() {
        assertNull(MailBillParser.parse("没有金额只有商家：瑞幸咖啡"))
        assertNull(MailBillParser.parse("只有金额￥9.90，没有商家字段"))
        assertNull(MailBillParser.parse(""))
    }

    @Test
    fun `非法日期回退null`() {
        assertNull(MailBillParser.extractDate("支付时间：13月88日"))
        assertNull(MailBillParser.extractDate("没有日期"))
    }

    // ── 白名单匹配 ──

    @Test
    fun `发件人白名单匹配与拒绝`() {
        TestDatabase.connect("mailwhitelist")
        val svc = MailIngestService(
            config = MailIngestConfig(1L, "imap.example.com", 993, "u", "p", listOf("@mail.alipay.com", "wxpaynotice@tencent.com")),
            billService = BillService(), log = LoggerFactory.getLogger("t")
        )
        assertTrue(svc.isWhitelisted("bill@mail.alipay.com", svc.config.senders))
        assertTrue("大小写不敏感", svc.isWhitelisted("Bill@Mail.Alipay.Com", svc.config.senders))
        assertTrue(svc.isWhitelisted("wxpaynotice@tencent.com", svc.config.senders))
        assertFalse(svc.isWhitelisted("evil@alipay.com.evil.example", svc.config.senders))
        assertFalse(svc.isWhitelisted("someone@example.com", svc.config.senders))
        // RFC 5322 显示名 From：真实账单邮件普遍是「支付宝 <bill@mail.alipay.com>」形态，
        // Jakarta 的 InternetAddress.toString() 原样带出 —— 白名单必须按 addr-spec 匹配
        assertTrue("带引号显示名也命中", svc.isWhitelisted("\"支付宝\" <bill@mail.alipay.com>", svc.config.senders))
        assertTrue("无引号显示名也命中", svc.isWhitelisted("支付宝 <bill@mail.alipay.com>", svc.config.senders))
        // 显示名伪造：按 addr-spec 判定，不能被「显示名长得像白名单地址」骗过
        assertFalse(
            "显示名伪装不算命中",
            svc.isWhitelisted("\"bill@mail.alipay.com\" <evil@evil.example>", svc.config.senders)
        )
    }
}

/** 轮询入账全链路（假收件箱）：入账、来源/日期标记、白名单外不触碰、失败告警限流。 */
class MailIngestServicePollTest {

    private val billService = BillService()
    private val log = LoggerFactory.getLogger("MailIngestPollTest")
    private var now = 1_000_000L
    private val alerts = mutableListOf<String>()

    private class FakeInbox(private val msgs: MutableList<MailMessage>) : MailInbox {
        val markedRead = mutableListOf<MailMessage>()
        override fun unread(): List<MailMessage> = msgs.filter { it !in markedRead }
        override fun markRead(msg: MailMessage) { markedRead.add(msg) }
        override fun close() {}
    }

    @Before
    fun setup() {
        TestDatabase.connect("mailpoll")
        transaction {
            SchemaUtils.create(UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable)
            BillsTable.deleteAll()
            SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll()
            CategoriesTable.deleteAll()
            UsersTable.deleteAll()
        }
        billService.seedIfNeeded()
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 1L
                it[UsersTable.phone] = "13800000071"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
        alerts.clear()
        now = 1_000_000L
    }

    private fun service(inbox: FakeInbox): MailIngestService = MailIngestService(
        config = MailIngestConfig(
            userId = 1L, host = "imap.example.com", port = 993, user = "u", password = "p",
            senders = listOf("@mail.alipay.com"), intervalMs = MailIngestConfig.DEFAULT_INTERVAL_MS
        ),
        billService = billService,
        alert = { text -> alerts.add(text) },
        nowMillis = { now },
        log = log,
        openInbox = { inbox }
    )

    private val alipayMail = MailMessage(
        from = "bill@mail.alipay.com", subject = "交易成功通知",
        body = "商户：瑞幸咖啡（文一路店）\n支付时间：2026-09-17 12:30\n金额：￥35.50"
    )

    @Test
    fun `白名单邮件入账且来源与日期正确`() {
        val inbox = FakeInbox(mutableListOf(alipayMail))
        val svc = service(inbox)
        val booked = kotlinx.coroutines.runBlocking { svc.pollOnce() }
        assertEquals(1, booked)
        val bill = transaction {
            BillsTable.selectAll().where { BillsTable.userId eq 1L }.single()
        }
        assertEquals(3550L, bill[BillsTable.amountMinor])
        assertEquals(BotCommands.SOURCE_MAIL, bill[BillsTable.billSource])
        assertEquals("瑞幸咖啡（文一路店）", bill[BillsTable.remark])
        // 品牌映射归三餐（本地规则，不送 LLM）
        assertEquals("三餐", bill[BillsTable.categoryName])
        // 支付日期来自邮件（2026-09-17），不是处理当天
        assertEquals(
            LocalDate.of(2026, 9, 17).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
            bill[BillsTable.date]
        )
        // 已读标记：同一收件箱再轮询一遍不再入账
        assertEquals(1, inbox.markedRead.size)
        assertEquals(0, kotlinx.coroutines.runBlocking { svc.pollOnce() })
    }

    @Test
    fun `带显示名的白名单邮件入账`() {
        // 真实邮件 From 携带显示名（"支付宝" <bill@mail.alipay.com>）：入账 + 标已读，不静默跳过
        val mail = alipayMail.copy(from = "\"支付宝\" <bill@mail.alipay.com>")
        val inbox = FakeInbox(mutableListOf(mail))
        val svc = service(inbox)
        val booked = kotlinx.coroutines.runBlocking { svc.pollOnce() }
        assertEquals(1, booked)
        assertEquals(1, inbox.markedRead.size)
    }

    @Test
    fun `白名单外邮件不触碰不入账`() {
        val inbox = FakeInbox(mutableListOf(MailMessage("evil@evil.example", "广告", "￥999.00 商户：假店")))
        val booked = kotlinx.coroutines.runBlocking { service(inbox).pollOnce() }
        assertEquals(0, booked)
        assertEquals(0, inbox.markedRead.size)
        assertEquals(0L, transaction { BillsTable.selectAll().count() })
    }

    @Test
    fun `解析失败标已读并限流告警`() {
        val bad = MailMessage("bill@mail.alipay.com", "看不懂", "正文里没有任何金额和商家字段")
        val inbox = FakeInbox(mutableListOf(bad))
        val svc = service(inbox)
        val booked = kotlinx.coroutines.runBlocking { svc.pollOnce() }
        assertEquals(0, booked)
        assertEquals("解析失败也标已读防重扫", 1, inbox.markedRead.size)
        assertEquals(1, alerts.size)
        assertTrue(alerts[0].contains("没解析出"))
        // 已读后本轮邮件不会再次触发告警
        kotlinx.coroutines.runBlocking { svc.pollOnce() }
        assertEquals(1, alerts.size)
    }

    @Test
    fun `同类告警一小时限流`() {
        val svc = service(FakeInbox(mutableListOf()))
        kotlinx.coroutines.runBlocking {
            svc.alertThrottled("k", "第一条")
            svc.alertThrottled("k", "第二条")
            assertEquals(1, alerts.size)
            now += MailIngestService.ALERT_INTERVAL_MS + 1
            svc.alertThrottled("k", "第三条")
            assertEquals(2, alerts.size)
            // 不同 key 各自限流
            svc.alertThrottled("k2", "另一类")
            assertEquals(3, alerts.size)
        }
    }
}
