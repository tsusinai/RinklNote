package com.example.rinklnote.server.services

import com.example.rinklnote.server.routes.WecomCallbackCodec
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * 企微消息处理器单测（C-W1，H2，照 FeishuMessageProcessorTest 的搭法）：
 * 文本落库 source=WECOM、自动开户、非文本降级提示、MsgId 去重、推送开关指令、
 * 登录码指令、超时兜底、webhook 推送结构，以及路由级加解密用例（WecomCallbackCodec，
 * 向量风格照 WxCryptUtilTest）。全程零真实网络。
 */
class WecomMessageProcessorTest {

    /** 假 HTTP：推送调用全部记录供断言，可配置失败响应。 */
    private class FakeWecomHttp : WecomHttpClient {
        data class Post(val url: String, val body: String)
        val posts = mutableListOf<Post>()
        var errcode = 0

        override suspend fun post(url: String, jsonBody: String): WecomHttpResponse {
            posts.add(Post(url, jsonBody))
            return WecomHttpResponse(200, """{"errcode":$errcode,"errmsg":"ok"}""")
        }
    }

    private val llmParser = LLMParser(LLMParserConfig(apiKey = "dummy", baseUrl = "http://127.0.0.1:1", timeoutMs = 500))
    private val billService = BillService()
    private val budgetService = BudgetService()
    private val insightService = InsightService(llmParser, billService)
    private val nlu = DefaultNLUService(RuleBasedParser(), llmParser, billService)
    private val userService = UserService("test-secret", "test", "rinklnote")

    private lateinit var wecomService: WecomBotService
    private lateinit var fakeHttp: FakeWecomHttp

    // 路由级加解密用例的固定向量（风格照 WxCryptUtilTest：43 位 key 自造、往返验证）
    private val aesKey = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
    private val token = "wecomToken"
    private val corpid = "ww58c7d2d24a123456"

    @Before
    fun setup() {
        TestDatabase.connect("wecomproc")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable,
                BillsTable, BudgetsTable, VoiceKeywordsTable, WebhookEventTable, BotConfigTable
            )
        }
        billService.seedIfNeeded()
        fakeHttp = FakeWecomHttp()
        wecomService = WecomBotService(fakeHttp).apply {
            configure(token, aesKey, "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=demo")
        }
    }

    /** 构造解密后的内层回调 XML 字段表（企微标准回调字段）。 */
    private fun fields(
        userid: String = "wecom_user_1",
        msgId: String = "100",
        msgType: String = "text",
        content: String = "午餐20元"
    ): Map<String, String> = linkedMapOf(
        "ToUserName" to corpid,
        "FromUserName" to userid,
        "CreateTime" to "1726400000",
        "MsgType" to msgType,
        "Content" to content,
        "MsgId" to msgId,
        "AgentID" to "1000002"
    )

    private suspend fun process(f: Map<String, String>, replyTimeoutMs: Long = WecomMessageProcessor.REPLY_DEADLINE_MS) =
        WecomMessageProcessor.process(f, wecomService, userService, billService, nlu, budgetService, insightService, replyTimeoutMs)

    private fun billCountFor(userid: String): Long {
        val user = userService.findByWecomUserid(userid) ?: return 0
        return transaction { BillsTable.selectAll().where { BillsTable.userId eq user.id }.count() }
    }

    // ── 文本落库 + 开户 ──

    @Test
    fun `文本归一化并落库 source=WECOM`() = runBlocking {
        val reply = process(fields(msgId = "1", content = "午餐20元"))

        assertEquals(1L, billCountFor("wecom_user_1"))
        val user = userService.findByWecomUserid("wecom_user_1")
        assertNotNull("userid 应自动开户", user)
        val source = transaction {
            BillsTable.selectAll().where { BillsTable.userId eq user!!.id }.single()[BillsTable.billSource]
        }
        assertEquals("WECOM", source)
        assertNotNull("同步被动回复应产出回复文本", reply)
        assertTrue("回复应含已记录：$reply", reply!!.contains("已记录"))
    }

    @Test
    fun `新用户首条消息带欢迎语`() = runBlocking {
        val reply = process(fields(msgId = "2", content = "午餐20元"))
        assertTrue(reply!!.contains("欢迎"))
        // 第二条不再欢迎
        val reply2 = process(fields(msgId = "3", content = "打车30元"))
        assertTrue(!reply2!!.contains("欢迎"))
    }

    // ── 非文本降级 ──

    @Test
    fun `非文本消息回复暂支持文字提示`() = runBlocking {
        val reply = process(fields(msgId = "4", msgType = "image", content = ""))
        assertTrue("应提示只支持文字：$reply", reply!!.contains("文字"))
        assertEquals(0L, billCountFor("wecom_user_1"))
    }

    // ── 去重（企微 5s 未收到响应会重试重推，同一 MsgId 只落一笔）──

    @Test
    fun `同一 MsgId 去重只落一笔`() = runBlocking {
        val first = process(fields(msgId = "dup_1", content = "午餐20元"))
        assertNotNull(first)
        val retry = process(fields(msgId = "dup_1", content = "午餐20元")) // 企微重试重推
        assertNull("重试应被去重挡下（回空串）", retry)
        assertEquals(1L, billCountFor("wecom_user_1"))
    }

    // ── 指令短路 ──

    @Test
    fun `推送开关指令改用户状态且不落账`() = runBlocking {
        val reply = process(fields(msgId = "push_1", content = "开启每日推送"))

        val user = userService.findByWecomUserid("wecom_user_1")
        assertNotNull(user)
        assertTrue(userService.findById(user!!.id)?.dailyReportEnabled == true)
        assertEquals(0L, billCountFor("wecom_user_1"))
        assertTrue("回复应含已开启：$reply", reply!!.contains("已开启"))
        // 企微边界：回复里说明推送走群机器人 webhook 维度（非单聊）
        assertTrue(reply.contains("群"))
    }

    @Test
    fun `登录码指令回复可消费的绑定码`() = runBlocking {
        val reply = process(fields(msgId = "login_1", content = "登录"))

        assertTrue("回复应含绑定码：$reply", reply!!.contains("绑定码"))
        // 回复里的 6 位码能被 consumeBindCode 消费回 userid
        val code = Regex("""\d{6}""").find(reply)!!.value
        assertEquals("wecom_user_1", wecomService.consumeBindCode(code))
    }

    // ── 超时兜底 ──

    @Test
    fun `超时返回兜底文案`() = runBlocking {
        // replyTimeoutMs=0：withTimeoutOrNull(0) 不执行管线立即超时（kotlinx-coroutines 1.8.1 语义，
        // 见 Timeout.kt L97），确定性命中兜底分支
        val reply = process(fields(msgId = "timeout_1", content = "随便聊聊"), replyTimeoutMs = 0)
        assertEquals(WecomMessageProcessor.TIMEOUT_REPLY, reply)
    }

    // ── 主动推送（PushScheduler WECOM 通道的发送实现）──

    @Test
    fun `pushText 按官方 webhook 结构推送`() = runBlocking {
        assertTrue(wecomService.pushText("今日总结"))
        val post = fakeHttp.posts.single()
        assertEquals("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=demo", post.url)
        val obj = Json.parseToJsonElement(post.body).jsonObject
        assertEquals("text", obj["msgtype"]!!.jsonPrimitive.content)
        assertEquals("今日总结", obj["text"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pushText errcode 非零或未配 webhook 视为失败`() = runBlocking {
        fakeHttp.errcode = 93000
        assertTrue(!wecomService.pushText("hi"))
        // 未配 webhook URL 的服务直接失败（不发网络）
        val noPush = WecomBotService(fakeHttp).apply { configure(token, aesKey) }
        assertTrue(!noPush.pushText("hi"))
        assertEquals("未配 URL 不应产生网络调用", 1, fakeHttp.posts.size)
    }

    // ── 路由级加解密用例（WecomBotWebhookRoutes 的 codec，向量风格照 WxCryptUtilTest）──

    @Test
    fun `URL 验证 echostr 解密回显且验签失败拒绝`() {
        // echostr 是密文：验签（对密文）通过后解密，回显明文
        val echostr = WxCryptUtil.encrypt(aesKey, "plain-echo-123", corpid)
        val sig = WxCryptUtil.signature(token, "1726400000", "nonce1", echostr)
        assertEquals(
            "plain-echo-123",
            WecomCallbackCodec.decryptEchostr(token, aesKey, sig, "1726400000", "nonce1", echostr)
        )
        // 验签不过（篡改的签名）→ null（路由层回 403）
        assertNull(WecomCallbackCodec.decryptEchostr(token, aesKey, "deadbeef", "1726400000", "nonce1", echostr))
        // 解密不过（key 不匹配）→ null
        assertNull(
            WecomCallbackCodec.decryptEchostr(
                token, "zyxwvutsrqponmlkjihgfedcba0123456789AB", sig, "1726400000", "nonce1", echostr
            )
        )
    }

    @Test
    fun `回调验签解密与被动回复加密往返`() = runBlocking {
        // 构造加密回调：内层消息 XML → AES 加密（receiveId=corpid）→ 外层 <Encrypt> 包裹 + msg_signature
        val innerXml = WxCryptUtil.buildXml(
            linkedMapOf(
                "ToUserName" to corpid, "FromUserName" to "wecom_user_rt", "CreateTime" to "1726400000",
                "MsgType" to "text", "Content" to "午餐20元", "MsgId" to "900"
            )
        )
        val cipher = WxCryptUtil.encrypt(aesKey, innerXml, corpid)
        val outer = WxCryptUtil.buildXml(
            linkedMapOf("ToUserName" to corpid, "Encrypt" to cipher, "AgentID" to "1000002")
        )
        val sig = WxCryptUtil.signature(token, "1726400000", "nonce1", cipher)

        val parsed = WecomCallbackCodec.parseEncryptedMessage(token, aesKey, sig, "1726400000", "nonce1", outer)
        assertNotNull(parsed)
        assertEquals("900", parsed!!.fields["MsgId"])
        assertEquals("午餐20元", parsed.fields["Content"])
        assertEquals("明文尾部校验串应为 corpid", corpid, parsed.receiveId)
        // 验签不过（篡改签名）→ null
        assertNull(WecomCallbackCodec.parseEncryptedMessage(token, aesKey, "0000000", "1726400000", "nonce1", outer))

        // 同步处理产出回复 → 加密被动回复包 → 解密还原，验证 XML 回复格式往返
        val reply = WecomMessageProcessor.process(
            parsed.fields, wecomService, userService, billService, nlu, budgetService, insightService
        )
        assertTrue(reply!!.contains("已记录"))
        val replyBody = WecomCallbackCodec.buildEncryptedTextReply(
            token, aesKey, parsed.receiveId,
            botUserName = corpid, toUserName = "wecom_user_rt",
            timestamp = "1726400000", nonce = "nonce1", replyText = reply
        )
        val replyOuter = WxCryptUtil.parseXml(replyBody)
        assertTrue(replyOuter.containsKey("Encrypt"))
        assertTrue(replyOuter.containsKey("MsgSignature"))
        // 回复包自带验签可用同 token 复核
        assertTrue(
            WxCryptUtil.verifySignature(
                replyOuter["MsgSignature"]!!, token, "1726400000", "nonce1", replyOuter["Encrypt"]!!
            )
        )
        val decrypted = WxCryptUtil.decrypt(aesKey, replyOuter["Encrypt"]!!)
        assertEquals("回复加密 receiveId 回填 corpid", corpid, decrypted.receiveId)
        val replyFields = WxCryptUtil.parseXml(decrypted.message)
        assertEquals("收件人应为原发送者", "wecom_user_rt", replyFields["ToUserName"])
        assertEquals("发件人应为机器人（原 ToUserName）", corpid, replyFields["FromUserName"])
        assertEquals("text", replyFields["MsgType"])
        assertTrue(replyFields["Content"]!!.contains("已记录"))
    }
}
