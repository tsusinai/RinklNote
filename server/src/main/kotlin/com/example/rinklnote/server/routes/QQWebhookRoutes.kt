package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.nlu.NLUService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

@Serializable
data class WebhookReply(val reply: String)

fun Route.qqWebhookRoutes(webhookSecret: String, userService: UserService, billService: BillService, nluService: NLUService) {
    route("/api/qq") {
        post("/webhook") {
            try {
                val headerSecret = call.request.header("X-Webhook-Secret")
                if (headerSecret == null || !MessageDigest.isEqual(
                        headerSecret.toByteArray(), webhookSecret.toByteArray()
                    )) {
                    call.respond(HttpStatusCode.Forbidden, WebhookReply("Unauthorized"))
                    return@post
                }

                val body = call.receiveText()
                val json = Json.parseToJsonElement(body).jsonObject

                val userId = json["user_id"]?.jsonPrimitive?.longOrNull
                    ?: return@post call.respond(HttpStatusCode.BadRequest, WebhookReply("无法获取用户ID"))

                val messageArray = json["message"]?.jsonArray
                val text = messageArray?.mapNotNull { element ->
                    val obj = element.jsonObject
                    if (obj["type"]?.jsonPrimitive?.content == "text") {
                        obj["data"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                    } else null
                }?.joinToString("") ?: ""

                if (text.isBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        WebhookReply("请发送文本消息，如：午餐20元")
                    )
                }

                val user = userService.findByQQ(userId.toString())
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        WebhookReply("未绑定账号，请先在App中绑定QQ号")
                    )

                val result = nluService.parse(text, user.id)

                if (result.amount == null || result.amount <= 0) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        WebhookReply("无法识别金额，请说如'午餐20元'")
                    )
                }

                val bill = billService.createBill(
                    userId = user.id,
                    amount = result.amount,
                    categoryName = result.categoryName,
                    remark = result.remark,
                    source = "QQ"
                )

                call.respond(WebhookReply("已记录: ${bill.categoryName} ¥${"%.2f".format(bill.amount)}"))
            } catch (e: Exception) {
                call.application.environment.log.error("QQ Webhook error", e)
                call.respond(HttpStatusCode.InternalServerError, WebhookReply("服务器内部错误"))
            }
        }
    }
}
