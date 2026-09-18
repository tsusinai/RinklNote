package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BotConfigTable
import io.ktor.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 汇率服务（2026-09-18 Task 4.2 多币种真汇率 · 服务端侧）。
 *
 * **响应契约（钉死，App/Web 按此对接）**：`GET /api/rates` 返回
 * `{"base":"CNY","rates":{"USD":<每 1 单位该币种兑 CNY 的数>,...},"updatedAt":"ISO-8601"}`；
 * rates 语义与 App `DEMO_RATES_VS_CNY` 完全一致（每 1 单位外币兑 CNY，CNY 自身为 1）。
 *
 * 汇率源：免 key 开放端点（默认 open.er-api.com，USD 基准），每日拉一次缓存进 bot_config KV；
 * 拉取/解析失败 → 保留上次成功值（回落）；从未成功过 → 回落内置演示表（与 App 同值）。
 * KV 里另存 source（live/cache/fallback）供运维排查，HTTP 响应体保持契约三键不变。
 */
class RateService(
    /** HTTP 拉取（注入便于单测）；返回 null = 网络失败。 */
    private val fetchJson: suspend (String) -> String?,
    private val apiUrl: String = DEFAULT_API_URL,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val log: Logger
) {

    companion object {
        const val DEFAULT_API_URL = "https://open.er-api.com/v6/latest/USD"
        const val KV_KEY = "fx_rates"

        /** 币种白名单（与 App DEMO_RATES_VS_CNY 同集合）；源端多出的币种不透传。 */
        val CURRENCIES = listOf("CNY", "USD", "EUR", "JPY", "GBP", "HKD", "KRW")

        /** 内置兜底演示表（与 App `DEMO_RATES_VS_CNY` 逐值一致，语义 = 每 1 单位该币种兑 CNY）。 */
        val FALLBACK_RATES: Map<String, Double> = mapOf(
            "CNY" to 1.00,
            "USD" to 7.10,
            "EUR" to 7.80,
            "JPY" to 0.0480,
            "GBP" to 9.00,
            "HKD" to 0.91,
            "KRW" to 0.0052
        )

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 解析 USD 基准汇率 JSON → CNY 基准白名单汇率。
         * 源 rates[c] = 每 1 USD 兑 c；目标 rates[c] = 每 1 c 兑 CNY = rates[CNY] ÷ rates[c]。
         * 缺 CNY 或解析失败返回 null。纯函数，可单测。
         */
        fun parseUsdBasedRates(body: String): Map<String, Double>? {
            return try {
                val rates = json.parseToJsonElement(body).jsonObject["rates"]?.jsonObject ?: return null
                val cnyPerUsd = rates["CNY"]?.jsonPrimitive?.double ?: return null
                if (cnyPerUsd <= 0.0) return null
                val out = linkedMapOf("CNY" to 1.0)
                for (c in CURRENCIES) {
                    if (c == "CNY") continue
                    val usdPerC = rates[c]?.jsonPrimitive?.double ?: continue
                    if (usdPerC <= 0.0) continue
                    out[c] = cnyPerUsd / usdPerC
                }
                if (out.size < 2) null else out
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 组装钉死契约的响应体：三键 base / rates / updatedAt（无值时 updatedAt 为 null）。
         * 纯函数，可单测。
         */
        fun buildPayload(rates: Map<String, Double>, updatedAtIso: String?): JsonObject = buildJsonObject {
            put("base", "CNY")
            put("rates", buildJsonObject { rates.forEach { (c, v) -> put(c, v) } })
            if (updatedAtIso != null) put("updatedAt", updatedAtIso) else put("updatedAt", null as String?)
        }
    }

    /** 内存快照（KV + 拉取结果缓存，避免每次请求读库）。 */
    @Volatile private var cached: Pair<Map<String, Double>, Long?>? = null

    /** 拉取一次并落 KV；成功返回 true。失败保留上次成功值（回落），返回 false。 */
    suspend fun refresh(): Boolean {
        val body = try {
            fetchJson(apiUrl)
        } catch (e: Exception) {
            log.warn("汇率拉取异常（保留上次成功值）: ${e.message}")
            null
        }
        if (body == null) return false
        val rates = parseUsdBasedRates(body)
        if (rates == null) {
            log.warn("汇率源响应解析失败（保留上次成功值）")
            return false
        }
        val now = nowMillis()
        store(rates, now)
        log.info("汇率更新成功：${rates.map { "${it.key}=${"%.4f".format(it.value)}" }.joinToString()}")
        return true
    }

    /** 当前快照：KV/内存有值用之；从未成功 → 兜底演示表（updatedAt = null）。 */
    fun snapshot(): Pair<Map<String, Double>, Long?> {
        cached?.let { return it }
        val stored = load()
        cached = stored
        return stored
    }

    /** 响应体（钉死契约，见 [buildPayload]）。 */
    fun snapshotPayload(): JsonObject {
        val (rates, updatedAt) = snapshot()
        return buildPayload(rates, updatedAt?.toIso8601())
    }

    /** 每日定时拉取：启动即拉一次，之后每 [intervalMs] 一次（失败不中断循环）。 */
    fun start(scope: CoroutineScope, intervalMs: Long = 24L * 60 * 60 * 1000) {
        scope.launch {
            while (isActive) {
                try {
                    if (!refresh()) log.warn("本次汇率刷新未成功，继续使用上次成功值")
                } catch (e: Exception) {
                    log.warn("汇率刷新循环异常: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    // ── KV 存取 ──

    private fun store(rates: Map<String, Double>, updatedAt: Long) {
        val value = buildJsonObject {
            put("base", "CNY")
            put("rates", buildJsonObject { rates.forEach { (c, v) -> put(c, v) } })
            put("updatedAt", updatedAt)
            put("source", "live")
        }.toString()
        transaction {
            val updated = BotConfigTable.update({ BotConfigTable.key eq KV_KEY }) {
                it[BotConfigTable.value] = value
            }
            if (updated == 0) {
                BotConfigTable.insert { it[BotConfigTable.key] = KV_KEY; it[BotConfigTable.value] = value }
            }
        }
        cached = rates to updatedAt
    }

    private fun load(): Pair<Map<String, Double>, Long?> = try {
        val row = transaction {
            BotConfigTable.selectAll().where { BotConfigTable.key eq KV_KEY }.singleOrNull()
        } ?: return FALLBACK_RATES to null
        val obj = json.parseToJsonElement(row[BotConfigTable.value]).jsonObject
        val ratesObj = obj["rates"]?.jsonObject ?: return FALLBACK_RATES to null
        val rates = CURRENCIES.mapNotNull { c ->
            ratesObj[c]?.jsonPrimitive?.double?.let { c to it }
        }.toMap()
        if (rates.isEmpty()) FALLBACK_RATES to null
        else rates to (obj["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull())
    } catch (_: Exception) {
        FALLBACK_RATES to null
    }

    private fun Long.toIso8601(): String =
        Instant.ofEpochMilli(this).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
