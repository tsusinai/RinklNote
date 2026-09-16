package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BotConfigTable
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 企业微信 Open API 的 HTTP 访问口。抽接口是为了单测注入假实现（WecomMessageProcessorTest），
 * 保证单测零真实网络请求。生产实现见 [KtorWecomHttpClient]（手写 HTTP，照 FeishuBotService
 * 的 Ktor Client 风格，零新依赖）。
 */
interface WecomHttpClient {
    /** POST JSON，返回 (HTTP 状态码, 响应体文本)。 */
    suspend fun post(url: String, jsonBody: String): WecomHttpResponse

    /** 释放底层资源；假实现默认空实现。 */
    fun close() {}
}

data class WecomHttpResponse(val status: Int, val body: String)

/** 生产 HTTP 实现：Ktor CIO 引擎 + 15s 超时（与 QQBotService / FeishuBotService 同款配置）。 */
class KtorWecomHttpClient(
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }
) : WecomHttpClient {
    override suspend fun post(url: String, jsonBody: String): WecomHttpResponse {
        val resp: HttpResponse = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        return WecomHttpResponse(resp.status.value, resp.bodyAsText())
    }

    override fun close() = client.close()
}

/**
 * 企业微信智能机器人服务（C-W1）。
 *
 * 职责：bot_config 配置读写、「消息推送」webhook 主动推送、绑定码（内存 map，5 分钟过期，
 * 照 QQBotService / FeishuBotService 模式）。企微回调的验签/加解密不在本类——统一走
 * `services/wx/WxCryptUtil`（W0 底座，订阅号复用同一套）。
 *
 * 配置存 bot_config KV 表，key（读写照 FeishuBotService 的 KV 模式；Web 管理卡片属 Phase E）：
 *  - wecom_token：回调 URL 的 Token（企微后台「智能机器人 → 接收消息」处获得）
 *  - wecom_encoding_aes_key：43 位 EncodingAESKey（回调加解密）
 *  - wecom_push_webhook_url：「消息推送」配置页获得的 webhook URL（主动推送，无需 token）
 */
class WecomBotService(
    private val http: WecomHttpClient = KtorWecomHttpClient(),
    // 时钟可注入：单测里控制「5 分钟绑定码过期」的判定
    private val nowSeconds: () -> Long = { Instant.now().epochSecond }
) {
    private val logger = LoggerFactory.getLogger(WecomBotService::class.java)

    companion object {
        private const val KEY_TOKEN = "wecom_token"
        private const val KEY_AES_KEY = "wecom_encoding_aes_key"
        private const val KEY_PUSH_WEBHOOK = "wecom_push_webhook_url"

        /** 绑定码有效期：5 分钟（照 QQ / 飞书）。 */
        private const val BIND_CODE_TTL_SECONDS = 300L
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var token: String? = null
    @Volatile private var encodingAesKey: String? = null
    @Volatile private var pushWebhookUrl: String? = null

    // 绑定码：码 → (企业内 userid, 过期时间)。与 QQ / 飞书同构：企微会话里发「登录」拿码，
    // 后续在 App/Web 管理端输码把该 userid 绑到自己的账号（消费入口随 Phase D/E 落地）。
    private data class BindEntry(val userid: String, val expiresAt: Long)
    private val bindCodes = ConcurrentHashMap<String, BindEntry>()
    private val random = SecureRandom()

    // ── 配置 ──

    /** 是否完成回调收发配置（token + EncodingAESKey 都非空才可能验签/加解密）。 */
    fun isConfigured(): Boolean = !token.isNullOrBlank() && !encodingAesKey.isNullOrBlank()

    /** 是否配置了「消息推送」webhook URL（主动推送与回调收发是两套独立配置）。 */
    fun isPushConfigured(): Boolean = !pushWebhookUrl.isNullOrBlank()

    fun configure(token: String, encodingAesKey: String, pushWebhookUrl: String = "") {
        require(token.isNotBlank() && encodingAesKey.isNotBlank()) { "企微 Token / EncodingAESKey 不能为空" }
        this.token = token
        this.encodingAesKey = encodingAesKey
        this.pushWebhookUrl = pushWebhookUrl.takeIf { it.isNotBlank() }
        logger.info("企微 Bot 已配置（Token: ${token.take(4)}...，推送 webhook ${if (this.pushWebhookUrl != null) "已配置" else "未配置"}）")
    }

    /** 从 bot_config 表读配置（启动时调用；读失败仅告警，不阻塞启动）。 */
    fun loadFromDb() {
        try {
            transaction {
                fun read(key: String): String? = BotConfigTable.selectAll()
                    .where { BotConfigTable.key eq key }
                    .singleOrNull()?.get(BotConfigTable.value)
                val t = read(KEY_TOKEN)
                val k = read(KEY_AES_KEY)
                if (!t.isNullOrBlank() && !k.isNullOrBlank()) {
                    configure(t, k, read(KEY_PUSH_WEBHOOK) ?: "")
                }
            }
        } catch (e: Exception) {
            logger.warn("从数据库加载企微 Bot 配置失败: ${e.message}")
        }
    }

    /** 三个 key 一次写库（push_webhook_url 空串表示未配置）。 */
    fun saveToDb(token: String, encodingAesKey: String, pushWebhookUrl: String = "") {
        transaction {
            listOf(
                KEY_TOKEN to token,
                KEY_AES_KEY to encodingAesKey,
                KEY_PUSH_WEBHOOK to pushWebhookUrl
            ).forEach { (k, v) ->
                val existing = BotConfigTable.selectAll().where { BotConfigTable.key eq k }.singleOrNull()
                if (existing != null) {
                    BotConfigTable.update({ BotConfigTable.key eq k }) { it[BotConfigTable.value] = v }
                } else {
                    BotConfigTable.insert {
                        it[BotConfigTable.key] = k
                        it[BotConfigTable.value] = v
                    }
                }
            }
        }
        configure(token, encodingAesKey, pushWebhookUrl)
        logger.info("企微 Bot 配置已写入数据库")
    }

    /** 管理端「是否已有保存过的配置」（看库不看内存）。 */
    fun hasSavedConfig(): Boolean = try {
        transaction {
            !BotConfigTable.selectAll()
                .where { BotConfigTable.key eq KEY_TOKEN }
                .singleOrNull()?.get(BotConfigTable.value).isNullOrBlank()
        }
    } catch (e: Exception) {
        false
    }

    fun getToken(): String? = token
    fun getEncodingAesKey(): String? = encodingAesKey
    fun getPushWebhookUrl(): String? = pushWebhookUrl

    /** 管理端掩码展示（照 FeishuBotService.getMaskedAppId 的口径：前 4 位 + ****）。 */
    fun getMaskedToken(): String? {
        val t = token ?: return null
        return if (t.length <= 4) t else t.take(4) + "****"
    }

    // ── 主动推送 ──
    // 向「消息推送」webhook URL POST JSON（官方消息推送配置，无需 token）：
    //   {"msgtype":"text","text":{"content":"..."}}
    // ⚠️ 边界（对照官方文档核对过口径）：webhook 机器人推送是 **群 / 群机器人维度**——
    // 消息发到配置该 webhook 的群会话，**不是按用户单聊**。按人单聊主动触达需要企业微信
    // 「应用消息」接口 message/send（corpid + 应用 secret 换 access_token，走应用可见范围），
    // 本期不做（调研文档 3.4 / 3.6）。企微绑定用户的日报推送统一落到群机器人会话。

    /**
     * 主动推文本到群机器人 webhook。返回是否发送成功（2xx 且 errcode==0；
     * webhook 频控 20 条/分钟，对日报级别的量完全够用）。
     */
    suspend fun pushText(content: String): Boolean {
        val url = pushWebhookUrl ?: return false
        val body = buildJsonObject {
            put("msgtype", "text")
            put("text", buildJsonObject { put("content", content) })
        }.toString()
        return try {
            val resp = http.post(url, body)
            if (resp.status !in 200..299) {
                logger.warn("企微 webhook 推送失败：HTTP ${resp.status}")
                return false
            }
            // 业务错误也用 HTTP 200 包裹（errcode!=0），「成功才落去重」要求一并视为失败
            val errcode = json.parseToJsonElement(resp.body).jsonObject["errcode"]?.jsonPrimitive?.intOrNull
            val ok = errcode == null || errcode == 0
            if (!ok) logger.warn("企微 webhook 推送失败：errcode=$errcode body=${resp.body.take(200)}")
            ok
        } catch (e: Exception) {
            logger.error("企微 webhook 推送异常", e)
            false
        }
    }

    // ── 绑定码（照 FeishuBotService 同构）──

    /** 绑定码日志掩码：只露前 3 位（如 123***），完整码不落日志（安全评审修复）。 */
    private fun maskCode(code: String) = "${code.take(3)}***"

    /** 生成 6 位绑定码（5 分钟过期），映射到发送者的企业内 userid。 */
    fun createBindCode(userid: String): String {
        val now = nowSeconds()
        // 顺手清理过期码
        bindCodes.entries.removeIf { it.value.expiresAt < now }
        val code = "%06d".format(random.nextInt(1_000_000))
        bindCodes[code] = BindEntry(userid, now + BIND_CODE_TTL_SECONDS)
        logger.info("已生成企微绑定码 ${maskCode(code)}（userid=...${userid.takeLast(6)}，5 分钟内有效）")
        return code
    }

    /** 消费绑定码：有效返回对应 userid（一次性），无效/过期返回 null。 */
    fun consumeBindCode(code: String): String? {
        val entry = bindCodes.remove(code) ?: return null
        if (nowSeconds() > entry.expiresAt) {
            logger.info("企微绑定码 ${maskCode(code)} 已过期")
            return null
        }
        logger.info("企微绑定码 ${maskCode(code)} 已消费（userid=...${entry.userid.takeLast(6)}）")
        return entry.userid
    }

    fun shutdown() {
        http.close()
    }
}
