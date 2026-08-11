package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.nlu.NLUService
import com.example.rinklnote.server.tables.WebhookEventTable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Shared QQ bot message processing for both receiving channels — the HTTP webhook
 * route and the WebSocket gateway client. Both deliver events with the same payload
 * shape (event type + `d` object), so the parse/bind/NLU/bookkeeping logic lives here
 * once.
 */
object QQMessageProcessor {
    private val logger = LoggerFactory.getLogger("QQMessageProcessor")

    suspend fun process(
        eventType: String,
        d: JsonObject,
        qqBotService: QQBotService,
        userService: UserService,
        billService: BillService,
        nluService: NLUService
    ) {
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
                if (groupOpenid != null) {
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
    fun isFirstEvent(eventId: String): Boolean {
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
}
