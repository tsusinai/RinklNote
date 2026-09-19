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
import kotlinx.serialization.json.jsonArray
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * 账单搜索 API（2026-09-18 全端优化 Task 0.6）HTTP 级用例：
 * GET /api/bills/search?q=&min=&max=&categoryId=&from=&to=&page=&pageSize=
 * 覆盖：关键词模糊（备注/分类、不区分大小写）、金额区间（元入参→分比较）、
 * 分类过滤、业务时区日期区间（含首尾两天）、分页与聚合、用户隔离、软删过滤。
 */
class BillSearchApiTest {

    companion object {
        private const val JWT_SECRET = "unit-test-bill-jwt-secret-0123456789"
        private const val ISSUER = "test-issuer"
        private const val AUDIENCE = "test-audience"
        private const val USER_ID = 42L
        private const val OTHER_USER_ID = 43L

        private lateinit var server: ApplicationEngine
        private var port: Int = 0
        private lateinit var client: HttpClient

        private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 业务时区某天 0 点的 epoch 毫秒（种子数据用它对齐日期边界）。 */
        private fun day(n: Int): Long =
            LocalDate.of(2026, 9, n).atStartOfDay(shanghai).toInstant().toEpochMilli()

        @BeforeClass
        @JvmStatic
        fun startServer() {
            TestDatabase.connect("billsearch")
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
                CategoriesTable.insert {
                    it[id] = 2L
                    it[name] = "交通"
                    it[iconName] = "transport"
                    it[billType] = "EXPENSE"
                }
                CategoriesTable.insert {
                    it[id] = 3L
                    it[name] = "工资"
                    it[iconName] = "salary"
                    it[billType] = "INCOME"
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

        private fun token(userId: Long = USER_ID): String = JWT.create()
            .withAudience(AUDIENCE)
            .withIssuer(ISSUER)
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(JWT_SECRET))

        private fun seedBill(
            id: Long,
            userId: Long,
            amountMinor: Long,
            categoryId: Long,
            categoryName: String,
            billType: String,
            remark: String?,
            date: Long,
            deleted: Boolean = false
        ) {
            transaction {
                BillsTable.insert {
                    it[BillsTable.id] = id
                    it[BillsTable.userId] = userId
                    it[amount] = amountMinor / 100.0
                    it[BillsTable.amountMinor] = amountMinor
                    it[BillsTable.billType] = billType
                    it[BillsTable.categoryId] = categoryId
                    it[BillsTable.categoryName] = categoryName
                    it[BillsTable.accountId] = 1L
                    it[BillsTable.remark] = remark
                    it[BillsTable.date] = date
                    it[billSource] = "WEB"
                    it[createdAt] = date
                    it[updatedAt] = date
                    it[BillsTable.deleted] = deleted
                }
            }
        }
    }

    @Before
    fun resetBills() {
        transaction { BillsTable.deleteAll() }
        // 6 笔种子：覆盖关键词/金额区间/分类/日期/软删/他人账单维度
        seedBill(1L, USER_ID, 2_000, 1L, "三餐", "EXPENSE", "早餐豆浆", day(1))
        seedBill(2L, USER_ID, 12_000, 1L, "三餐", "EXPENSE", "午餐外卖", day(2))
        seedBill(3L, USER_ID, 8_800, 2L, "交通", "EXPENSE", "地铁充值", day(3))
        seedBill(4L, USER_ID, 500_000, 3L, "工资", "INCOME", "九月工资", day(4))
        seedBill(5L, USER_ID, 3_300, 2L, "交通", "EXPENSE", "打车", day(5), deleted = true)
        seedBill(6L, OTHER_USER_ID, 99_900, 1L, "三餐", "EXPENSE", "他人的早餐豆浆", day(2))
    }

    @Test
    fun `创建账单超大金额返回400而不是500`() = runBlocking {
        // amount=1e20 元（旧字段回退路径）：resolveAmountMinor → Money.toMinor 溢出
        // ArithmeticException，不属于 IAE 家族，路由必须自己兜住回 400
        val resp = client.post(baseUrl("/api/bills")) {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody("""{"amount":1e20,"billType":"EXPENSE","categoryId":1,"categoryName":"三餐","accountId":1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        // 库里不得真的落账
        assertEquals(6L, transaction { BillsTable.selectAll().count() })
    }

    private suspend fun search(query: String, userId: Long = USER_ID): JsonObjectPair {
        val resp = client.get(baseUrl("/api/bills/search$query")) { bearerAuth(token(userId)) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return JsonObjectPair(obj)
    }

    private class JsonObjectPair(val obj: kotlinx.serialization.json.JsonObject) {
        val ids: List<Long> get() = obj["bills"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }
        val total: Long get() = obj["total"]!!.jsonPrimitive.long
        val sumExpense: Long get() = obj["sumExpenseMinor"]!!.jsonPrimitive.long
        val sumIncome: Long get() = obj["sumIncomeMinor"]!!.jsonPrimitive.long
        val totalPages: Int get() = obj["totalPages"]!!.jsonPrimitive.content.toInt()
    }

    @Test
    fun `q matches remark and category case insensitively`() = runBlocking {
        // 备注命中（子串）
        assertEquals(listOf(2L), search("?q=%E5%8D%88%E9%A4%90").ids) // 午餐 → 备注「午餐外卖」
        // 备注命中另一条
        assertEquals(listOf(3L), search("?q=%E5%9C%B0%E9%93%81").ids) // 地铁 → 备注「地铁充值」
        // 他人账单同名备注不出现（用户隔离）
        val mine = search("?q=%E8%B1%86%E6%B5%86").ids // 豆浆
        assertEquals(listOf(1L), mine)
    }

    @Test
    fun `min max 超大或非法数字返回400而不是500`() = runBlocking {
        // max=1e20 元：Money.toMinor 的 longValueExact 溢出抛 ArithmeticException，
        // 不在路由层兜住会穿透成 500（生产上还会经 AlertNotifier 误发管理员告警）
        val huge = client.get(baseUrl("/api/bills/search?max=99999999999999999999")) { bearerAuth(token()) }
        assertEquals(HttpStatusCode.BadRequest, huge.status)
        // min=NaN：BigDecimal.valueOf(NaN) 抛 NumberFormatException，同样必须 400
        val nan = client.get(baseUrl("/api/bills/search?min=NaN")) { bearerAuth(token()) }
        assertEquals(HttpStatusCode.BadRequest, nan.status)
    }

    @Test
    fun `min max are yuan inputs compared as minor`() = runBlocking {
        // ≥100 元 → 4（5000 元）与 2（120 元）；日期倒序 [4, 2]
        assertEquals(listOf(4L, 2L), search("?min=100").ids)
        // 10~50 元 → 1（20 元）；3 是 88 元不在区间
        assertEquals(listOf(1L), search("?min=10&max=50").ids)
        // 区间含边界：120 元正好等于午餐外卖
        assertEquals(listOf(2L), search("?min=120&max=120").ids)
    }

    @Test
    fun `categoryId filters precisely`() = runBlocking {
        val ids = search("?categoryId=2").ids // 交通：3（5 已软删）
        assertEquals(listOf(3L), ids)
    }

    @Test
    fun `from to day range is inclusive in business timezone`() = runBlocking {
        // 9月2日当天 → 只有账单 2（6 是他人账单）
        assertEquals(listOf(2L), search("?from=2026-09-02&to=2026-09-02").ids)
        // 9月1日~9月3日 → 1、2、3
        assertEquals(listOf(3L, 2L, 1L), search("?from=2026-09-01&to=2026-09-03").ids)
        // 非法日期 → 400
        val bad = client.get(baseUrl("/api/bills/search?from=not-a-date")) { bearerAuth(token()) }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun `pagination and aggregates cover whole filtered set`() = runBlocking {
        // 无过滤：全部未删且属于我的 = 1、2、3、4（5 软删、6 他人）
        val p1 = search("?pageSize=2&page=1")
        assertEquals(4L, p1.total)
        assertEquals(2, p1.totalPages)
        assertEquals(2, p1.ids.size)
        val p2 = search("?pageSize=2&page=2")
        assertEquals(2, p2.ids.size)
        assertEquals(p1.ids.size + p2.ids.size, 4)

        // 聚合跨页覆盖全集：支出 20 + 120 + 88 = 228 元 = 22800 分；收入 5000 元 = 500000 分
        assertEquals(22_800L, p1.sumExpense)
        assertEquals(500_000L, p1.sumIncome)

        // 组合：q=餐 且 min=100 元 → 只有 2（120 元）同时满足两个条件
        val combo = search("?q=%E9%A4%90&min=100") // 「餐」：1 早餐豆浆、2 午餐外卖、6 他人
        assertEquals(listOf(2L), combo.ids)
    }

    @Test
    fun `soft deleted and other users bills are excluded`() = runBlocking {
        val all = search("")
        assertEquals(listOf(4L, 3L, 2L, 1L), all.ids) // 日期倒序，软删 5 与他人 6 不出现

        // 他人视角查自己的：只有 6
        val other = search("", OTHER_USER_ID)
        assertEquals(listOf(6L), other.ids)
    }

    @Test
    fun `search requires auth`() = runBlocking {
        val resp = client.get(baseUrl("/api/bills/search"))
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
