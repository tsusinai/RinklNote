package com.example.rinklnote.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.CategoriesTable
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
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 条件 PUT 乐观锁（2026-09-18 全端优化 Task 0.1）HTTP 级用例：
 * 1. 带过期 baseUpdatedAt 的 PUT → 409 + 服务端当前最新 DTO，且账单数据未被改动；
 * 2. 带匹配 baseUpdatedAt 的 PUT → 200 且 updatedAt 前进、字段生效；
 * 3. 带条件但账单不存在 / 属他人 → 404；
 * 4. 不带 baseUpdatedAt 的无条件 PUT 保持可用（旧客户端回归）。
 *
 * 测试基座照 AuthApiTest：embeddedServer(Netty, port = 0) 挂真实路由 + ktor-client-cio 发真请求，
 * 数据库整类共用一个 H2，每个用例重置账单行。
 */
class BillOptimisticLockTest {

    companion object {
        private const val JWT_SECRET = "unit-test-bill-jwt-secret-0123456789"
        private const val ISSUER = "test-issuer"
        private const val AUDIENCE = "test-audience"
        private const val USER_ID = 42L
        private const val OTHER_USER_ID = 43L
        private const val BASE_UPDATED_AT = 1_000_000L

        private lateinit var server: ApplicationEngine
        private var port: Int = 0
        private lateinit var client: HttpClient

        @BeforeClass
        @JvmStatic
        fun startServer() {
            TestDatabase.connect("billoptimistic")
            transaction {
                SchemaUtils.create(UsersTable, CategoriesTable, AccountsTable, BillsTable)
                UsersTable.insert {
                    it[id] = USER_ID
                    it[phone] = "13800000042"
                    it[createdAt] = "2026-09-18"
                }
                UsersTable.insert {
                    it[id] = OTHER_USER_ID
                    it[phone] = "13800000043"
                    it[createdAt] = "2026-09-18"
                }
                CategoriesTable.insert {
                    it[id] = 1L
                    it[name] = "三餐"
                    it[iconName] = "meals"
                    it[billType] = "EXPENSE"
                }
                AccountsTable.insert {
                    it[id] = 1L
                    it[userId] = USER_ID
                    it[name] = "无账户"
                    it[iconColor] = "#F97D1D"
                    it[updatedAt] = 0L
                }
            }

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
                routing { billRoutes(BillService()) }
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

        private fun token(): String = JWT.create()
            .withAudience(AUDIENCE)
            .withIssuer(ISSUER)
            .withClaim("userId", USER_ID)
            .sign(Algorithm.HMAC256(JWT_SECRET))

        private fun putBody(baseUpdatedAt: Long?): String = buildString {
            append(
                """{"amountMinor":1500,"billType":"EXPENSE","categoryId":1,""" +
                    """"categoryName":"三餐","accountId":1,"remark":"更新后备注""""
            )
            if (baseUpdatedAt != null) append(""","baseUpdatedAt":$baseUpdatedAt""")
            append("}")
        }

        private suspend fun put(id: Long, baseUpdatedAt: Long?): HttpResponse =
            client.put(baseUrl("/api/bills/$id")) {
                bearerAuth(token())
                contentType(ContentType.Application.Json)
                setBody(putBody(baseUpdatedAt))
            }
    }

    @Before
    fun resetBills() {
        transaction {
            BillsTable.deleteAll()
            BillsTable.insert {
                it[id] = 1L
                it[userId] = USER_ID
                it[amount] = 10.0
                it[amountMinor] = 1000L
                it[billType] = "EXPENSE"
                it[categoryId] = 1L
                it[categoryName] = "三餐"
                it[accountId] = 1L
                it[remark] = "初始备注"
                it[date] = BASE_UPDATED_AT
                it[billSource] = "WEB"
                it[createdAt] = BASE_UPDATED_AT - 100
                it[updatedAt] = BASE_UPDATED_AT
            }
            // 他人账单：属主隔离用例
            BillsTable.insert {
                it[id] = 2L
                it[userId] = OTHER_USER_ID
                it[amount] = 10.0
                it[amountMinor] = 1000L
                it[billType] = "EXPENSE"
                it[categoryId] = 1L
                it[categoryName] = "三餐"
                it[accountId] = 1L
                it[remark] = "他人备注"
                it[date] = BASE_UPDATED_AT
                it[billSource] = "WEB"
                it[createdAt] = BASE_UPDATED_AT - 100
                it[updatedAt] = BASE_UPDATED_AT
            }
        }
    }

    @Test
    fun `stale baseUpdatedAt is rejected with 409 and bill data unchanged`() = runBlocking {
        val resp = put(1L, BASE_UPDATED_AT - 1)
        assertEquals(HttpStatusCode.Conflict, resp.status)
        // 409 响应体携带服务端当前最新 DTO，客户端据此重取 base 重放
        val conflict = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("初始备注", conflict["remark"]!!.jsonPrimitive.content)

        // 数据确实未被改动
        val get = client.get(baseUrl("/api/bills/1")) { bearerAuth(token()) }
        assertEquals(HttpStatusCode.OK, get.status)
        val bill = Json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals(1000L, bill["amountMinor"]!!.jsonPrimitive.long)
        assertEquals("初始备注", bill["remark"]!!.jsonPrimitive.content)
        assertEquals(BASE_UPDATED_AT, bill["updatedAt"]!!.jsonPrimitive.long)
    }

    @Test
    fun `matching baseUpdatedAt updates and advances updatedAt`() = runBlocking {
        val resp = put(1L, BASE_UPDATED_AT)
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertTrue(body["updatedAt"]!!.jsonPrimitive.long > BASE_UPDATED_AT)

        val get = client.get(baseUrl("/api/bills/1")) { bearerAuth(token()) }
        val bill = Json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals(1500L, bill["amountMinor"]!!.jsonPrimitive.long)
        assertEquals("更新后备注", bill["remark"]!!.jsonPrimitive.content)
    }

    @Test
    fun `conditional put on missing bill returns 404`() = runBlocking {
        val resp = put(9999L, 1L)
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `conditional put on another users bill returns 404 and does not touch it`() = runBlocking {
        val resp = put(2L, BASE_UPDATED_AT)
        assertEquals(HttpStatusCode.NotFound, resp.status)
        val row = transaction { BillsTable.selectAll().where { BillsTable.id eq 2L }.single() }
        assertEquals(1000L, row[BillsTable.amountMinor])
        assertEquals("他人备注", row[BillsTable.remark])
    }

    @Test
    fun `unconditional put without baseUpdatedAt still updates`() = runBlocking {
        val resp = put(1L, null)
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `put with oversized legacy amount returns 400 not 500`() = runBlocking {
        // amount=1e20 元（旧字段回退路径）→ Money.toMinor 溢出 ArithmeticException，
        // 不属于 IAE 家族，PUT 必须自己兜住回 400，否则穿透成 500 + 误发管理员告警
        val resp = client.put(baseUrl("/api/bills/1")) {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(
                """{"amount":1e20,"billType":"EXPENSE","categoryId":1,""" +
                    """"categoryName":"三餐","accountId":1}"""
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        // 账单数据未被改动
        val row = transaction { BillsTable.selectAll().where { BillsTable.id eq 1L }.single() }
        assertEquals(1000L, row[BillsTable.amountMinor])
    }
}
