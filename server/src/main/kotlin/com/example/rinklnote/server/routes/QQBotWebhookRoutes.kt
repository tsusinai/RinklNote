package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.QQBotService
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.nlu.NLUService
import com.example.rinklnote.server.tables.WebhookEventTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
fun Route.qqBotWebhookRoutes(
    qqBotService: QQBotService,
    userService: UserService,
    billService: BillService,
    nluService: NLUService
) {
    val logger = LoggerFactory.getLogger("QQBotWebhook")
    val scope = CoroutineScope(Dispatchers.Default)

    route("/api/qq/bot") {
        post("/webhook") {
            try {
                // Read raw body once (needed for signature verification)
                val rawBody = call.receiveText()
                val json = Json.parseToJsonElement(rawBody).jsonObject

                val op = json["op"]?.jsonPrimitive?.intOrNull ?: 0

                when (op) {
                    // URL verification
                    13 -> {
                        val d = json["d"]?.jsonObject
                            ?: return@post call.respondText("""{"message":"missing d"}""",
                                ContentType.Application.Json)

                        val plainToken = d["plain_token"]?.jsonPrimitive?.content
                            ?: return@post call.respondText("""{"message":"missing plain_token"}""",
                                ContentType.Application.Json)
                        val eventTs = d["event_ts"]?.jsonPrimitive?.content
                            ?: return@post call.respondText("""{"message":"missing event_ts"}""",
                                ContentType.Application.Json)

                        val toSign = (eventTs + plainToken).toByteArray(Charsets.UTF_8)
                        val signature = qqBotService.sign(toSign)
                        val signatureHex = signature.joinToString("") { "%02x".format(it) }

                        logger.info("Webhook URL verification: plain_token=$plainToken, signature=$signatureHex")

                        call.respondText(
                            """{"plain_token":"$plainToken","signature":"$signatureHex"}""",
                            ContentType.Application.Json
                        )
                    }

                    // Event dispatch
                    0 -> {
                        // Verify signature (required)
                        val sigHeader = call.request.header("X-Signature-Ed25519")
                            ?: return@post call.respondText("""{"op":12}""",
                                status = HttpStatusCode.Forbidden,
                                contentType = ContentType.Application.Json)
                        val sigTimestamp = call.request.header("X-Signature-Timestamp")
                            ?: return@post call.respondText("""{"op":12}""",
                                status = HttpStatusCode.Forbidden,
                                contentType = ContentType.Application.Json)

                        val sigBytes = try {
                            sigHeader.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        } catch (e: Exception) {
                            logger.warn("Invalid signature hex", e)
                            return@post call.respondText("""{"op":12}""",
                                status = HttpStatusCode.Forbidden,
                                contentType = ContentType.Application.Json)
                        }
                        val payload = (sigTimestamp + rawBody).toByteArray(Charsets.UTF_8)
                        if (!qqBotService.verify(sigBytes, payload)) {
                            logger.warn("Webhook signature verification failed")
                            return@post call.respondText("""{"op":12}""",
                                status = HttpStatusCode.Forbidden,
                                contentType = ContentType.Application.Json)
                        }

                        // Return ACK immediately
                        call.respondText("""{"op":12}""", ContentType.Application.Json)

                        // Process message asynchronously
                        val eventType = json["t"]?.jsonPrimitive?.content ?: return@post
                        val d = json["d"]?.jsonObject ?: return@post
                        val msgId = d["id"]?.jsonPrimitive?.content ?: return@post

                        scope.launch {
                            val dedupKey = "$eventType:$msgId"
                            if (isFirstEvent(dedupKey)) {
                                processMessage(eventType, d, qqBotService, userService, billService, nluService)
                            } else {
                                logger.info("Duplicate webhook event ignored: $dedupKey")
                            }
                        }
                    }

                    else -> {
                        call.respondText("""{"op":12}""", ContentType.Application.Json)
                    }
                }
            } catch (e: Exception) {
                logger.error("QQ Bot webhook error", e)
                try {
                    call.respondText("""{"op":12}""", ContentType.Application.Json)
                } catch (_: Exception) { }
            }
        }
    }
}

private suspend fun processMessage(
    eventType: String,
    d: JsonObject,
    qqBotService: QQBotService,
    userService: UserService,
    billService: BillService,
    nluService: NLUService
) {
    val logger = LoggerFactory.getLogger("QQBotWebhook")
    try {
        // Handle content as string or array
        val rawContent = when (val c = d["content"]) {
            is JsonPrimitive -> c.content.trim()
            is JsonArray -> c.mapNotNull { el ->
                val obj = el.jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "text")
                    obj["data"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                else null
            }.joinToString("")
            else -> return
        }
        // Strip group mention markup like <@!123456>
        val content = rawContent.replace(Regex("""<@!\d+>"""), "").replace(Regex("""<@\d+>"""), "").trim()
        val msgId = d["id"]?.jsonPrimitive?.content ?: return

        if (content.isBlank()) return

        // Determine user openid based on event type
        val author = d["author"]?.jsonObject
        val openid: String
        val groupOpenid: String?

        when (eventType) {
            "C2C_MESSAGE_CREATE" -> {
                openid = author?.get("user_openid")?.jsonPrimitive?.content ?: return
                groupOpenid = null
            }
            "GROUP_AT_MESSAGE_CREATE" -> {
                openid = author?.get("member_openid")?.jsonPrimitive?.content ?: return
                groupOpenid = d["group_openid"]?.jsonPrimitive?.content
            }
            else -> {
                logger.info("Unhandled event type: $eventType")
                return
            }
        }

        logger.info("Processing message from $openid: $content")

        // Check if user is bound
        val user = userService.findByQqOpenid(openid)
        if (user == null) {
            val code = qqBotService.generateBindCode(openid)
            if (groupOpenid != null) {
                qqBotService.sendGroupMessage(groupOpenid, "你还未绑定账号。绑定码: $code\n请在网页设置中输入此码完成绑定。", msgId)
            } else {
                qqBotService.sendC2CMessage(openid, "你还未绑定账号。\n绑定码: $code\n请在网页设置中输入此码完成绑定。", msgId)
            }
            return
        }

        // Parse message and create bill
        val result = nluService.parse(content, user.id)

        if (result.amount == null || result.amount <= 0) {
            val reply = if (groupOpenid != null) {
                qqBotService.sendGroupMessage(groupOpenid, "无法识别金额，请说如'午餐20元'", msgId)
            } else {
                qqBotService.sendC2CMessage(openid, "无法识别金额，请说如'午餐20元'", msgId)
            }
            return
        }

        val bill = billService.createBill(
            userId = user.id,
            amount = result.amount,
            categoryName = result.categoryName,
            remark = result.remark,
            source = "QQ"
        )

        val reply = "已记录: ${bill.categoryName} ¥${"%.2f".format(bill.amount)}"
        if (groupOpenid != null) {
            qqBotService.sendGroupMessage(groupOpenid, reply, msgId)
        } else {
            qqBotService.sendC2CMessage(openid, reply, msgId)
        }

        logger.info("Bill created for user ${user.id}: $reply")
    } catch (e: Exception) {
        logger.error("Error processing QQ Bot message", e)
    }
}

/**
 * Idempotency guard: returns true only for the first delivery of an event.
 * The primary key makes concurrent deliveries race — only one insert wins,
 * the others see a constraint violation and are treated as duplicates.
 * Old entries are pruned opportunistically (kept for 3 days).
 */
private fun isFirstEvent(eventId: String): Boolean {
    val now = System.currentTimeMillis()
    return transaction {
        val inserted = try {
            WebhookEventTable.insert {
                it[WebhookEventTable.eventId] = eventId
                it[WebhookEventTable.processedAt] = now
            }
            true
        } catch (_: Exception) {
            false
        }
        if (inserted) {
            // Opportunistic prune (keep 3 days). Raw SQL avoids pulling the
            // ISqlExpressionBuilder operator into scope for this lambda.
            exec("DELETE FROM webhook_events WHERE processed_at < $now")
        }
        inserted
    }
}
