package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.FeishuBotService
import com.example.rinklnote.server.services.FeishuCrypto
import com.example.rinklnote.server.services.FeishuMessageProcessor
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.NLUService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * 飞书事件订阅 webhook（B2）：`POST /api/feishu/bot/webhook`。
 *
 * 处理顺序（照 QQBotWebhookRoutes 的「先安全校验 → 立即 ACK → 异步处理」骨架）：
 *  1. 配置了 Encrypt Key 时先验签：`X-Lark-Signature = SHA256(timestamp + nonce + encrypt_key + 原始body)`
 *     （常量出处见 [FeishuCrypto]，已对照官方文档核实）；
 *  2. body 含 `encrypt` 字段则 AES-256-CBC 解密（key = SHA256(encrypt_key)，密文前 16 字节作 IV）；
 *  3. `type=url_verification`：回显 challenge（配置 Encrypt Key 后响应体也须按同套加密返回）；
 *  4. 普通事件：立即 200，消息体丢进协程异步处理（去重在 FeishuMessageProcessor 内做）。
 *
 * Verification Token 强校验（安全评审修复）：未配 Encrypt Key 时请求**必须**携带与服务端一致的
 * verification_token —— 完全不带同样 403（旧实现「不带即放行」可被伪造 im.message.receive_v1
 * 任意 open_id 自动开户/注账）；Encrypt Key 与 verification_token 都未配时 webhook 直接拒绝服务
 * （503 全中文错误）；配了 Encrypt Key 时以验签为准、跳过 token 校验。
 *
 * 校验决策抽成纯函数 [checkVerificationToken] 便于单测（测试无 Ktor test host 依赖）。
 */
fun Route.feishuBotWebhookRoutes(
    feishuBotService: FeishuBotService,
    userService: UserService,
    billService: BillService,
    nluService: NLUService,
    budgetService: BudgetService,
    insightService: InsightService
) {
    val logger = LoggerFactory.getLogger("FeishuBotWebhook")
    val scope = CoroutineScope(Dispatchers.Default)

    route("/api/feishu/bot") {
        post("/webhook") {
            try {
                // 原始请求体只读一次（验签必须对原始字节对应的文本算）
                val rawBody = call.receiveText()
                val encryptKey = feishuBotService.getEncryptKey()
                val verificationToken = feishuBotService.getVerificationToken()

                // 1) 验签：配了 Encrypt Key 就必须带三个验签头；对原始（加密）body 计算
                if (!encryptKey.isNullOrBlank()) {
                    val ts = call.request.header("X-Lark-Request-Timestamp")
                    val nonce = call.request.header("X-Lark-Request-Nonce")
                    val sig = call.request.header("X-Lark-Signature")
                    if (ts == null || nonce == null || sig == null) {
                        logger.warn("飞书 webhook 缺少验签头")
                        return@post call.respondText(
                            """{"message":"missing lark signature headers"}""",
                            ContentType.Application.Json, HttpStatusCode.Forbidden
                        )
                    }
                    val expected = FeishuCrypto.signature(ts, nonce, encryptKey, rawBody)
                    // 恒时比较（安全评审修复，照 QQWebhookRoutes 的 MessageDigest.isEqual 写法），
                    // 防时序侧信道逐字节猜测签名；两侧先统一小写保持既有的大小写不敏感语义
                    if (!MessageDigest.isEqual(
                            expected.lowercase().toByteArray(Charsets.UTF_8),
                            sig.lowercase().toByteArray(Charsets.UTF_8)
                        )
                    ) {
                        logger.warn("飞书 webhook 验签失败")
                        return@post call.respondText(
                            """{"message":"invalid signature"}""",
                            ContentType.Application.Json, HttpStatusCode.Forbidden
                        )
                    }
                }

                // 2) 解密外层 {"encrypt":"..."}（配置 Encrypt Key 后 url_verification 也是密文）
                val outer = Json.parseToJsonElement(rawBody).jsonObject
                var payloadText = rawBody
                var encrypted = false
                val encryptField = outer["encrypt"]?.jsonPrimitive?.contentOrNull
                if (encryptField != null) {
                    if (encryptKey.isNullOrBlank()) {
                        // 飞书侧配了 Encrypt Key 而本端没配：无法解密，配置不一致
                        logger.warn("飞书 webhook 收到加密事件但本端未配置 Encrypt Key")
                        return@post call.respondText(
                            """{"message":"encrypt key not configured"}""",
                            ContentType.Application.Json, HttpStatusCode.BadRequest
                        )
                    }
                    payloadText = try {
                        FeishuCrypto.decrypt(encryptKey, encryptField)
                    } catch (e: Exception) {
                        logger.warn("飞书 webhook 解密失败: ${e.message}")
                        return@post call.respondText(
                            """{"message":"decrypt failed"}""",
                            ContentType.Application.Json, HttpStatusCode.BadRequest
                        )
                    }
                    encrypted = true
                }

                val payload = Json.parseToJsonElement(payloadText).jsonObject

                // Verification Token 强校验（安全评审修复，决策逻辑见 [checkVerificationToken]）：
                // 未配 Encrypt Key 时请求必须带一致 token（缺失同样 403）；双未配直接拒绝服务。
                checkVerificationToken(encryptKey, verificationToken, payload)?.let { (status, message) ->
                    logger.warn("飞书 webhook 拒绝：$message")
                    return@post call.respondText(
                        """{"message":"$message"}""",
                        ContentType.Application.Json, status
                    )
                }

                // 3) URL 验证：回显 challenge。配 Encrypt Key 时响应体也按官方要求加密返回
                //    {"encrypt": <加密后的 {"challenge":...>} }（实施时对照官方文档核对过：官方 SDK 行为一致）
                if (payload["type"]?.jsonPrimitive?.contentOrNull == "url_verification") {
                    val challenge = payload["challenge"]?.jsonPrimitive?.contentOrNull
                        ?: return@post call.respondText(
                            """{"message":"missing challenge"}""",
                            ContentType.Application.Json, HttpStatusCode.BadRequest
                        )
                    val challengeJson = buildJsonObject { put("challenge", challenge) }.toString()
                    val responseBody = if (encrypted && !encryptKey.isNullOrBlank()) {
                        buildJsonObject { put("encrypt", FeishuCrypto.encrypt(encryptKey, challengeJson)) }.toString()
                    } else {
                        challengeJson
                    }
                    // challenge 只截前 16 字符进日志（安全评审修复：完整值虽为一次性回显串，仍避免全量落日志）
                    logger.info("飞书 URL 验证：challenge=${challenge.take(16)}")
                    return@post call.respondText(responseBody, ContentType.Application.Json)
                }

                // 4) 立即 200，异步处理（飞书 3s 内收不到 200 会重试，处理务必不占响应窗口）
                call.respondText("{}", ContentType.Application.Json)

                // v2 schema：header.event_type + event；兼容 v1：type=event_callback + event
                val eventType = payload["header"]?.jsonObject?.get("event_type")?.jsonPrimitive?.contentOrNull
                    ?: payload["event_type"]?.jsonPrimitive?.contentOrNull
                    ?: payload["type"]?.jsonPrimitive?.contentOrNull
                val eventObj = payload["event"]?.jsonObject
                if (eventType == null || eventObj == null) {
                    logger.info("飞书 webhook 忽略：无 event_type/event（type=$eventType）")
                    return@post
                }

                scope.launch {
                    // 只有消息事件进处理器；其余事件类型（如应用变更）记录后忽略
                    if (eventType == "im.message.receive_v1") {
                        FeishuMessageProcessor.process(
                            eventObj, feishuBotService, userService, billService, nluService, budgetService, insightService
                        )
                    } else {
                        logger.info("飞书事件忽略：$eventType")
                    }
                }
            } catch (e: Exception) {
                logger.error("飞书 webhook 处理异常", e)
                try {
                    call.respondText("{}", ContentType.Application.Json)
                } catch (_: Exception) { }
            }
        }
    }
}

/**
 * 飞书 webhook 的 Verification Token 校验决策（纯函数，安全评审修复，便于单测）。
 *
 *  - 配了 Encrypt Key：路由已先行验签（X-Lark-Signature），以验签为准 → 放行；
 *  - Encrypt Key 与 verification_token 都未配：bot 无法验证任何请求来源 → 拒绝服务
 *    （503，全中文错误，引导先完成安全配置）；
 *  - 仅配 verification_token：请求必须携带 token（url_verification 在顶层、v2 事件在
 *    header.token）且与配置一致 —— 缺失与不一致一律 403（旧实现「不带即放行」是可被
 *    伪造 im.message.receive_v1 任意开户/注账的绕过口）。
 *
 * 返回 null 表示放行，否则 (HTTP 状态码, 错误 JSON 的 message 字段值)。
 */
internal fun checkVerificationToken(
    encryptKey: String?,
    verificationToken: String?,
    payload: JsonObject
): Pair<HttpStatusCode, String>? = when {
    !encryptKey.isNullOrBlank() -> null
    verificationToken.isNullOrBlank() ->
        HttpStatusCode.ServiceUnavailable to "机器人未完成安全配置：请先在 Web 设置页配置 Encrypt Key 或 Verification Token"
    else -> {
        // url_verification 的 token 在顶层；v2 事件的 token 在 header.token
        val given = payload["token"]?.jsonPrimitive?.contentOrNull
            ?: payload["header"]?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
        if (given != null && given == verificationToken) null
        else HttpStatusCode.Forbidden to "invalid verification token"
    }
}
