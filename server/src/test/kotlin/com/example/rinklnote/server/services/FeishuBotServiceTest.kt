package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BotConfigTable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 飞书 Bot 服务单测：配置读写（H2）、token 缓存单飞/过期前刷新、绑定码 5 分钟过期、
 * 发送报文形状（content 是「JSON 字符串套 JSON」）、事件加解密常量（含官方测试向量）。
 * 全部走 FakeFeishuHttp 假实现，零真实网络请求。
 */
class FeishuBotServiceTest {

    /** 假 HTTP：tenant_access_token 接口计数并返回固定 token；其余记为发送调用。 */
    private class FakeFeishuHttp(var delayMs: Long = 0, var failApi: Boolean = false) : FeishuHttpClient {
        data class Post(val url: String, val body: String, val headers: Map<String, String>)

        val posts = mutableListOf<Post>()
        var authCalls = 0

        override suspend fun post(url: String, jsonBody: String, headers: Map<String, String>): FeishuHttpResponse {
            if (delayMs > 0) delay(delayMs)
            if (url.contains("tenant_access_token")) {
                authCalls++
                return FeishuHttpResponse(200, """{"code":0,"msg":"ok","tenant_access_token":"t-fake-$authCalls","expire":7200}""")
            }
            posts.add(Post(url, jsonBody, headers))
            return if (failApi) {
                FeishuHttpResponse(200, """{"code":99991663,"msg":"token expired","data":{}}""")
            } else {
                FeishuHttpResponse(200, """{"code":0,"msg":"success","data":{"message_id":"om_sent"}}""")
            }
        }
    }

    @Before
    fun setupDb() {
        TestDatabase.connect("feishubot")
        transaction { SchemaUtils.create(BotConfigTable) }
    }

    // ── 配置 ──

    @Test
    fun `配置保存后可从数据库读回`() {
        val svc = FeishuBotService(FakeFeishuHttp())
        assertFalse(svc.isConfigured())
        svc.saveToDb("cli_a1", "secret1", "encrypt-key-1", "vt-1")

        assertTrue(svc.isConfigured())
        assertEquals("cli_a1", svc.getAppId())
        assertEquals("cli_****", svc.getMaskedAppId())
        assertEquals("encrypt-key-1", svc.getEncryptKey())
        assertEquals("vt-1", svc.getVerificationToken())

        // 新实例 loadFromDb 读回（启动路径）
        val svc2 = FeishuBotService(FakeFeishuHttp())
        assertFalse(svc2.isConfigured())
        svc2.loadFromDb()
        assertTrue(svc2.isConfigured())
        assertEquals("cli_a1", svc2.getAppId())
        assertEquals("encrypt-key-1", svc2.getEncryptKey())
        assertEquals("vt-1", svc2.getVerificationToken())
    }

    @Test
    fun `encrypt_key 留空表示未配置`() {
        val svc = FeishuBotService(FakeFeishuHttp())
        svc.saveToDb("cli_a1", "secret1", "", "")
        svc.loadFromDb()
        assertTrue(svc.isConfigured())
        assertNull(svc.getEncryptKey())
        assertNull(svc.getVerificationToken())
    }

    // ── tenant_access_token ──

    @Test
    fun `token 并发请求单飞只发一次 HTTP`() = runBlocking {
        val fake = FakeFeishuHttp(delayMs = 50)
        val svc = FeishuBotService(fake).apply { configure("cli", "sec") }
        val tokens = coroutineScope {
            listOf(async { svc.getTenantAccessToken() }, async { svc.getTenantAccessToken() }).awaitAll()
        }
        assertEquals(1, fake.authCalls)
        assertEquals(listOf("t-fake-1", "t-fake-1"), tokens)
    }

    @Test
    fun `token 过期前 300 秒内才刷新`() = runBlocking {
        var now = 1_000_000L
        val fake = FakeFeishuHttp()
        val svc = FeishuBotService(fake) { now }
        svc.configure("cli", "sec")

        svc.getTenantAccessToken()
        assertEquals(1, fake.authCalls)

        now += 6_899 // 距过期 301s：仍命中缓存（提前量 300s 之外）
        svc.getTenantAccessToken()
        assertEquals(1, fake.authCalls)

        now += 1 // 距过期 300s：进入提前量，触发刷新
        svc.getTenantAccessToken()
        assertEquals(2, fake.authCalls)
    }

    // ── 发消息 ──

    @Test
    fun `主动推送走 receive_id_type=open_id 且 content 为 JSON 套 JSON`() = runBlocking {
        val fake = FakeFeishuHttp()
        val svc = FeishuBotService(fake).apply { configure("cli", "sec") }
        assertTrue(svc.sendText("ou_user1", "你好\n世界"))

        val p = fake.posts.single()
        assertTrue("实际 url=$p", p.url.contains("/open-apis/im/v1/messages?receive_id_type=open_id"))
        assertTrue(p.body.contains("\"receive_id\":\"ou_user1\""))
        // content 字段是字符串化的 {"text":"..."}
        assertTrue("实际 body=${p.body}", p.body.contains("\"content\":\"{\\\"text\\\":"))
        assertEquals("Bearer t-fake-1", p.headers["Authorization"])
    }

    @Test
    fun `带消息 id 时走 reply 端点`() = runBlocking {
        val fake = FakeFeishuHttp()
        val svc = FeishuBotService(fake).apply { configure("cli", "sec") }
        assertTrue(svc.sendText("ou_user1", "回复内容", "om_msg_1"))
        val p = fake.posts.single()
        assertTrue(p.url.contains("/open-apis/im/v1/messages/om_msg_1/reply"))
    }

    @Test
    fun `未配置时发送直接失败不发 HTTP`() = runBlocking {
        val fake = FakeFeishuHttp()
        val svc = FeishuBotService(fake)
        assertFalse(svc.sendText("ou_user1", "内容"))
        assertTrue(fake.posts.isEmpty() && fake.authCalls == 0)
    }

    @Test
    fun `飞书业务 code 非 0 视为发送失败`() = runBlocking {
        val fake = FakeFeishuHttp(failApi = true)
        val svc = FeishuBotService(fake).apply { configure("cli", "sec") }
        // 飞书业务错误包在 HTTP 200 里（code!=0）；主动推送「成功才落去重」要求一并视为失败
        assertFalse(svc.sendText("ou_user1", "内容", "om_x"))
    }

    // ── 绑定码 ──

    @Test
    fun `绑定码往返且一次性`() {
        var now = 1_000_000L
        val svc = FeishuBotService(FakeFeishuHttp()) { now }
        val code = svc.createBindCode("ou_a")
        assertTrue("6 位数字", code.matches(Regex("""\d{6}""")))
        assertEquals("ou_a", svc.consumeBindCode(code))
        assertNull("消费后不可复用", svc.consumeBindCode(code))
    }

    @Test
    fun `绑定码 5 分钟过期`() {
        var now = 1_000_000L
        val svc = FeishuBotService(FakeFeishuHttp()) { now }
        val code = svc.createBindCode("ou_b")
        now += 299
        assertEquals("5 分钟内有效", "ou_b", svc.consumeBindCode(code))

        val code2 = svc.createBindCode("ou_c")
        now += 301
        assertNull("超 5 分钟过期", svc.consumeBindCode(code2))
    }

    // ── 事件加解密常量（FeishuCrypto） ──

    @Test
    fun `解密命中官方文档测试向量`() {
        // 出处：飞书官方《Encrypt Key 加密配置案例》：key="test key" → 明文 "hello world"
        assertEquals(
            "hello world",
            FeishuCrypto.decrypt("test key", "P37w+VZImNgPEO1RBhJ6RtKl7n6zymIbEG1pReEzghk=")
        )
    }

    @Test
    fun `验签为 SHA256 十六进制小写且对原始 body 计算`() {
        val sig = FeishuCrypto.signature("1700000000", "nonce", "my-key", """{"encrypt":"abc"}""")
        assertEquals(64, sig.length)
        assertTrue(sig.all { it.isDigit() || it in 'a'..'f' })
        // 顺序敏感：timestamp + nonce + encrypt_key + body
        val shuffled = FeishuCrypto.signature("my-key", "nonce", "1700000000", """{"encrypt":"abc"}""")
        org.junit.Assert.assertNotEquals(sig, shuffled)
    }

    @Test
    fun `加密解密往返`() {
        val plain = """{"challenge":"ajls384kdd","token":"vt-1"}"""
        val cipher = FeishuCrypto.encrypt("my-key", plain)
        assertEquals(plain, FeishuCrypto.decrypt("my-key", cipher))
    }
}
