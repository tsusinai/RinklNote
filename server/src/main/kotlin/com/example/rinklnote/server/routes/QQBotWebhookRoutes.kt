package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.QQBotService
import com.example.rinklnote.server.services.QQMessageProcessor
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.NLUService
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
import org.slf4j.LoggerFactory
fun Route.qqBotWebhookRoutes(
    qqBotService: QQBotService,
    userService: UserService,
    billService: BillService,
    nluService: NLUService,
    budgetService: BudgetService,
    insightService: InsightService
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
                            if (QQMessageProcessor.isFirstEvent(dedupKey)) {
                                QQMessageProcessor.process(eventType, d, qqBotService, userService, billService, nluService, budgetService, insightService)
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
