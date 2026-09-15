package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.DefaultNLUService
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.services.nlu.RuleBasedParser
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 飞书消息处理器单测（H2，照 PhoneIntentRouterTest 的搭法）：
 * p2p 归一化落库（source=FEISHU）、群聊 mention 剥离、message_id 去重、推送开关指令、
 * 登录码指令。LLM 指向关闭端口快速失败，实际走规则解析，全程零真实网络。
 */
class FeishuMessageProcessorTest {

    /** 假 HTTP：token 接口固定返回；发送调用全部记录供断言。 */
    private class FakeFeishuHttp : FeishuHttpClient {
        data class Post(val url: String, val body: String, val headers: Map<String, String>)
        val posts = mutableListOf<Post>()

        override suspend fun post(url: String, jsonBody: String, headers: Map<String, String>): FeishuHttpResponse {
            if (url.contains("tenant_access_token")) {
                return FeishuHttpResponse(200, """{"code":0,"tenant_access_token":"t-fake","expire":7200}""")
            }
            posts.add(Post(url, jsonBody, headers))
            return FeishuHttpResponse(200, """{"code":0,"msg":"success","data":{}}""")
        }
    }

    private val llmParser = LLMParser(LLMParserConfig(apiKey = "dummy", baseUrl = "http://127.0.0.1:1", timeoutMs = 500))
    private val billService = BillService()
    private val budgetService = BudgetService()
    private val insightService = InsightService(llmParser, billService)
    private val nlu = DefaultNLUService(RuleBasedParser(), llmParser, billService)
    private val userService = UserService("test-secret", "test", "rinklnote")

    private lateinit var feishuService: FeishuBotService
    private lateinit var fakeHttp: FakeFeishuHttp

    @Before
    fun setup() {
        TestDatabase.connect("feishuproc")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable,
                BillsTable, BudgetsTable, VoiceKeywordsTable, WebhookEventTable, BotConfigTable
            )
        }
        billService.seedIfNeeded()
        fakeHttp = FakeFeishuHttp()
        feishuService = FeishuBotService(fakeHttp).apply { configure("cli_test", "secret_test") }
    }

    /** 构造 im.message.receive_v1 的 event 对象；content 为「JSON 字符串」。 */
    private fun event(
        openId: String = "ou_test_user_1",
        messageId: String = "om_test_1",
        chatType: String = "p2p",
        messageType: String = "text",
        text: String = "午餐20元",
        withMentions: Boolean = false
    ): JsonObject = buildJsonObject {
        put("sender", buildJsonObject {
            put("sender_id", buildJsonObject { put("open_id", openId) })
            put("sender_type", "user")
        })
        put("message", buildJsonObject {
            put("message_id", messageId)
            put("chat_id", "oc_chat_1")
            put("chat_type", chatType)
            put("message_type", messageType)
            put("content", """{"text":"$text"}""")
            if (withMentions) {
                put("mentions", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("key", "@_user_1")
                        put("id", buildJsonObject { put("open_id", "ou_bot_self") })
                        put("name", "记一笔")
                    })
                })
            }
        })
    }

    private suspend fun process(e: JsonObject) =
        FeishuMessageProcessor.process(e, feishuService, userService, billService, nlu, budgetService, insightService)

    private fun billCountFor(openId: String): Long {
        val user = userService.findByFeishuOpenId(openId) ?: return 0
        return transaction { BillsTable.selectAll().where { BillsTable.userId eq user.id }.count() }
    }

    private fun sentBodies(): List<String> = fakeHttp.posts.map { it.body }

    // ── p2p 归一化 + 落库 ──

    @Test
    fun `p2p 文本归一化并落库 source=FEISHU`() = runBlocking {
        process(event(text = "午餐20元"))

        assertEquals(1L, billCountFor("ou_test_user_1"))
        val user = userService.findByFeishuOpenId("ou_test_user_1")
        assertNotNull("open_id 应自动开户", user)
        val source = transaction {
            BillsTable.selectAll().where { BillsTable.userId eq user!!.id }.single()[BillsTable.billSource]
        }
        assertEquals("FEISHU", source)

        val reply = sentBodies().single()
        assertTrue("回复应含已记录：$reply", reply.contains("已记录"))
        assertTrue(reply.contains("20.00"))
        // 被动回复走 reply 端点
        assertTrue(fakeHttp.posts.single().url.contains("/open-apis/im/v1/messages/om_test_1/reply"))
    }

    @Test
    fun `新用户首条消息带欢迎语`() = runBlocking {
        process(event(text = "午餐20元"))
        val reply = sentBodies().single()
        assertTrue(reply.contains("欢迎"))
        // 第二条不再欢迎
        fakeHttp.posts.clear()
        process(event(text = "打车30元", messageId = "om_test_2"))
        assertFalse(sentBodies().single().contains("欢迎"))
    }

    // ── 群聊 mention ──

    @Test
    fun `群聊 mention 占位符剥离后正常落库`() = runBlocking {
        process(event(chatType = "group", text = "@_user_1 午餐20元", withMentions = true))
        assertEquals(1L, billCountFor("ou_test_user_1"))
        val reply = sentBodies().single()
        assertTrue(reply.contains("已记录"))
        assertFalse("回复不应含占位符：$reply", reply.contains("@_user_1"))
    }

    @Test
    fun `群聊无 mention 直接丢弃`() = runBlocking {
        process(event(chatType = "group", text = "午餐20元", withMentions = false))
        assertEquals(0L, billCountFor("ou_test_user_1"))
        assertTrue(fakeHttp.posts.isEmpty())
    }

    @Test
    fun `非文本消息丢弃`() = runBlocking {
        process(event(messageType = "audio", text = ""))
        assertEquals(0L, billCountFor("ou_test_user_1"))
        assertTrue(fakeHttp.posts.isEmpty())
    }

    // ── 去重 ──

    @Test
    fun `同一 message_id 去重只落一笔`() = runBlocking {
        process(event(messageId = "om_dup_1", text = "午餐20元"))
        process(event(messageId = "om_dup_1", text = "午餐20元")) // 飞书重试重推
        assertEquals(1L, billCountFor("ou_test_user_1"))
    }

    // ── 指令短路 ──

    @Test
    fun `推送开关指令改用户状态且不落账`() = runBlocking {
        process(event(text = "开启每日推送", messageId = "om_push_1"))

        val user = userService.findByFeishuOpenId("ou_test_user_1")
        assertNotNull(user)
        assertTrue(userService.findById(user!!.id)?.dailyReportEnabled == true)
        assertEquals(0L, billCountFor("ou_test_user_1"))
        val reply = sentBodies().single()
        assertTrue("回复应含已开启：$reply", reply.contains("已开启"))
    }

    @Test
    fun `登录码指令下发可消费的绑定码`() = runBlocking {
        process(event(text = "登录", messageId = "om_login_1"))

        val reply = sentBodies().single()
        assertTrue("回复应含绑定码：$reply", reply.contains("绑定码"))
        // 回复里的 6 位码能被 consumeBindCode 消费回 open_id
        val code = Regex("""\d{6}""").find(reply)!!.value
        assertEquals("ou_test_user_1", feishuService.consumeBindCode(code))
    }
}
