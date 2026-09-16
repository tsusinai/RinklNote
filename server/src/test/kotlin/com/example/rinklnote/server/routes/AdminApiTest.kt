package com.example.rinklnote.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.rinklnote.server.plugins.AdminIdentities
import com.example.rinklnote.server.services.AdminService
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.QQBotService
import com.example.rinklnote.server.services.QQBotWebSocketClient
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.DefaultNLUService
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BotConfigTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.PushLogTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 管理端 P0 的 HTTP 级用例（W4）。
 *
 * 不引入 ktor-server-test-host（零新依赖约束）：直接 embeddedServer(Netty, port = 0) 起
 * 真实服务，挂**真实的**路由函数（adminRoutes / insightRoutes / qqBotManageRoutes / authRoutes），
 * 用既有依赖 ktor-client-cio 发真请求，覆盖 403/200 语义而非只测纯函数。
 *
 * 数据库：整类共用一个 H2（TestDatabase.connect 只在 @BeforeClass 调一次）—— Netty IO 线程的
 * Exposed 事务管理器会按线程缓存，测试中途换默认库会让请求线程读到旧库（见 TestDatabase 注释）。
 * 每个用例用 deleteAll 清表而不是重连。
 */
class AdminApiTest {

    companion object {
        private const val JWT_SECRET = "unit-test-admin-jwt-secret-0123456789abcdef"
        private const val ISSUER = "test-issuer"
        private const val AUDIENCE = "test-audience"

        private const val ADMIN_PHONE = "13800000001"
        private const val USER_PHONE = "13900000002"

        /** 固定「现在」：todayStartMillis 注入它，今日/昨日账单用它 ±60s，避免跨午夜抖动。 */
        private val FIXED_NOW = System.currentTimeMillis()

        private lateinit var server: ApplicationEngine
        private var port: Int = 0
        private lateinit var client: HttpClient
        private lateinit var userService: UserService
        private lateinit var adminService: AdminService
        private lateinit var qqBotService: QQBotService
        private lateinit var qqWsClient: QQBotWebSocketClient
        private val originalAdminEnv: String? = System.getenv("ADMIN_IDENTITIES")

        @BeforeClass
        @JvmStatic
        fun startServer() {
            TestDatabase.connect("adminapi")
            transaction {
                SchemaUtils.create(
                    UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable,
                    BillsTable, PushLogTable, BotConfigTable
                )
            }

            userService = UserService(JWT_SECRET, ISSUER, AUDIENCE)
            val billService = BillService()
            val llmParser = LLMParser(
                LLMParserConfig(apiKey = "test-key", baseUrl = "http://127.0.0.1:1", model = "test-model", timeoutMs = 100)
            )
            val insightService = InsightService(llmParser = llmParser, billService = billService, anomalyThreshold = 1.5)
            val nluService = DefaultNLUService(ruleBasedParser = RuleBasedParser(), llmParser = llmParser, billService = billService)
            qqBotService = QQBotService()
            // 只构造不 start()：/bot/status 只要 getter；start 会真的去拨 QQ 网关（测试不可联网）。
            qqWsClient = QQBotWebSocketClient(
                qqBotService, userService, billService, nluService, BudgetService(), insightService
            )
            adminService = AdminService(
                dbTypeName = { "H2" },
                llmConfigured = { true },
                asrConfigured = { false },
                todayStartMillis = { FIXED_NOW }
            )

            server = embeddedServer(Netty, port = 0) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
                }
                install(Authentication) {
                    jwt("auth-jwt") {
                        realm = "RinklNote API"
                        verifier(
                            JWT.require(Algorithm.HMAC256(JWT_SECRET))
                                .withAudience(AUDIENCE)
                                .withIssuer(ISSUER)
                                .build()
                        )
                        validate { credential ->
                            val userId = credential.payload.getClaim("userId").asLong()
                            if (userId != null) JWTPrincipal(credential.payload) else null
                        }
                    }
                }
                routing {
                    adminRoutes(adminService, qqBotService, qqWsClient)
                    insightRoutes(insightService)
                    qqBotManageRoutes(qqBotService, userService)
                    authRoutes(userService, qqBotService)
                }
            }.start(wait = false)
            port = runBlocking { server.resolvedConnectors().first().port }
            client = HttpClient(CIO)
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            // 恢复名单为环境变量原值，避免污染同 JVM 里后续跑的其它用例。
            AdminIdentities.refresh(originalAdminEnv)
            client.close()
            server.stop(100, 1000)
            qqWsClient.shutdown()
            qqBotService.shutdown()
        }
    }

    @Before
    fun cleanTables() {
        // 名单恢复到「环境变量原值」（CI 里通常为空 = 无管理员），用例内自行 refresh 注入。
        AdminIdentities.refresh(originalAdminEnv)
        transaction {
            BillsTable.deleteAll()
            PushLogTable.deleteAll()
            BotConfigTable.deleteAll()
            SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll()
            CategoriesTable.deleteAll()
            UsersTable.deleteAll()
            // bills 外键依赖的分类 / 账户种子各一行
            CategoriesTable.insert {
                it[id] = 1L
                it[name] = "三餐"
                it[iconName] = "meal"
                it[billType] = "EXPENSE"
            }
            AccountsTable.insert {
                it[id] = 1L
                it[name] = "无账户"
                it[iconColor] = "#888888"
            }
        }
    }

    // ── HTTP 工具 ──

    private fun url(path: String): String = "http://127.0.0.1:$port$path"

    private suspend fun get(path: String, token: String?): HttpResponse =
        client.get(url(path)) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }

    private suspend fun put(path: String, token: String?, body: String): HttpResponse =
        client.put(url(path)) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun post(path: String, token: String?, body: String): HttpResponse =
        client.post(url(path)) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    // JsonObject 取值捷径（get 返回 JsonElement，要先 jsonPrimitive 才能取标量）
    private fun JsonObject.longAt(key: String): Long = this[key]!!.jsonPrimitive.long
    private fun JsonObject.intAt(key: String): Int = this[key]!!.jsonPrimitive.int
    private fun JsonObject.boolAt(key: String): Boolean = this[key]!!.jsonPrimitive.boolean

    /** 注册并返回 (userId, token)。手机号唯一，所以每个用例自选未占用的号段。 */
    private fun register(phone: String): Pair<Long, String> =
        userService.register(phone, "pass123456")

    private fun insertBill(userId: Long, date: Long, deleted: Boolean = false) = transaction {
        BillsTable.insert {
            it[BillsTable.userId] = userId
            it[amount] = 10.0
            it[amountMinor] = 1000L
            it[billType] = "EXPENSE"
            it[categoryId] = 1L
            it[categoryName] = "三餐"
            it[accountId] = 1L
            it[BillsTable.date] = date
            it[billSource] = "APP"
            it[createdAt] = date
            it[BillsTable.deleted] = deleted
        } get BillsTable.id
    }

    // ── 1. 双保险判定核心（纯函数，无 HTTP）──

    @Test
    fun `AdminIdentities 双保险判定与名单刷新`() {
        AdminIdentities.refresh("$ADMIN_PHONE, 42 ,")
        // 名单命中：手机号 / userId 任一
        assertTrue(AdminIdentities.matches(ADMIN_PHONE, null))
        assertTrue(AdminIdentities.matches(null, 42L))
        assertFalse(AdminIdentities.matches(USER_PHONE, 7L))
        // claim 单独放行（名单外身份 + admin claim）
        assertTrue(AdminIdentities.isAllowed(adminClaim = true, phone = USER_PHONE, userId = 7L))
        // 名单单独放行（无 claim）
        assertTrue(AdminIdentities.isAllowed(adminClaim = false, phone = ADMIN_PHONE, userId = null))
        // 双未命中 → 拒绝
        assertFalse(AdminIdentities.isAllowed(adminClaim = false, phone = USER_PHONE, userId = 7L))
        // 名单清空 → 全拒
        AdminIdentities.refresh(null)
        assertFalse(AdminIdentities.matches(ADMIN_PHONE, null))
    }

    @Test
    fun `手机号掩码分级降级`() {
        assertEquals("138****1234", AdminService.maskPhone("13800001234"))
        assertEquals("12****67", AdminService.maskPhone("1234567"))
        assertEquals("****", AdminService.maskPhone("12345"))
        assertNull(AdminService.maskPhone(null))
        assertNull(AdminService.maskPhone("  "))
    }

    // ── 2. 管理端接口的 403 / 401 语义 ──

    @Test
    fun `非管理员访问全部管理端接口返回 403`() {
        val (_, token) = register(USER_PHONE)
        runBlocking {
            for (path in listOf(
                "/api/admin/overview", "/api/admin/users", "/api/admin/push-logs", "/api/admin/bot/status"
            )) {
                val resp = get(path, token)
                assertEquals("非管理员 GET $path 应 403", HttpStatusCode.Forbidden, resp.status)
            }
        }
    }

    @Test
    fun `未登录访问管理端接口返回 401`() {
        runBlocking {
            assertEquals(HttpStatusCode.Unauthorized, get("/api/admin/overview", null).status)
        }
    }

    @Test
    fun `admin claim 独立于活名单放行旧 token`() {
        // 签发时在名单里 → token 带 admin claim；之后名单清空，旧 token 仍应放行（claim 是签发时刻快照）。
        AdminIdentities.refresh(ADMIN_PHONE)
        val (_, adminToken) = register(ADMIN_PHONE)
        AdminIdentities.refresh(null)
        val (_, plainToken) = register(USER_PHONE)
        runBlocking {
            assertEquals(HttpStatusCode.OK, get("/api/admin/overview", adminToken).status)
            assertEquals(HttpStatusCode.Forbidden, get("/api/admin/overview", plainToken).status)
        }
    }

    // ── 3. overview 计数 ──

    @Test
    fun `管理员 overview 计数正确`() {
        AdminIdentities.refresh(ADMIN_PHONE)
        val (adminId, adminToken) = register(ADMIN_PHONE)
        val (u1, _) = register(USER_PHONE)
        val (u2, _) = register("13700000003")
        // u2 绑定 QQ（openid 有值）
        transaction { UsersTable.update({ UsersTable.id eq u2 }) { it[qqOpenid] = "openid-u2" } }
        // 账单：今日 admin / 今日+昨日 u1 / 今日 u2（软删除）→ 有效 3 笔、今日活跃 2 人
        insertBill(adminId, FIXED_NOW + 60_000)
        insertBill(u1, FIXED_NOW + 60_000)
        insertBill(u1, FIXED_NOW - 60_000)
        insertBill(u2, FIXED_NOW + 60_000, deleted = true)

        runBlocking {
            val body = get("/api/admin/overview", adminToken).json()
            assertEquals(3L, body.longAt("totalUsers"))
            assertEquals(3L, body.longAt("totalBills"))
            assertEquals(2L, body.longAt("todayActiveUsers"))
            assertEquals(1L, body.longAt("qqBoundUsers"))
            assertEquals("H2", body["dbType"]!!.jsonPrimitive.content)
            assertEquals(true, body.boolAt("llmConfigured"))
            assertEquals(false, body.boolAt("asrConfigured"))
        }
    }

    // ── 4. 用户列表：掩码 + 查询 ──

    @Test
    fun `用户列表手机号掩码且支持尾段与 userId 查询`() {
        AdminIdentities.refresh(ADMIN_PHONE)
        val (_, adminToken) = register(ADMIN_PHONE)
        val (u1, _) = register(USER_PHONE)
        register("13700000003")
        // u1 双通道绑定
        transaction {
            UsersTable.update({ UsersTable.id eq u1 }) {
                it[qqNumber] = "10001"
                it[qqOpenid] = "openid-u1"
            }
        }

        runBlocking {
            val page = get("/api/admin/users", adminToken).json()
            assertEquals(3L, page.longAt("total"))
            assertEquals(20, page.intAt("pageSize"))
            val items = page["items"]!!.jsonArray
            assertEquals(3, items.size)
            // 掩码格式：前 3 + 4 星 + 后 4，绝无整号回显
            val phones = items.map { it.jsonObject["phone"]!!.jsonPrimitive.content }
            phones.forEach { assertTrue("掩码格式错误: $it", Regex("^\\d{3}\\*{4}\\d{4}$").matches(it)) }
            assertTrue(phones.contains("138****0001"))
            assertTrue(phones.contains("139****0002"))
            assertTrue(phones.contains("137****0003"))
            // u1 双通道 → boundChannels = 2，其余 0
            val u1Row = items.first { it.jsonObject.longAt("id") == u1 }.jsonObject
            assertEquals(2, u1Row.intAt("boundChannels"))

            // 尾段查询：0003 只命中 13700000003
            val byTail = get("/api/admin/users?query=0003", adminToken).json()
            assertEquals(1L, byTail.longAt("total"))
            assertEquals("137****0003", byTail["items"]!!.jsonArray.first().jsonObject["phone"]!!.jsonPrimitive.content)

            // userId 精确查询
            val byId = get("/api/admin/users?query=$u1", adminToken).json()
            assertEquals(1L, byId.longAt("total"))
            assertEquals(u1, byId["items"]!!.jsonArray.first().jsonObject.longAt("id"))
        }
    }

    // ── 5. suggest-config 收口：非管理员 PUT 403 / GET 200，管理员可写 ──

    @Test
    fun `suggest-config 非管理员只读管理员可写`() {
        val (_, userToken) = register(USER_PHONE)
        AdminIdentities.refresh(ADMIN_PHONE)
        val (_, adminToken) = register(ADMIN_PHONE)

        val newConfig = """{"enabled":false,"lookbackDays":14,"minOccurrences":3,"displayDuration":5000,"timeWindows":[]}"""
        runBlocking {
            // 非管理员：读 200（所有登录用户可读）、写 403
            assertEquals(HttpStatusCode.OK, get("/api/insights/suggest-config", userToken).status)
            assertEquals(
                HttpStatusCode.Forbidden,
                put("/api/insights/suggest-config", userToken, newConfig).status
            )
            // 管理员：写 200，随后读回可见改动
            assertEquals(
                HttpStatusCode.OK,
                put("/api/insights/suggest-config", adminToken, newConfig).status
            )
            val saved = get("/api/insights/suggest-config", userToken).json()
            assertEquals(false, saved.boolAt("enabled"))
            assertEquals(14, saved.intAt("lookbackDays"))
        }
    }

    // ── 6. qq-bot config 收口：非管理员 POST 403，管理员可写 ──

    @Test
    fun `qq-bot config 非管理员 POST 403 管理员可写`() {
        val (_, userToken) = register(USER_PHONE)
        AdminIdentities.refresh(ADMIN_PHONE)
        val (_, adminToken) = register(ADMIN_PHONE)
        val body = """{"appId":"102345678","clientSecret":"test-secret"}"""

        runBlocking {
            assertEquals(
                HttpStatusCode.Forbidden,
                post("/api/qq-bot/config", userToken, body).status
            )
            assertEquals(
                HttpStatusCode.OK,
                post("/api/qq-bot/config", adminToken, body).status
            )
            // 保存后 bot/status 反映已配置；WS 网关未 start → 不在线、token 未获取
            val status = get("/api/admin/bot/status", adminToken).json()
            assertEquals(true, status.boolAt("configured"))
            assertEquals(0L, status.longAt("tokenExpiresAt"))
            assertEquals(false, status.boolAt("gatewayOnline"))
            assertEquals(false, status.boolAt("gatewayStarted"))
            assertTrue(status["maskedAppId"]!!.jsonPrimitive.content.startsWith("1023"))
        }
    }

    // ── 7. push-logs：分页倒序 + 只回元数据 ──

    @Test
    fun `push-logs 倒序分页且仅元数据无文案`() {
        AdminIdentities.refresh(ADMIN_PHONE)
        val (_, adminToken) = register(ADMIN_PHONE)
        transaction {
            PushLogTable.insert { it[userId] = 1L; it[type] = "MONTHLY_SUMMARY"; it[dayKey] = "2026-08"; it[pushedAt] = 100 }
            PushLogTable.insert { it[userId] = 2L; it[type] = "ANOMALY"; it[dayKey] = "2026-09-15"; it[pushedAt] = 300 }
            PushLogTable.insert { it[userId] = 3L; it[type] = "HABIT"; it[dayKey] = "2026-09-16"; it[pushedAt] = 200 }
        }
        runBlocking {
            val page = get("/api/admin/push-logs?page=1", adminToken).json()
            assertEquals(3L, page.longAt("total"))
            val items = page["items"]!!.jsonArray
            assertEquals(3, items.size)
            // id 倒序（最新在前）
            assertEquals(
                listOf(3L, 2L, 1L),
                items.map { it.jsonObject.longAt("id") }
            )
            // 隐私红线：条目里只有 id/userId/type/dayKey/pushedAt，无文案类字段
            items.forEach { row ->
                val keys = row.jsonObject.keys
                assertTrue(
                    "push-logs 条目出现越权字段: $keys",
                    keys.all { it in setOf("id", "userId", "type", "dayKey", "pushedAt") }
                )
            }
        }
    }

    // ── 8. auth/me 的 isAdmin ──

    @Test
    fun `me 接口按名单返回 isAdmin`() {
        val (_, userToken) = register(USER_PHONE)
        AdminIdentities.refresh(ADMIN_PHONE)
        val (_, adminToken) = register(ADMIN_PHONE)
        runBlocking {
            assertEquals(false, get("/api/auth/me", userToken).json().boolAt("isAdmin"))
            assertEquals(true, get("/api/auth/me", adminToken).json().boolAt("isAdmin"))
        }
    }
}
