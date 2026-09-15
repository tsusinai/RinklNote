package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.MpBotService
import com.example.rinklnote.server.services.MpMessageProcessor
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.NLUService
import com.example.rinklnote.server.services.wx.WxCryptUtil
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

/**
 * 个人订阅号回调路由（C-W2）：`/api/mp/bot/webhook`。
 *
 * 机制出处：调研文档《bot渠道机制调研与选型》4.2 / 4.4 节（对照微信公众号官方文档核实）：
 *  - GET 接入验证：query 携带 `signature / timestamp / nonce / echostr`。
 *    明文模式（选定的默认路线）：`signature = SHA1(字典序排序(token, timestamp, nonce))`
 *    （[WxCryptUtil.signature3]），一致则**原样返回 echostr**；
 *    若后台切了「安全模式」（配置了 mp_encoding_aes_key）：echostr 是密文，
 *    按 4 参验签后解密，**返回解密出的明文**（同企微 WxCryptUtil 那套）。
 *  - POST 消息回调：明文模式 body 就是消息 XML 明文（`signature` 仍按 3 参校验）；
 *    安全模式 body 是 `<Encrypt><![CDATA[密文]]></Encrypt>`（4 参验签 + 解密）。
 *    → 同步处理 → **被动回复**：响应体直接返回 XML 文本，5 秒窗口（管线内已包 4s 硬超时，
 *    见 [MpMessageProcessor]）；无回复（null）时回空串——微信对空串不作处理，
 *    也避免触发「该公众号暂时无法提供服务」。
 *
 * 订阅号只收不推：本路由之外没有任何发送通道，配置仅 `mp_token`（+可选 `mp_encoding_aes_key`）。
 */
fun Route.mpWebhookRoutes(
    mpBotService: MpBotService,
    userService: UserService,
    billService: BillService,
    nluService: NLUService,
    budgetService: BudgetService,
    insightService: InsightService
) {
    val logger = LoggerFactory.getLogger("MpWebhook")

    route("/api/mp/bot") {
        // 接入验证：明文模式原样回 echostr；安全模式解密后回明文
        get("/webhook") {
            val token = mpBotService.getToken()
            val aesKey = mpBotService.getEncodingAesKey()
            if (token.isNullOrBlank()) {
                logger.warn("订阅号 webhook 未配置 token，拒绝验证")
                return@get call.respondText("mp bot not configured", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            }
            val signature = call.request.queryParameters["signature"]
            val timestamp = call.request.queryParameters["timestamp"]
            val nonce = call.request.queryParameters["nonce"]
            val echostr = call.request.queryParameters["echostr"]
            if (signature == null || timestamp == null || nonce == null || echostr == null) {
                return@get call.respondText("missing params", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            }
            val echoReply = MpCallbackCodec.verifyEchostr(token, aesKey, signature, timestamp, nonce, echostr)
            if (echoReply == null) {
                logger.warn("订阅号接入验证失败（验签或解密不通过）")
                return@get call.respondText("invalid signature", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            }
            logger.info("订阅号接入验证通过")
            call.respondText(echoReply, ContentType.Text.Plain)
        }

        // 消息回调：验签 →（安全模式解密）→ 同步处理 → 被动回复 XML（5s 窗口内）
        post("/webhook") {
            val token = mpBotService.getToken()
            val aesKey = mpBotService.getEncodingAesKey()
            val signature = call.request.queryParameters["signature"]
            val timestamp = call.request.queryParameters["timestamp"]
            val nonce = call.request.queryParameters["nonce"]
            if (token.isNullOrBlank() || signature == null || timestamp == null || nonce == null) {
                logger.warn("订阅号回调缺少配置或验签参数")
                return@post call.respondText("not configured or missing params", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            }

            // 原始请求体只读一次
            val rawBody = call.receiveText()
            val callback = MpCallbackCodec.parseMessage(token, aesKey, signature, timestamp, nonce, rawBody)
            if (callback == null) {
                logger.warn("订阅号回调验签/解密失败，拒绝")
                return@post call.respondText("invalid signature", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            }
            val fields = callback.fields

            // 同步处理并产出回复文本；null（重复消息/事件类）→ 回空串（微信不作处理，不触发重试展示）
            val reply = try {
                MpMessageProcessor.process(fields, userService, billService, nluService, budgetService, insightService)
            } catch (e: Exception) {
                // 处理器内部已兜底，此处防路由层意外：回空串避免 5s 窗口被 500 重试拖垮
                logger.error("订阅号回调处理异常", e)
                null
            }
            if (reply == null) {
                call.respondText("", ContentType.Text.Plain)
                return@post
            }

            val body = MpCallbackCodec.buildTextReply(
                token, aesKey, callback.receiveId,
                botUserName = fields["ToUserName"] ?: "",
                toUserName = fields["FromUserName"] ?: "",
                timestamp = timestamp, nonce = nonce,
                replyText = reply
            )
            call.respondText(body, ContentType.Text.Xml)
        }
    }
}

/**
 * 订阅号回调解析结果：消息字段表 + 是否安全模式（回复组包分支用）+ receiveId
 * （安全模式解密尾部校验串，即公众号 appid；明文模式为空）。
 */
internal data class MpCallbackMessage(val fields: Map<String, String>, val encrypted: Boolean, val receiveId: String)

/**
 * 订阅号回调的验签 / 解析 / 回复组装（路由层辅助，抽成纯函数便于单测——
 * 测试类路径无 ktor-server-test-host（「零新依赖」约束），路由级加解密用例直接对本组函数断言，
 * 向量风格照 WxCryptUtilTest）。
 */
internal object MpCallbackCodec {

    /**
     * GET 接入验证：明文模式（aesKey 为空）按 3 参验签后**原样返回 echostr**；
     * 安全模式按 4 参验签（对加密的 echostr）后解密返回明文。失败返回 null。
     */
    fun verifyEchostr(
        token: String,
        aesKey: String?,
        signature: String,
        timestamp: String,
        nonce: String,
        echostr: String
    ): String? {
        return if (aesKey.isNullOrBlank()) {
            // 明文模式：signature = SHA1(字典序排序(token, timestamp, nonce))
            if (!WxCryptUtil.verifySignature(signature, token, timestamp, nonce, "")) return null
            echostr
        } else {
            if (!WxCryptUtil.verifySignature(signature, token, timestamp, nonce, echostr)) return null
            try {
                WxCryptUtil.decrypt(aesKey, echostr).message
            } catch (_: Exception) {
                return null
            }
        }
    }

    /**
     * POST 消息解析：body 含 `Encrypt` 字段 → 安全模式（4 参验签对密文 + 解密，aesKey 必须已配）；
     * 否则明文模式（3 参验签，body 即消息 XML）。失败返回 null。
     */
    fun parseMessage(
        token: String,
        aesKey: String?,
        signature: String,
        timestamp: String,
        nonce: String,
        rawXml: String
    ): MpCallbackMessage? {
        val outer = WxCryptUtil.parseXml(rawXml)
        val encrypt = outer["Encrypt"]
        return if (encrypt != null) {
            // 安全模式：与企微同套 4 参验签 + AES-256-CBC/PKCS7 解密
            if (aesKey.isNullOrBlank()) return null // 本端没配 key 却收到密文，配置不一致
            if (!WxCryptUtil.verifySignature(signature, token, timestamp, nonce, encrypt)) return null
            val decrypted = try {
                WxCryptUtil.decrypt(aesKey, encrypt)
            } catch (_: Exception) {
                return null
            }
            MpCallbackMessage(WxCryptUtil.parseXml(decrypted.message), encrypted = true, receiveId = decrypted.receiveId)
        } else {
            // 明文模式：signature 仍按 3 参校验（XML 明文 + 每条带签名可校验）
            if (!WxCryptUtil.verifySignature(signature, token, timestamp, nonce, "")) return null
            MpCallbackMessage(outer, encrypted = false, receiveId = "")
        }
    }

    /**
     * 组装 text 被动回复：明文模式直接返回回复 XML；
     * 安全模式加密后按官方回复包结构包裹（同 [WecomCallbackCodec.buildEncryptedTextReply]）。
     * 内层 XML：ToUserName = 原发送者 openid / FromUserName = 公众号（原消息 ToUserName）/ MsgType=text。
     */
    fun buildTextReply(
        token: String,
        aesKey: String?,
        receiveId: String,
        botUserName: String,
        toUserName: String,
        timestamp: String,
        nonce: String,
        replyText: String
    ): String {
        val replyXml = WxCryptUtil.buildXml(
            linkedMapOf(
                "ToUserName" to toUserName,
                "FromUserName" to botUserName,
                "CreateTime" to (System.currentTimeMillis() / 1000).toString(),
                "MsgType" to "text",
                "Content" to replyText
            )
        )
        if (aesKey.isNullOrBlank()) return replyXml
        val cipher = WxCryptUtil.encrypt(aesKey, replyXml, receiveId)
        val sig = WxCryptUtil.signature(token, timestamp, nonce, cipher)
        return WxCryptUtil.buildXml(
            linkedMapOf(
                "Encrypt" to cipher,
                "MsgSignature" to sig,
                "TimeStamp" to timestamp,
                "Nonce" to nonce
            )
        )
    }
}
