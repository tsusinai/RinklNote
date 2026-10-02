package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BotCommands
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.CategoryDTO
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.TimeUtil
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.util.logging.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** 视觉模型从票据图片提取出的结构化字段（全部可空：模型可能给不出某项）。 */
data class ReceiptInfo(
    val merchant: String? = null,
    val amountYuan: Double? = null,
    val dateText: String? = null,
    val categoryHint: String? = null
)

/**
 * 阶段 1 · 识图入账：支付截图 / 外卖订单 / 纸质小票照片 → 视觉多模态模型提取
 * {商户, 实付金额, 交易日期, 分类建议} → 经 BillService 落库（source = QQ）→ 回执卡片。
 *
 * 配置约定（env，未配置时返回引导文案并记日志，绝不抛异常）：
 *   VISION_API_KEY / VISION_BASE_URL（默认 https://api.deepseek.com，需 OpenAI 兼容
 *   chat/completions 且模型支持图片输入）/ VISION_MODEL（如 qwen-vl-plus / glm-4v-flash）。
 *
 * 隐私红线：图片只在内存转发给视觉模型完成提取，不落盘、不进日志；日志只允许出现
 * 提取结果元数据（商户/金额/分类），绝不含图片内容、图片 URL 或完整请求/响应体。
 */
class ReceiptOcrService(
    private val billService: BillService,
    private val log: Logger
) {

    /** 识图并入账；返回回执文案。图片缺失/未配置/识别失败/金额无效时返回引导文案（非 null）。 */
    suspend fun tryBook(inc: ButlerIncoming): String? {
        val imageUrl = inc.imageUrl
        if (imageUrl.isNullOrBlank()) {
            // 纯图消息必须给回复（绝不返回 null），图片缺失按失败口径兜底
            return failReply("图片缺失")
        }
        val apiKey = System.getenv(ENV_API_KEY)?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            log.info("识图入账跳过：视觉模型未配置（VISION_API_KEY 缺失）")
            return NOT_CONFIGURED_REPLY
        }
        val baseUrl = (System.getenv(ENV_BASE_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE_URL)
            .trimEnd('/')
        val model = System.getenv(ENV_MODEL)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL
        return try {
            bookWithVision(inc.userId, imageUrl, apiKey, baseUrl, model)
        } catch (e: Exception) {
            // 只记异常类名等元数据，不带请求/响应内容与图片 URL（隐私红线）
            log.warn("识图入账失败：阶段=请求/解析，异常=${e.javaClass.simpleName}")
            failReply("识别服务开小差")
        }
    }

    fun shutdown() {
        // 幂等关闭自建 client（未用过即未创建，判空防重复关闭）
        synchronized(this) {
            client?.close()
            client = null
        }
    }

    // ── 视觉请求与落库 ─────────────────────────────────────────

    private suspend fun bookWithVision(
        userId: Long,
        imageUrl: String,
        apiKey: String,
        baseUrl: String,
        model: String
    ): String {
        val requestJson = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", imageUrl) })
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", EXTRACT_PROMPT)
                        })
                    })
                })
            })
            put("max_tokens", 500)
            put("temperature", 0.1)
        }

        val response = obtainClient().post("$baseUrl/v1/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestJson.toString())
        }
        if (response.status.value !in 200..299) {
            // 非 2xx 走失败分支；日志只记状态码，绝不记响应体
            log.warn("识图入账失败：视觉服务返回 HTTP ${response.status.value}")
            return failReply("识别服务开小差")
        }
        val raw = response.bodyAsText()
        val content = extractMessageContent(raw)
            ?: run {
                log.warn("识图入账失败：响应缺少 choices[0].message.content")
                return failReply("识别结果读不出来")
            }
        val info = parseReceiptJson(content)
            ?: run {
                log.warn("识图入账失败：模型输出不是合法的票据 JSON")
                return failReply("识别结果读不出来")
            }
        // 金额校验：缺金额 / 超过 1e7 元视为无效，同一引导文案
        val amountYuan = info.amountYuan
        if (amountYuan == null || amountYuan > MAX_AMOUNT_YUAN) {
            return NO_AMOUNT_REPLY
        }
        val amountMinor = Money.toMinor(amountYuan)
        if (amountMinor <= 0) {
            return NO_AMOUNT_REPLY
        }

        val categories = billService.getCategories()
        val categoryName = resolveCategoryName(info.categoryHint, categories)
        val remark = info.merchant?.trim()?.takeIf { it.isNotEmpty() }
        val bookedDate = parseDateText(info.dateText)

        val bill = try {
            billService.createBill(
                userId = userId,
                amountMinor = amountMinor,
                categoryName = categoryName,
                remark = remark,
                source = BotCommands.SOURCE_QQ,
                date = bookedDate?.atStartOfDay(TimeUtil.BOOKKEEPING_ZONE)?.toInstant()?.toEpochMilli()
            )
        } catch (e: Exception) {
            log.warn("识图入账失败：阶段=落库，异常=${e.javaClass.simpleName}")
            return failReply("记这笔账时出了岔子")
        }

        // 回执只含元数据（分类/金额/商户/日期），符合隐私红线
        log.info(
            "识图入账成功：userId=$userId 分类=${bill.categoryName} 金额分=$amountMinor " +
                "商户=${remark ?: "无"}"
        )
        return buildString {
            append("🧾 已记录：").append(bill.categoryName).append(" ¥").append(Money.format(bill.amountMinor))
            if (remark != null) {
                append("\n商家：").append(remark)
            }
            if (bookedDate != null) {
                append("\n日期：").append(bookedDate.monthValue).append("月").append(bookedDate.dayOfMonth).append("日")
            }
            append("\n金额或分类不对？回「改金额xx」或「改分类 xx」就能改")
        }
    }

    // ── 纯函数解析（可单测） ───────────────────────────────────

    /**
     * 解析视觉模型输出的票据 JSON：剥 ```json 代码围栏 → 取首个 `{` 到末个 `}` 子串 →
     * kotlinx.serialization 解析（字段全可空、忽略未知键）。amount ≤ 0 视为 null。
     * 任何不合法输入返回 null，绝不抛。
     */
    fun parseReceiptJson(raw: String): ReceiptInfo? {
        return try {
            val body = extractJsonBody(raw) ?: return null
            val obj = lenientJson.decodeFromString<JsonObject>(body)
            ReceiptInfo(
                merchant = obj.optString("merchant")?.trim()?.takeIf { it.isNotEmpty() },
                amountYuan = obj.optDouble("amount")?.takeIf { it > 0 && !it.isNaN() && !it.isInfinite() },
                dateText = obj.optString("date")?.trim()?.takeIf { it.isNotEmpty() },
                categoryHint = obj.optString("categoryHint")?.trim()?.takeIf { it.isNotEmpty() }
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 分类解析（纯函数，可单测）：先 hint 与分类名精确相等；再「hint 包含分类名」或
     * 「分类名包含 hint」的唯一包含命中；多义 / 无命中 / hint 为空 → null 落默认分类。
     */
    fun resolveCategoryName(hint: String?, categories: List<CategoryDTO>): String? {
        val trimmed = hint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (categories.any { it.name == trimmed }) return trimmed
        val containsHits = categories.filter { it.name.contains(trimmed) || trimmed.contains(it.name) }
        return containsHits.singleOrNull()?.name
    }

    // ── 私有工具 ───────────────────────────────────────────────

    /** 从模型响应里取 choices[0].message.content；结构不符返回 null。 */
    private fun extractMessageContent(raw: String): String? = try {
        val root = lenientJson.parseToJsonElement(raw).jsonObject
        root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    /** 剥代码围栏后取首个 `{` 到末个 `}` 的子串；找不到有效区间返回 null。 */
    private fun extractJsonBody(raw: String): String? {
        val stripped = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return stripped.substring(start, end + 1)
    }

    /** 从 JsonObject 取字符串字段：缺失 / 非字符串标量（含 null）返回 null。 */
    private fun JsonObject.optString(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content
    }

    /** 从 JsonObject 取数字字段：缺失 / 非数字标量返回 null（数字字符串也接受）。 */
    private fun JsonObject.optDouble(key: String): Double? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content.toDoubleOrNull()
    }

    /** 解析 yyyy-MM-dd 日期文本；失败返回 null（落库时 null = 今天）。 */
    private fun parseDateText(text: String?): LocalDate? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(trimmed)
        } catch (_: DateTimeParseException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 懒创建的自建 client（HttpTimeout 60s）；未配置 key 时不会创建。 */
    @Volatile
    private var client: HttpClient? = null

    private fun obtainClient(): HttpClient =
        client ?: synchronized(this) {
            client ?: HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }.also { client = it }
        }

    private fun failReply(reason: String): String = "这张图小盘没看懂（$reason），直接打字告诉我吧～"

    private companion object {
        const val ENV_API_KEY = "VISION_API_KEY"
        const val ENV_BASE_URL = "VISION_BASE_URL"
        const val ENV_MODEL = "VISION_MODEL"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "qwen-vl-plus"
        const val REQUEST_TIMEOUT_MS = 60_000L
        /** 金额上限（元）：超过视为模型幻觉，按「没看出金额」引导。 */
        const val MAX_AMOUNT_YUAN = 1e7

        const val NOT_CONFIGURED_REPLY =
            "小盘还没学会看图记账（视觉模型未配置），先打字告诉我金额吧～"
        const val NO_AMOUNT_REPLY =
            "这张图没看出金额诶…换个清晰的截图，或者直接打字告诉我金额～"

        /** 视觉提取指令：严格只输出 JSON，禁止其它内容（解析侧仍做围栏剥离兜底）。 */
        val EXTRACT_PROMPT = """
            这是用户本人发来的支付截图或购物小票，请从图中提取记账信息，
            严格只输出一个 JSON 对象，格式为：
            {"merchant": 商户名称字符串或null, "amount": 实付金额数字（单位元）或null, "date": 交易日期，格式 yyyy-MM-dd 或null, "categoryHint": 记账分类猜测字符串或null}
            不要输出任何解释、前后缀或代码块标记，只输出 JSON 本身。
        """.trimIndent()
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
}
