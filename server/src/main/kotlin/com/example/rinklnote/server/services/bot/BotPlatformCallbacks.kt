package com.example.rinklnote.server.services.bot

import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.QQBotService
import kotlinx.serialization.json.*

/** 只接收已由平台路由验签的载荷；动作里的用户 ID 不作为身份依据。 */
object BotPlatformCallbacks {
    fun feishu(event: JsonObject, service: BotInteractionService): JsonObject {
        return try {
            val actor = event["operator"]?.jsonObject?.get("open_id")?.jsonPrimitive?.contentOrNull
                ?: throw BotActionException(403, "缺少操作者身份")
            val action = event["action"]?.jsonObject ?: throw BotActionException(400, "缺少操作")
            val token = action["value"]?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
                ?: throw BotActionException(400, "缺少操作入口")
            val values = action["form_value"]?.jsonObject
            val edit = values?.let {
                if (it.containsKey("amount")) {
                    val minor = Money.parseMinor(it["amount"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        ?: throw BotActionException(400, "金额必须大于零且最多两位小数")
                    val category = it["category"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: throw BotActionException(400, "请选择分类")
                    InteractionEdit("save", amountMinor = minor, categoryId = category)
                } else {
                    val time = it["time"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (!Regex("\\d{2}:\\d{2}").matches(time)) throw BotActionException(400, "提醒时间应为 HH:mm")
                    InteractionEdit("reminders", enabled = it["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                        ?: throw BotActionException(400, "请选择提醒状态"),
                        hour = time.substringBefore(':').toInt(), minute = time.substringAfter(':').toInt())
                }
            }
            val result = service.action(token, "FEISHU", actor, edit)
            buildJsonObject {
                putJsonObject("toast") { put("type", "success"); put("content", result.text.take(80)) }
                putJsonObject("card") { put("type", "raw"); put("data", result.card ?: BotCardRenderer.result(result.text)) }
            }
        } catch (e: BotActionException) {
            buildJsonObject { putJsonObject("toast") { put("type", "error"); put("content", e.message) } }
        } catch (_: Exception) {
            buildJsonObject { putJsonObject("toast") { put("type", "error"); put("content", "未能处理，请重新打开卡片") } }
        }
    }

    suspend fun qq(data: JsonObject, bot: QQBotService) {
        val id = data["id"]?.jsonPrimitive?.contentOrNull ?: return
        val type = data["type"]?.jsonPrimitive?.intOrNull
        if (type != 11 && type != 12) return
        val group = data["group_openid"]?.jsonPrimitive?.contentOrNull
        // 群按钮回调优先使用群成员身份，必须与 GROUP_AT_MESSAGE_CREATE 建账时的身份保持一致。
        val actor = if (group != null) {
            data["group_member_openid"]?.jsonPrimitive?.contentOrNull
                ?: data["member_openid"]?.jsonPrimitive?.contentOrNull
                ?: data["user_openid"]?.jsonPrimitive?.contentOrNull
        } else {
            data["user_openid"]?.jsonPrimitive?.contentOrNull
                ?: data["group_member_openid"]?.jsonPrimitive?.contentOrNull
                ?: data["member_openid"]?.jsonPrimitive?.contentOrNull
        }
        val token = data["data"]?.jsonObject?.get("resolved")?.jsonObject?.get("button_data")?.jsonPrimitive?.contentOrNull
        val service = bot.interactions
        if (actor == null || token == null || service == null) { bot.acknowledgeInteraction(id, 1); return }
        try {
            val result = service.action(token, "QQ", actor)
            bot.acknowledgeInteraction(id, 0)
            if (result.qqCard != null) bot.sendCard(actor, group, result.qqCard, id, interactionEvent = true)
            else bot.sendInteractionText(actor, group, result.text, id, interactionEvent = true)
        } catch (e: BotActionException) {
            bot.acknowledgeInteraction(id, when (e.status) { 403 -> 4; 409 -> 3; else -> 1 })
            bot.sendInteractionText(actor, group, e.message, id, interactionEvent = true)
        }
    }
}
