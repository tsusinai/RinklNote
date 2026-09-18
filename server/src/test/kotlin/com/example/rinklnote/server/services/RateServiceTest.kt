package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BotConfigTable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
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

/**
 * Task 4.2：汇率服务单测 —— USD 基准→CNY 基准换算、失败回落上次成功值、
 * 从未成功回落演示表、钉死响应契约（base/rates/updatedAt 三键）。
 * 汇率源用注入的 fetchJson 假实现，零网络。
 */
class RateServiceTest {

    private val log = LoggerFactory.getLogger("RateServiceTest")
    private var now = 1_789_689_600_000L // 2026-09-18T00:00Z（+08:00 即 08:00）

    @Before
    fun setup() {
        TestDatabase.connect("rates")
        transaction {
            SchemaUtils.create(BotConfigTable)
            BotConfigTable.deleteAll()
        }
        now = 1_789_689_600_000L
    }

    /** open.er-api.com（USD 基准）样例：rates[c] = 每 1 USD 兑 c。 */
    private val usdBasedBody = """
        {"result":"success","base_code":"USD",
         "rates":{"USD":1,"CNY":7.10,"EUR":0.92,"JPY":145.0,"GBP":0.79,"HKD":7.81,"KRW":1360.0,"CHF":0.88}}
    """.trimIndent()

    private fun service(fetch: suspend (String) -> String?) = RateService(
        fetchJson = fetch, apiUrl = "https://unit.test/api", nowMillis = { now }, log = log
    )

    // ── 换算 ──

    @Test
    fun `USD基准换算成CNY基准且白名单过滤`() {
        val rates = RateService.parseUsdBasedRates(usdBasedBody)!!
        // CNY 本位 = 1
        assertEquals(1.0, rates["CNY"]!!, 1e-9)
        // USD：7.10 / 1 = 7.10
        assertEquals(7.10, rates["USD"]!!, 1e-9)
        // EUR：7.10 / 0.92
        assertEquals(7.10 / 0.92, rates["EUR"]!!, 1e-9)
        // JPY：7.10 / 145
        assertEquals(7.10 / 145.0, rates["JPY"]!!, 1e-9)
        // 白名单外的 CHF 不透传
        assertFalse(rates.containsKey("CHF"))
        assertEquals(7, rates.size)
    }

    @Test
    fun `解析失败与缺CNY返回null`() {
        assertNull(RateService.parseUsdBasedRates("not json"))
        assertNull(RateService.parseUsdBasedRates("""{"rates":{"USD":1}}""")) // 缺 CNY
        assertNull(RateService.parseUsdBasedRates("""{"rates":{"CNY":-1,"USD":1}}"""))
    }

    // ── 回落逻辑 ──

    @Test
    fun `拉取成功落KV并可读回`() = runBlocking {
        var shouldFail = false
        val svc = service { if (shouldFail) null else usdBasedBody }
        assertTrue(svc.refresh())
        val (rates, updatedAt) = svc.snapshot()
        assertEquals(7.10, rates["USD"]!!, 1e-9)
        assertEquals(now, updatedAt)
    }

    @Test
    fun `失败回落上次成功值`() = runBlocking {
        var shouldFail = false
        val svc = service { if (shouldFail) null else usdBasedBody }
        assertTrue(svc.refresh())
        shouldFail = true
        assertFalse(svc.refresh())
        val (rates, updatedAt) = svc.snapshot()
        assertEquals("保留上次成功值", 7.10, rates["USD"]!!, 1e-9)
        assertEquals(now, updatedAt)
    }

    @Test
    fun `从未成功过回落演示表且与App同值`() = runBlocking {
        val svc = service { null }
        assertFalse(svc.refresh())
        val (rates, updatedAt) = svc.snapshot()
        assertNull(updatedAt)
        // 与 App DEMO_RATES_VS_CNY 逐值一致
        assertEquals(1.00, rates["CNY"]!!, 1e-9)
        assertEquals(7.10, rates["USD"]!!, 1e-9)
        assertEquals(7.80, rates["EUR"]!!, 1e-9)
        assertEquals(0.0480, rates["JPY"]!!, 1e-9)
        assertEquals(9.00, rates["GBP"]!!, 1e-9)
        assertEquals(0.91, rates["HKD"]!!, 1e-9)
        assertEquals(0.0052, rates["KRW"]!!, 1e-9)
    }

    @Test
    fun `跨进程回落从KV恢复`() = runBlocking {
        // 第一次服务实例成功落 KV
        val first = service { usdBasedBody }
        assertTrue(first.refresh())
        // 新实例（模拟重启）直接从 KV 恢复
        val second = service { null }
        val (rates, updatedAt) = second.snapshot()
        assertEquals(7.10, rates["USD"]!!, 1e-9)
        assertEquals(now, updatedAt)
    }

    // ── 钉死响应契约 ──

    @Test
    fun `响应契约三键base_rates_updatedAt`() = runBlocking {
        val svc = service { usdBasedBody }
        svc.refresh()
        val payload = svc.snapshotPayload()
        assertEquals(setOf("base", "rates", "updatedAt"), payload.keys)
        assertEquals("CNY", payload["base"]!!.jsonPrimitive.content)
        val usd = payload["rates"]!!.jsonObject["USD"]!!.jsonPrimitive.double
        assertEquals(7.10, usd, 1e-9)
        val iso = payload["updatedAt"]!!.jsonPrimitive.content
        assertTrue("updatedAt 应为 ISO-8601 带时区，实际 $iso", iso.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?\+08:00""")))
        // 未成功过：updatedAt 为 null（值级回落，键不缺失）。先清 KV 模拟「从未成功」
        transaction { BotConfigTable.deleteAll() }
        val cold = service { null }
        val coldPayload = cold.snapshotPayload()
        assertEquals(setOf("base", "rates", "updatedAt"), coldPayload.keys)
        assertTrue(coldPayload["updatedAt"] is kotlinx.serialization.json.JsonNull)
        // buildPayload 纯函数等价性
        val built = RateService.buildPayload(mapOf("CNY" to 1.0), null)
        assertTrue(built["updatedAt"] is kotlinx.serialization.json.JsonNull)
    }
}
