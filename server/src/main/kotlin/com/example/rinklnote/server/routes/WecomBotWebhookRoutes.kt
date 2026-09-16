package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.WecomBotService
import com.example.rinklnote.server.services.WecomMessageProcessor
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
 * 企业微信智能机器人回调路由（C-W1）：`/api/wecom/bot/webhook`。
 *
 * 机制出处：调研文档《bot渠道机制调研与选型》3.2 / 3.4 节（对照企微官方文档核实）：
 *  - GET URL 验证：query 携带 `msg_signature / timestamp / nonce / echostr`，
 *    `msg_signature = SHA1(字典序排序(token, timestamp, nonce, echostr))`；比对一致后
 *    AES 解密 echostr，**原样返回解密出的明文**。
 *  - POST 消息回调：query 携带 `msg_signature / timestamp / nonce`，body 是外层 XML
 *    （`<Encrypt><![CDATA[密文]]></Encrypt>`）→ 验签（对密文）→ AES-256-CBC/PKCS7 解密
 *    （[WxCryptUtil]）→ 得到内层消息 XML → 同步处理 → **被动回复**：响应体直接返回加密 XML
 *    `<xml><Encrypt>..</Encrypt><MsgSignature>..</MsgSignature><TimeStamp>..</TimeStamp><Nonce>..</Nonce></xml>`。
 *  - 与飞书 / QQ 的关键差异：**不能异步 ACK**——回复就在 HTTP 响应体里，受 5 秒窗口约束；
 *    处理管线内已包 4s 硬超时（见 [WecomMessageProcessor]），本层只做加解密与组包。
 *
 * 配置存 bot_config KV：`wecom_token / wecom_encoding_aes_key / wecom_push_webhook_url`
 * （读写走 [WecomBotService]，照 FeishuBotService 的 KV 模式；Web 管理卡片属 Phase E）。
 */
fun Route.wecomBotWebhookRoutes(
    wecomBotService: WecomBotService,
    userService: UserService,
    billService: BillService,
    nluService: NLUService,
    budgetService: BudgetService,
    insightService: InsightService
) {
    val logger = LoggerFactory.getLogger("WecomBotWebhook")

    route("/api/wecom/bot") {
        // URL 验证：解密 echostr 原样返回明文（企微后台校验回显是否一致）
        get("/webhook") {
            val token = wecomBotService.getToken()
            val aesKey = wecomBotService.getEncodingAesKey()
            if (token.isNullOrBlank() || aesKey.isNullOrBlank()) {
                logger.warn("企微 webhook 未配置 token/EncodingAESKey，拒绝验证")
                return@get call.respondText("wecom bot not configured", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            }
            val msgSignature = call.request.queryParameters["msg_signature"]
            val timestamp = call.request.queryParameters["timestamp"]
            val nonce = call.request.queryParameters["nonce"]
            val echostr = call.request.queryParameters["echostr"]
            if (msgSignature == null || timestamp == null || nonce == null || echostr == null) {
                return@get call.respondText("missing params", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            }
            val plaintext = WecomCallbackCodec.decryptEchostr(token, aesKey, msgSignature, timestamp, nonce, echostr)
            if (plaintext == null) {
                logger.warn("企微 URL 验证失败（验签或解密不通过）")
                return@get call.respondText("invalid signature", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            }
            logger.info("企微 URL 验证通过，回显明文 ${plaintext.length} 字符")
            call.respondText(plaintext, ContentType.Text.Plain)
        }

        // 消息回调：验签 → 解密 → 同步处理 → 加密被动回复（5s 窗口内）
        post("/webhook") {
            val token = wecomBotService.getToken()
            val aesKey = wecomBotService.getEncodingAesKey()
            val msgSignature = call.request.queryParameters["msg_signature"]
            val timestamp = call.request.queryParameters["timestamp"]
            val nonce = call.request.queryParameters["nonce"]
            if (token.isNullOrBlank() || aesKey.isNullOrBlank() ||
                msgSignature == null || timestamp == null || nonce == null
            ) {
                logger.warn("企微回调缺少配置或验签参数")
                return@post call.respondText("not configured or missing params", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            }

            // 原始请求体只读一次（验签必须对原始字节对应的文本算）
            val rawBody = call.receiveText()
            val callback = WecomCallbackCodec.parseEncryptedMessage(token, aesKey, msgSignature, timestamp, nonce, rawBody)
            if (callback == null) {
                logger.warn("企微回调验签/解密失败，拒绝")
                return@post call.respondText("invalid signature", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            }
            val (fields, receiveId) = callback

            // 同步处理并产出回复文本；null（重复消息/事件类）→ 回空串，企微侧不作处理
            val reply = try {
                WecomMessageProcessor.process(
                    fields, wecomBotService, userService, billService, nluService, budgetService, insightService
                )
            } catch (e: Exception) {
                // 处理器内部已兜底，此处防路由层意外：回空串避免 5s 窗口被 500 重试拖垮
                logger.error("企微回调处理异常", e)
                null
            }
            if (reply == null) {
                call.respondText("", ContentType.Text.Plain)
                return@post
            }

            val body = WecomCallbackCodec.buildEncryptedTextReply(
                token, aesKey, receiveId,
                botUserName = fields["ToUserName"] ?: "",
                toUserName = fields["FromUserName"] ?: "",
                timestamp = timestamp, nonce = nonce,
                replyText = reply
            )
            call.respondText(body, ContentType.Text.Xml)
        }
    }
}

/** 企微回调解密结果：内层消息字段表 + receiveId（明文尾部校验串，企微为 corpid，回复加密时回填）。 */
internal data class WecomCallbackMessage(val fields: Map<String, String>, val receiveId: String)

/**
 * 企微回调的验签 / 解密 / 加密回复组装（路由层辅助，抽成纯函数便于单测——
 * 测试类路径无 ktor-server-test-host（「零新依赖」约束），路由级加解密用例直接对本组函数断言，
 * 向量风格照 WxCryptUtilTest：自造 43 位 EncodingAESKey + 加解密往返。
 */
internal object WecomCallbackCodec {

    /** GET 验证：验签（对加密的 echostr）→ 解密 → 返回明文；失败返回 null。 */
    fun decryptEchostr(
        token: String,
        aesKey: String,
        msgSignature: String,
        timestamp: String,
        nonce: String,
        echostr: String
    ): String? {
        if (!WxCryptUtil.verifySignature(msgSignature, token, timestamp, nonce, echostr)) return null
        return try {
            WxCryptUtil.decrypt(aesKey, echostr).message
        } catch (_: Exception) {
            null
        }
    }

    /** POST 回调：解析外层 XML 取 Encrypt → 验签（对密文）→ 解密 → 内层消息字段；失败返回 null。 */
    fun parseEncryptedMessage(
        token: String,
        aesKey: String,
        msgSignature: String,
        timestamp: String,
        nonce: String,
        rawXml: String
    ): WecomCallbackMessage? {
        val outer = WxCryptUtil.parseXml(rawXml)
        val encrypt = outer["Encrypt"] ?: return null
        if (!WxCryptUtil.verifySignature(msgSignature, token, timestamp, nonce, encrypt)) return null
        val decrypted = try {
            WxCryptUtil.decrypt(aesKey, encrypt)
        } catch (_: Exception) {
            return null
        }
        return WecomCallbackMessage(WxCryptUtil.parseXml(decrypted.message), decrypted.receiveId)
    }

    /**
     * 组装 text 被动回复并加密（官方「加解密方案」的回复包结构，字段名冒烟时再对照核对）：
     * 内层 XML：ToUserName = 原发送者 / FromUserName = 机器人（原消息 ToUserName）/ MsgType=text / Content=回复文本；
     * 外层包裹：Encrypt（加密，receiveId 回填解密时拿到的 corpid）+ MsgSignature（对新密文按同 token 重算）
     * + TimeStamp / Nonce（沿用请求侧值——企微侧会用 XML 里带回的值验签，自洽即可）。
     */
    fun buildEncryptedTextReply(
        token: String,
        aesKey: String,
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
