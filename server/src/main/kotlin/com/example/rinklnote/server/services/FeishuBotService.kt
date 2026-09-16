package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BotConfigTable
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 飞书 Open API 的 HTTP 访问口。抽接口是为了单测注入假实现（FeishuBotServiceTest /
 * FeishuMessageProcessorTest），保证单测零真实网络请求。
 * 生产实现见 [KtorFeishuHttpClient]（手写 HTTP，照 QQBotService 的 Ktor Client 风格，零新依赖）。
 */
interface FeishuHttpClient {
    /** POST JSON，返回 (HTTP 状态码, 响应体文本)。 */
    suspend fun post(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): FeishuHttpResponse

    /** 释放底层资源；假实现默认空实现。 */
    fun close() {}
}

data class FeishuHttpResponse(val status: Int, val body: String)

/** 生产 HTTP 实现：Ktor CIO 引擎 + 15s 超时（与 QQBotService 同款配置）。 */
class KtorFeishuHttpClient(
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }
) : FeishuHttpClient {
    override suspend fun post(url: String, jsonBody: String, headers: Map<String, String>): FeishuHttpResponse {
        val resp: HttpResponse = client.post(url) {
            contentType(ContentType.Application.Json)
            headers.forEach { (k, v) ->
                // Content-Type 已由 contentType() 设置，避免重复头
                if (!k.equals("Content-Type", ignoreCase = true)) header(k, v)
            }
            setBody(jsonBody)
        }
        return FeishuHttpResponse(resp.status.value, resp.bodyAsText())
    }

    override fun close() = client.close()
}

@Serializable
data class FeishuTokenResponse(
    val code: Int = -1,
    val msg: String? = null,
    val tenant_access_token: String = "",
    // 有效期（秒），自建应用固定 2 小时（7200）
    val expire: Long = 0
)

/**
 * 飞书自建应用机器人服务（B2 阶段）。
 *
 * 职责：bot_config 配置读写、tenant_access_token 缓存（Mutex 单飞 + 过期前刷新）、
 * 发文本消息（主动发 / 按消息 id 回复）、绑定码（内存 map，5 分钟过期，照 QQBotService 模式）。
 *
 * 配置存 bot_config KV 表，key：
 *  - feishu_app_id / feishu_app_secret：换 token 的凭证（必填）
 *  - feishu_encrypt_key：事件加密密钥（可空；配了才做 X-Lark-Signature 验签 + AES 解密）
 *  - feishu_verification_token：旧版轻校验 token（可空；未配 Encrypt Key 时的校验手段）
 */
class FeishuBotService(
    private val http: FeishuHttpClient = KtorFeishuHttpClient(),
    // 时钟可注入：单测里控制「5 分钟绑定码过期」「token 过期前刷新」的判定
    private val nowSeconds: () -> Long = { Instant.now().epochSecond }
) {
    private val logger = LoggerFactory.getLogger(FeishuBotService::class.java)

    companion object {
        /** 飞书开放平台 API 域名。 */
        const val BASE_URL = "https://open.feishu.cn"

        private const val KEY_APP_ID = "feishu_app_id"
        private const val KEY_APP_SECRET = "feishu_app_secret"
        private const val KEY_ENCRYPT_KEY = "feishu_encrypt_key"
        private const val KEY_VERIFICATION_TOKEN = "feishu_verification_token"

        /** token 过期前提前刷新的秒数（照 QQBotService 的 300s）。 */
        private const val TOKEN_REFRESH_AHEAD_SECONDS = 300L

        /** 绑定码有效期：5 分钟（照 QQ）。 */
        private const val BIND_CODE_TTL_SECONDS = 300L
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var appId: String? = null
    @Volatile private var appSecret: String? = null
    @Volatile private var encryptKey: String? = null
    @Volatile private var verificationToken: String? = null

    // tenant_access_token 缓存：2h 有效期，提前 TOKEN_REFRESH_AHEAD_SECONDS 失效重取
    @Volatile private var tenantToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0
    private val tokenMutex = Mutex()

    // 绑定码：码 → (open_id, 过期时间)。与 QQ 同构：bot 会话里发「登录」拿码，
    // 用户在 Web 管理端输码，把该 open_id 绑到自己的账号。
    private data class BindEntry(val openId: String, val expiresAt: Long)
    private val bindCodes = ConcurrentHashMap<String, BindEntry>()
    private val random = SecureRandom()

    // ── 配置 ──

    fun isConfigured(): Boolean =
        !appId.isNullOrBlank() && !appSecret.isNullOrBlank()

    fun configure(appId: String, appSecret: String, encryptKey: String? = null, verificationToken: String? = null) {
        require(appId.isNotBlank() && appSecret.isNotBlank()) { "飞书 AppID / AppSecret 不能为空" }
        this.appId = appId
        this.appSecret = appSecret
        this.encryptKey = encryptKey?.takeIf { it.isNotBlank() }
        this.verificationToken = verificationToken?.takeIf { it.isNotBlank() }
        // 凭证变更后旧 token 作废
        tenantToken = null
        tokenExpiresAt = 0
        logger.info("飞书 Bot 已配置（AppID: ${appId.take(4)}...，EncryptKey ${if (this.encryptKey != null) "已配置" else "未配置"}）")
    }

    /** 从 bot_config 表读配置（启动时调用；读失败仅告警，不阻塞启动）。 */
    fun loadFromDb() {
        try {
            transaction {
                fun read(key: String): String? = BotConfigTable.selectAll()
                    .where { BotConfigTable.key eq key }
                    .singleOrNull()?.get(BotConfigTable.value)
                val aid = read(KEY_APP_ID)
                val secret = read(KEY_APP_SECRET)
                if (!aid.isNullOrBlank() && !secret.isNullOrBlank()) {
                    configure(aid, secret, read(KEY_ENCRYPT_KEY), read(KEY_VERIFICATION_TOKEN))
                }
            }
        } catch (e: Exception) {
            logger.warn("从数据库加载飞书 Bot 配置失败: ${e.message}")
        }
    }

    /** 四个 key 一次写库（encrypt_key / verification_token 空串表示未配置）。 */
    fun saveToDb(appId: String, appSecret: String, encryptKey: String = "", verificationToken: String = "") {
        transaction {
            listOf(
                KEY_APP_ID to appId,
                KEY_APP_SECRET to appSecret,
                KEY_ENCRYPT_KEY to encryptKey,
                KEY_VERIFICATION_TOKEN to verificationToken
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
        configure(appId, appSecret, encryptKey, verificationToken)
        logger.info("飞书 Bot 配置已写入数据库")
    }

    /** 管理端「是否已有保存过的配置」（看库不看内存）。 */
    fun hasSavedConfig(): Boolean = try {
        transaction {
            !BotConfigTable.selectAll()
                .where { BotConfigTable.key eq KEY_APP_ID }
                .singleOrNull()?.get(BotConfigTable.value).isNullOrBlank()
        }
    } catch (e: Exception) {
        false
    }

    fun getAppId(): String? = appId
    fun getEncryptKey(): String? = encryptKey
    fun getVerificationToken(): String? = verificationToken

    fun getMaskedAppId(): String? {
        val aid = appId ?: return null
        return if (aid.length <= 4) aid else aid.take(4) + "****"
    }

    // ── tenant_access_token ──
    // POST /open-apis/auth/v3/tenant_access_token/internal（app_id + app_secret），
    // 返回 code=0 / tenant_access_token / expire=7200（2 小时）。

    suspend fun getTenantAccessToken(): String {
        val now = nowSeconds()
        if (tenantToken != null && now < tokenExpiresAt - TOKEN_REFRESH_AHEAD_SECONDS) {
            return tenantToken!!
        }
        // Mutex 单飞：并发请求只发一次 HTTP（QQBotService 同款模式）
        return tokenMutex.withLock {
            // 二次检查：排队等锁期间可能已被前一个请求刷新
            val nowInLock = nowSeconds()
            if (tenantToken != null && nowInLock < tokenExpiresAt - TOKEN_REFRESH_AHEAD_SECONDS) {
                return@withLock tenantToken!!
            }
            refreshTenantToken()
        }
    }

    private suspend fun refreshTenantToken(): String {
        val aid = appId ?: throw IllegalStateException("飞书 Bot 未配置 AppID")
        val secret = appSecret ?: throw IllegalStateException("飞书 Bot 未配置 AppSecret")

        logger.info("刷新飞书 tenant_access_token ...")
        val body = buildJsonObject {
            put("app_id", aid)
            put("app_secret", secret)
        }.toString()
        val resp = try {
            http.post("$BASE_URL/open-apis/auth/v3/tenant_access_token/internal", body)
        } catch (e: Exception) {
            logger.error("请求飞书 tenant_access_token 失败", e)
            throw RuntimeException("飞书鉴权失败: ${e.message}")
        }
        if (resp.status !in 200..299) {
            throw RuntimeException("飞书鉴权失败：HTTP ${resp.status}")
        }
        val parsed = json.decodeFromString<FeishuTokenResponse>(resp.body)
        if (parsed.code != 0 || parsed.tenant_access_token.isBlank()) {
            throw RuntimeException("飞书鉴权失败：code=${parsed.code} msg=${parsed.msg}")
        }
        tenantToken = parsed.tenant_access_token
        tokenExpiresAt = nowSeconds() + parsed.expire
        logger.info("飞书 tenant_access_token 已刷新，${parsed.expire}s 后过期")
        return parsed.tenant_access_token
    }

    // ── 发消息 ──
    // content 是「JSON 字符串套 JSON」：外层请求体的 content 字段本身是字符串化的
    // {"text":"..."}（飞书最容易写错的一点，见调研文档 2.4）。

    /**
     * 发文本消息。
     * - [replyToMsgId] 非空：走 `POST /im/v1/messages/{message_id}/reply` 以原消息 id 回复，
     *   回到原会话（群聊 @ 触发的被动回复落在群里；主动推送传空即可）。
     * - [replyToMsgId] 为空：走 `POST /im/v1/messages?receive_id_type=open_id` 主动发送。
     * 返回是否发送成功（2xx）。
     */
    suspend fun sendText(openId: String, content: String, replyToMsgId: String? = null): Boolean {
        if (!isConfigured()) return false
        return try {
            val token = getTenantAccessToken()
            // 内层 content JSON：{"text":"..."} 整体序列化成字符串再放进外层 body
            val innerContent = buildJsonObject { put("text", content) }.toString()
            val (url, body) = if (!replyToMsgId.isNullOrBlank()) {
                "$BASE_URL/open-apis/im/v1/messages/$replyToMsgId/reply" to
                    buildJsonObject {
                        put("msg_type", "text")
                        put("content", innerContent)
                    }.toString()
            } else {
                "$BASE_URL/open-apis/im/v1/messages?receive_id_type=open_id" to
                    buildJsonObject {
                        put("receive_id", openId)
                        put("msg_type", "text")
                        put("content", innerContent)
                    }.toString()
            }
            val resp = http.post(
                url, body,
                mapOf("Authorization" to "Bearer $token")
            )
            logger.info("飞书发消息 → open_id=...${openId.takeLast(6)}: HTTP ${resp.status}")
            // 飞书业务错误也用 HTTP 200 包裹（code!=0），主动推送「成功才落去重」要求一并视为失败
            if (resp.status !in 200..299) return false
            return try {
                val code = json.parseToJsonElement(resp.body).jsonObject["code"]?.jsonPrimitive?.intOrNull
                code == null || code == 0
            } catch (_: Exception) {
                false
            }
        } catch (e: Exception) {
            logger.error("飞书发消息失败（open_id=...${openId.takeLast(6)}）", e)
            false
        }
    }

    // ── 绑定码 ──

    /** 绑定码日志掩码：只露前 3 位（如 123***），完整码不落日志（安全评审修复）。 */
    private fun maskCode(code: String) = "${code.take(3)}***"

    /** 生成 6 位绑定码（5 分钟过期），映射到发送者的 open_id。 */
    fun createBindCode(openId: String): String {
        val now = nowSeconds()
        // 顺手清理过期码
        bindCodes.entries.removeIf { it.value.expiresAt < now }
        val code = "%06d".format(random.nextInt(1_000_000))
        bindCodes[code] = BindEntry(openId, now + BIND_CODE_TTL_SECONDS)
        logger.info("已生成飞书绑定码 ${maskCode(code)}（open_id=...${openId.takeLast(6)}，5 分钟内有效）")
        return code
    }

    /** 消费绑定码：有效返回对应 open_id（一次性），无效/过期返回 null。 */
    fun consumeBindCode(code: String): String? {
        val entry = bindCodes.remove(code) ?: return null
        if (nowSeconds() > entry.expiresAt) {
            logger.info("飞书绑定码 ${maskCode(code)} 已过期")
            return null
        }
        logger.info("飞书绑定码 ${maskCode(code)} 已消费（open_id=...${entry.openId.takeLast(6)}）")
        return entry.openId
    }

    fun shutdown() {
        http.close()
    }
}

/**
 * 飞书事件验签与解密常量（B2）。
 *
 * 常量出处：调研文档《bot渠道机制调研与选型》2.2 节，并已对照飞书官方文档
 * 《Encrypt Key 加密配置案例》（encrypt-key-encryption-configuration-case）逐字核实：
 *  - 验签：`X-Lark-Signature = SHA256(timestamp + nonce + encrypt_key + 原始body)` 十六进制小写；
 *    timestamp / nonce 取请求头 `X-Lark-Request-Timestamp` / `X-Lark-Request-Nonce`。
 *    （注意：实施任务描述里写的 `encryptKey + timestamp + nonce + body` 顺序与官方不符，已弃用。）
 *  - 解密：AES-256-CBC，key = SHA256(encrypt_key) 原始 32 字节，密文 Base64 解码后
 *    **前 16 字节作 IV**，其余为密文；明文为 UTF-8 JSON（PKCS7 去填充）。
 *  - 官方文档自带测试向量：encrypt_key="test key"、
 *    密文 "P37w+VZImNgPEO1RBhJ6RtKl7n6zymIbEG1pReEzghk=" → 明文 "hello world"（单测已覆盖）。
 */
object FeishuCrypto {

    /** 计算事件推送验签（十六进制小写，与官方 Java/Go/Node 示例一致）。 */
    fun signature(timestamp: String, nonce: String, encryptKey: String, rawBody: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((timestamp + nonce + encryptKey + rawBody).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 解密飞书加密事件：返回明文 JSON 字符串。
     * PKCS7 去填充按官方 Java 示例实现；若填充字节异常（理论上不会出现），
     * 退回官方 Go 示例的宽容做法——截取首个 `{` 到最后一个 `}` 之间的 JSON。
     */
    fun decrypt(encryptKey: String, cipherBase64: String): String {
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest(encryptKey.toByteArray(Charsets.UTF_8)) // 32 字节原始摘要作 AES key
        val data = java.util.Base64.getDecoder().decode(cipherBase64)
        require(data.size > 16 && (data.size - 16) % 16 == 0) { "飞书密文长度非法" }

        // 密文前 16 字节作 IV（官方各语言示例一致）
        val iv = data.copyOfRange(0, 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(data.copyOfRange(16, data.size))

        // PKCS7 去填充（官方 Java 示例：尾字节 n ∈ 1..16，剥离 n 字节）
        val pad = plain.last().toInt() and 0xFF
        val unpadOk = pad in 1..16 && plain.size > pad &&
            plain.copyOfRange(plain.size - pad, plain.size).all { (it.toInt() and 0xFF) == pad }
        val text = if (unpadOk) {
            String(plain.copyOfRange(0, plain.size - pad), Charsets.UTF_8)
        } else {
            // 兜底：官方 Go 示例的宽容截取（首个 { 到最后一个 }）
            val s = String(plain, Charsets.UTF_8)
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start in 0..end) s.substring(start, end + 1) else s
        }
        return text
    }

    /**
     * 加密（[decrypt] 的逆过程，用于加密 Encrypt Key 配置下的 url_verification 响应）：
     * 随机 16 字节 IV 置于密文头部，AES-256-CBC + PKCS7 填充，整体 Base64。
     */
    fun encrypt(encryptKey: String, plain: String): String {
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest(encryptKey.toByteArray(Charsets.UTF_8))
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // JCE 的 PKCS5 对 16 字节分组即 PKCS7
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(iv + encrypted)
    }
}
