package com.example.rinklnote.server.services

import com.example.rinklnote.server.routes.MpCallbackCodec
import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.DefaultNLUService
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import com.example.rinklnote.server.services.wx.WxCryptUtil
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BotConfigTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import com.example.rinklnote.server.tables.VoiceKeywordsTable
import com.example.rinklnote.server.tables.WebhookEventTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 订阅号消息处理器单测（C-W2，H2，照 FeishuMessageProcessorTest 的搭法）：
 * 文本落库 source=MP、voice Recognition 进管线（免费平台转写）、非文本降级提示、
 * MsgId 去重（微信 5s 未响应会重试 3 次）、推送/登录码指令按「不支持」回复、
 * 4s 超时兜底文案、路由级明文/安全模式验签与回复 XML 往返（MpCallbackCodec，
 * 向量风格照 WxCryptUtilTest）。订阅号无任何发送通道，全程零网络。
 */
class MpMessageProcessorTest {

    private val llmParser = LLMParser(LLMParserConfig(apiKey = "dummy", baseUrl = "http://127.0.0.1:1", timeoutMs = 500))
    private val billService = BillService()
    private val budgetService = BudgetService()
    private val insightService = InsightService(llmParser, billService)
    private val nlu = DefaultNLUService(RuleBasedParser(), llmParser, billService)
    private val userService = UserService("test-secret", "test", "rinklnote")

    // 路由级验签用例的固定向量（风格照 WxCryptUtilTest）
    private val aesKey = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
    private val token = "mpToken"
    private val ghId = "gh_rinklnote" // 公众号原始 ID（消息 XML 的 ToUserName）

    @Before
    fun setup() {
        TestDatabase.connect("mpproc")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable,
                BillsTable, BudgetsTable, VoiceKeywordsTable, WebhookEventTable, BotConfigTable
            )
        }
        billService.seedIfNeeded()
    }

    /** 构造消息 XML 字段表（微信公众平台标准字段）。 */
    private fun fields(
        openid: String = "oMP_openid_1",
        msgId: String = "200",
        msgType: String = "text",
        content: String = "午餐20元",
        recognition: String? = null
    ): Map<String, String> = linkedMapOf(
        "ToUserName" to ghId,
        "FromUserName" to openid,
        "CreateTime" to "1726400000",
        "MsgType" to msgType,
        "Content" to content,
        "MsgId" to msgId
    ) + (if (recognition != null) mapOf("Recognition" to recognition) else emptyMap())

    private suspend fun process(f: Map<String, String>, replyTimeoutMs: Long = MpMessageProcessor.REPLY_DEADLINE_MS) =
        MpMessageProcessor.process(f, userService, billService, nlu, budgetService, insightService, replyTimeoutMs)

    private fun billCountFor(openid: String): Long {
        val user = userService.findByWechatOpenid(openid) ?: return 0
        return transaction { BillsTable.selectAll().where { BillsTable.userId eq user.id }.count() }
    }

    // ── 文本落库 + 开户 ──

    @Test
    fun `文本归一化并落库 source=MP`() = runBlocking {
        val reply = process(fields(msgId = "1", content = "午餐20元"))

        assertEquals(1L, billCountFor("oMP_openid_1"))
        val user = userService.findByWechatOpenid("oMP_openid_1")
        assertNotNull("openid 应自动开户", user)
        val source = transaction {
            BillsTable.selectAll().where { BillsTable.userId eq user!!.id }.single()[BillsTable.billSource]
        }
        assertEquals("MP", source)
        assertNotNull("同步被动回复应产出回复文本", reply)
        assertTrue("回复应含已记录：$reply", reply!!.contains("已记录"))
    }

    @Test
    fun `新用户首条消息带欢迎语`() = runBlocking {
        val reply = process(fields(msgId = "2", content = "午餐20元"))
        assertTrue(reply!!.contains("欢迎"))
        val reply2 = process(fields(msgId = "3", content = "打车30元"))
        assertTrue(!reply2!!.contains("欢迎"))
    }

    // ── 语音转写（平台免费 Recognition，三通道唯一）──

    @Test
    fun `voice 的 Recognition 进管线落库 source=MP`() = runBlocking {
        val reply = process(fields(msgId = "voice_1", msgType = "voice", content = "", recognition = "午餐20元"))

        assertEquals(1L, billCountFor("oMP_openid_1"))
        assertTrue("回复应含已记录：$reply", reply!!.contains("已记录"))
    }

    @Test
    fun `voice 无 Recognition 回听不清提示`() = runBlocking {
        val reply = process(fields(msgId = "voice_2", msgType = "voice", content = "", recognition = null))
        assertTrue("应提示没听清：$reply", reply!!.contains("没听清"))
        assertEquals(0L, billCountFor("oMP_openid_1"))
    }

    @Test
    fun `图片等其他类型回提示`() = runBlocking {
        val reply = process(fields(msgId = "img_1", msgType = "image", content = ""))
        assertTrue(reply!!.contains("文字"))
        assertEquals(0L, billCountFor("oMP_openid_1"))
    }

    // ── 去重（微信 5s 未收到响应会重试重推共 3 次，同一 MsgId 只落一笔）──

    @Test
    fun `同一 MsgId 去重只落一笔`() = runBlocking {
        val first = process(fields(msgId = "dup_1", content = "午餐20元"))
        assertNotNull(first)
        val retry = process(fields(msgId = "dup_1", content = "午餐20元")) // 微信重试重推
        assertNull("重试应被去重挡下（回空串）", retry)
        assertEquals(1L, billCountFor("oMP_openid_1"))
    }

    // ── 指令短路（订阅号只收不推：推送/登录码指令只能回「不支持」）──

    @Test
    fun `推送指令回不支持且不改状态`() = runBlocking {
        val reply = process(fields(msgId = "push_1", content = "开启每日推送"))

        val user = userService.findByWechatOpenid("oMP_openid_1")
        assertNotNull(user)
        // 状态未被改动：订阅号改了也送不到（findAllPushUsers 已排除仅订阅号用户）
        assertTrue(userService.findById(user!!.id)?.dailyReportEnabled == false)
        assertEquals(0L, billCountFor("oMP_openid_1"))
        assertTrue("回复应说明不支持推送：$reply", reply!!.contains("不支持"))
    }

    @Test
    fun `登录码指令回不支持`() = runBlocking {
        val reply = process(fields(msgId = "login_1", content = "登录"))
        assertTrue(reply!!.contains("不支持"))
    }

    // ── 超时兜底（LLM 兜底查询默认 10s，必须被 4s 硬顶下来防 5s 窗口击穿）──

    @Test
    fun `超时返回兜底文案`() = runBlocking {
        // replyTimeoutMs=0：withTimeoutOrNull(0) 不执行管线立即超时（kotlinx-coroutines 1.8.1 语义，
        // 见 Timeout.kt L97），确定性命中兜底分支
        val reply = process(fields(msgId = "timeout_1", content = "随便聊聊"), replyTimeoutMs = 0)
        assertEquals(MpMessageProcessor.TIMEOUT_REPLY, reply)
    }

    // ── 路由级用例（MpWebhookRoutes 的 codec，向量风格照 WxCryptUtilTest）──

    @Test
    fun `明文模式接入验证原样返回 echostr 且验签失败拒绝`() {
        // 明文模式：signature = SHA1(sorted(token,timestamp,nonce))，echostr 原样回显
        val sig = WxCryptUtil.signature3(token, "1726400000", "nonce1")
        assertEquals("echo-xyz", MpCallbackCodec.verifyEchostr(token, null, sig, "1726400000", "nonce1", "echo-xyz"))
        // 篡改签名 → null（路由层回 403）
        assertNull(MpCallbackCodec.verifyEchostr(token, null, "deadbeef", "1726400000", "nonce1", "echo-xyz"))
        // 安全模式（配置了 aesKey）：echostr 是密文，4 参验签后解密回明文
        val cipherEcho = WxCryptUtil.encrypt(aesKey, "plain-echo", ghId)
        val sig4 = WxCryptUtil.signature(token, "1726400000", "nonce1", cipherEcho)
        assertEquals("plain-echo", MpCallbackCodec.verifyEchostr(token, aesKey, sig4, "1726400000", "nonce1", cipherEcho))
    }

    @Test
    fun `明文消息解析与被动回复 XML 往返`() = runBlocking {
        // 明文模式 POST：body 即消息 XML 明文，signature 按 3 参校验
        val xml = WxCryptUtil.buildXml(
            linkedMapOf(
                "ToUserName" to ghId, "FromUserName" to "oMP_rt", "CreateTime" to "1726400000",
                "MsgType" to "text", "Content" to "午餐20元", "MsgId" to "910"
            )
        )
        val sig = WxCryptUtil.signature3(token, "1726400000", "nonce1")
        val parsed = MpCallbackCodec.parseMessage(token, null, sig, "1726400000", "nonce1", xml)
        assertNotNull(parsed)
        assertTrue(!parsed!!.encrypted)
        assertEquals("910", parsed.fields["MsgId"])
        assertEquals("午餐20元", parsed.fields["Content"])
        // 篡改签名 → null
        assertNull(MpCallbackCodec.parseMessage(token, null, "0000000", "1726400000", "nonce1", xml))

        // 同步处理产出回复 → 明文回复 XML → 解析还原，验证被动回复格式往返
        val reply = MpMessageProcessor.process(parsed.fields, userService, billService, nlu, budgetService, insightService)
        assertTrue(reply!!.contains("已记录"))
        val replyXml = MpCallbackCodec.buildTextReply(
            token, null, parsed.receiveId,
            botUserName = ghId, toUserName = "oMP_rt",
            timestamp = "1726400000", nonce = "nonce1", replyText = reply
        )
        val replyFields = WxCryptUtil.parseXml(replyXml)
        assertEquals("收件人应为原发送者 openid", "oMP_rt", replyFields["ToUserName"])
        assertEquals("发件人应为公众号（原 ToUserName）", ghId, replyFields["FromUserName"])
        assertEquals("text", replyFields["MsgType"])
        assertTrue(replyFields["Content"]!!.contains("已记录"))

        // 安全模式回复：加密包裹后可解密还原（与企微同套底座）
        val safeXml = MpCallbackCodec.buildTextReply(
            token, aesKey, ghId,
            botUserName = ghId, toUserName = "oMP_rt",
            timestamp = "1726400000", nonce = "nonce1", replyText = reply
        )
        val safeOuter = WxCryptUtil.parseXml(safeXml)
        assertTrue(safeOuter.containsKey("Encrypt"))
        val decrypted = WxCryptUtil.decrypt(aesKey, safeOuter["Encrypt"]!!)
        assertEquals("安全模式回复加密 receiveId 为公众号 appid 占位", ghId, decrypted.receiveId)
        assertTrue(WxCryptUtil.parseXml(decrypted.message)["Content"]!!.contains("已记录"))
    }
}
