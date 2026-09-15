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
 * Verification Token 属旧版轻校验：未配 Encrypt Key 时对明文载荷做软校验（有 token 字段且不匹配才拒），
 * 配了 Encrypt Key 时以验签为准、跳过 token 校验。
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
                    if (!expected.equals(sig, ignoreCase = true)) {
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

                // Verification Token 软校验：未配 Encrypt Key 时才做（配了以验签为准）
                if (encryptKey.isNullOrBlank() && !verificationToken.isNullOrBlank()) {
                    // url_verification 的 token 在顶层；v2 事件的 token 在 header.token
                    val given = payload["token"]?.jsonPrimitive?.contentOrNull
                        ?: payload["header"]?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
                    if (given != null && given != verificationToken) {
                        logger.warn("飞书 webhook Verification Token 不匹配")
                        return@post call.respondText(
                            """{"message":"invalid verification token"}""",
                            ContentType.Application.Json, HttpStatusCode.Forbidden
                        )
                    }
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
                    logger.info("飞书 URL 验证：challenge=$challenge")
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
