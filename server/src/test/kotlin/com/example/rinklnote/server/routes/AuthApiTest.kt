package com.example.rinklnote.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.rinklnote.server.services.QQBotService
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.tables.UsersTable
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 认证路由（2026-09-17 优化登录方式）HTTP 级用例：邮箱注册 / 登录 / 唯一冲突、
 * 密码大小写规则（注册与改密码）、旧客户端（只发 phone）零破坏回归。
 *
 * 测试基座照 ProfileApiTest：不引入 ktor-server-test-host（零新依赖约束），直接
 * embeddedServer(Netty, port = 0) 挂真实路由 + ktor-client-cio 发真请求；数据库整类共用一个
 * H2（TestDatabase.connect 只在 @BeforeClass 调一次），每个用例清表。
 */
class AuthApiTest {

    companion object {
        private const val JWT_SECRET = "unit-test-auth-jwt-secret-0123456789"
        private const val ISSUER = "test-issuer"
        private const val AUDIENCE = "test-audience"
        private const val PHONE = "13800000301"
        private const val EMAIL = "user@example.com"
        private const val PASSWORD = "Good123"

        private lateinit var server: ApplicationEngine
        private var port: Int = 0
        private lateinit var client: HttpClient

        @BeforeClass
        @JvmStatic
        fun startServer() {
            TestDatabase.connect("authapi")
            transaction { SchemaUtils.create(UsersTable) }

            val userService = UserService(JWT_SECRET, ISSUER, AUDIENCE)
            val qqBotService = QQBotService() // 只构造不 start：authRoutes 仅用到它的绑定码消费

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
                routing { authRoutes(userService, qqBotService) }
            }.start(wait = false)
            port = runBlocking { server.resolvedConnectors().first().port }
            client = HttpClient(CIO)
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            client.close()
            server.stop(100, 1000)
        }

        private fun baseUrl(path: String) = "http://127.0.0.1:$port$path"

        /** 旧客户端形态的注册：只发 phone + password（无 email 键）。 */
        private suspend fun registerByPhone(phone: String, password: String): HttpResponse =
            client.post(baseUrl("/api/auth/register")) {
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$phone","password":"$password"}""")
            }

        private suspend fun loginBody(body: String): HttpResponse =
            client.post(baseUrl("/api/auth/login")) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
    }

    @Before
    fun cleanTable() {
        transaction { UsersTable.deleteAll() }
    }

    // ── 邮箱注册 / 登录 ──

    @Test
    fun `register by email returns 201 with token and me carries email`() = runBlocking {
        val resp = client.post(baseUrl("/api/auth/register")) {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$EMAIL","password":"$PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val token = obj["token"]!!.jsonPrimitive.content

        // /api/auth/me 回显 email（phone 为空）
        val me = client.get(baseUrl("/api/auth/me")) { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, me.status)
        val meObj = Json.parseToJsonElement(me.bodyAsText()).jsonObject
        assertEquals(EMAIL, meObj["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `duplicate email registration is rejected with 409`() = runBlocking {
        val first = client.post(baseUrl("/api/auth/register")) {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$EMAIL","password":"$PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.Created, first.status)

        val second = client.post(baseUrl("/api/auth/register")) {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$EMAIL","password":"$PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertTrue(Json.parseToJsonElement(second.bodyAsText()).jsonObject["message"]!!.jsonPrimitive.content.contains("该邮箱已注册"))
    }

    @Test
    fun `email login works and wrong password is 401 with email message`() = runBlocking {
        client.post(baseUrl("/api/auth/register")) {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$EMAIL","password":"$PASSWORD"}""")
        }

        val ok = loginBody("""{"email":"$EMAIL","password":"$PASSWORD"}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(Json.parseToJsonElement(ok.bodyAsText()).jsonObject.containsKey("token"))

        val bad = loginBody("""{"email":"$EMAIL","password":"Wrong999"}""")
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertTrue(Json.parseToJsonElement(bad.bodyAsText()).jsonObject["message"]!!.jsonPrimitive.content.contains("邮箱"))
    }

    @Test
    fun `email is case insensitive for register conflict and login`() = runBlocking {
        client.post(baseUrl("/api/auth/register")) {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"USER@Example.COM","password":"$PASSWORD"}""")
        }
        // 大小写不同也判重
        val dup = client.post(baseUrl("/api/auth/register")) {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"user@example.com","password":"$PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)

        // 小写形式可登录
        val ok = loginBody("""{"email":"user@example.com","password":"$PASSWORD"}""")
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `malformed email is rejected with 400`() = runBlocking {
        val resp = client.post(baseUrl("/api/auth/register")) {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"not-an-email","password":"$PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ── 密码大小写规则（只约束新设定）──

    @Test
    fun `register rejects password without upper case`() = runBlocking {
        val resp = client.post(baseUrl("/api/auth/register")) {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$PHONE","password":"alllower123"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(Json.parseToJsonElement(resp.bodyAsText()).jsonObject["message"]!!.jsonPrimitive.content.contains("大小写"))
    }

    @Test
    fun `register rejects password without lower case`() = runBlocking {
        val resp = client.post(baseUrl("/api/auth/register")) {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$PHONE","password":"ALLUPPER123"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `change password enforces the same rule but keeps old password working`() = runBlocking {
        val resp = registerByPhone(PHONE, PASSWORD)
        assertEquals(HttpStatusCode.Created, resp.status)
        val token = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

        // 新密码无大写 → 400，且旧密码仍可登录（规则只约束新设定）
        val weak = client.post(baseUrl("/api/auth/password")) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"oldPassword":"$PASSWORD","newPassword":"weak123"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, weak.status)
        val stillOld = loginBody("""{"phone":"$PHONE","password":"$PASSWORD"}""")
        assertEquals(HttpStatusCode.OK, stillOld.status)

        // 合法新密码 → 成功，新密码可登录
        val ok = client.post(baseUrl("/api/auth/password")) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"oldPassword":"$PASSWORD","newPassword":"NewPass9"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(HttpStatusCode.OK, loginBody("""{"phone":"$PHONE","password":"NewPass9"}""").status)
    }

    // ── 旧客户端零破坏（只发 phone，无 email 键）──

    @Test
    fun `legacy phone register and login keep working`() = runBlocking {
        val resp = registerByPhone(PHONE, PASSWORD)
        assertEquals(HttpStatusCode.Created, resp.status)

        val login = loginBody("""{"phone":"$PHONE","password":"$PASSWORD"}""")
        assertEquals(HttpStatusCode.OK, login.status)

        // 旧客户端错误密码文案保持「手机号或密码错误」
        val bad = loginBody("""{"phone":"$PHONE","password":"Wrong999"}""")
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertTrue(Json.parseToJsonElement(bad.bodyAsText()).jsonObject["message"]!!.jsonPrimitive.content.contains("手机号"))
    }
}
