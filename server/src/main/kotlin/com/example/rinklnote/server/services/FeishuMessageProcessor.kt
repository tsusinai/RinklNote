package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.NLUService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * 飞书 bot 消息处理器（B2）——照 QQMessageProcessor 的管线结构：
 * 内容归一化 → 事件去重 → 自动开户 → 指令短路 → 自然语言路由 → 回复。
 *
 * 输入是 `im.message.receive_v1` 事件的 `event` 对象（webhook 路由已完成验签/解密），形状：
 * ```
 * { "sender": { "sender_id": { "open_id": "ou_..." } },
 *   "message": { "message_id": "om_...", "chat_type": "p2p"|"group",
 *                "message_type": "text", "content": "{\"text\":\"午餐20元\"}",
 *                "mentions": [ { "key": "@_user_1", "id": {...}, "name": "..." } ] } }
 * ```
 * 与 QQ 的差异点：
 *  - `message.content` 是「JSON 字符串」，先解析再取 `text`；
 *  - 群聊 mention 占位符形如 `@_user_1`（mentions[].key），从文本中剥离；
 *  - v1 事件只接文本，语音/图片等直接丢弃（平台无语音转写下发，见调研文档 2.6）；
 *  - 落库 source = BotCommands.SOURCE_FEISHU（"FEISHU"）。
 */
object FeishuMessageProcessor {
    private val logger = LoggerFactory.getLogger("FeishuMessageProcessor")

    /** 群聊 mention 占位符：如 @_user_1（mentions[].key）。 */
    private val MENTION_PLACEHOLDER = Regex("""@_user_\d+""")

    suspend fun process(
        event: JsonObject,
        feishuBotService: FeishuBotService,
        userService: UserService,
        billService: BillService,
        nluService: NLUService,
        budgetService: BudgetService,
        insightService: InsightService
    ) {
        try {
            val message = event["message"]?.jsonObject
            val senderOpenId = event["sender"]?.jsonObject
                ?.get("sender_id")?.jsonObject
                ?.get("open_id")?.jsonPrimitive?.contentOrNull
            if (message == null || senderOpenId.isNullOrBlank()) {
                logger.info("飞书事件丢弃：缺少 message/sender.open_id")
                return
            }
            val messageId = message["message_id"]?.jsonPrimitive?.contentOrNull ?: return
            val chatType = message["chat_type"]?.jsonPrimitive?.contentOrNull ?: "p2p"
            val messageType = message["message_type"]?.jsonPrimitive?.contentOrNull

            // 去重：message_id 全局唯一（天然去重键）。复用 QQ 的 webhook_events + SHA-256 判重
            // （QQMessageProcessor.isFirstEvent），同一张去重台账服务所有通道。
            if (!isFirstEvent("$messageId")) {
                logger.info("飞书重复事件忽略：$messageId")
                return
            }

            // v1 事件只接文本（语音拿到的是音频文件 key，B2 不接，见调研文档 2.6）
            if (messageType != "text") {
                logger.info("飞书事件丢弃：非文本消息 t=$messageType id=$messageId")
                return
            }

            // content 是 JSON 字符串（如 {"text":"午餐20元"}），先解析再取 text
            val contentJson = message["content"]?.jsonPrimitive?.contentOrNull
            var content = try {
                Json.parseToJsonElement(contentJson ?: "").jsonObject["text"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) {
                null
            }
            if (content.isNullOrBlank()) {
                logger.info("飞书事件丢弃：content 解析不到 text，id=$messageId")
                return
            }

            // 群聊：官方语义是「机器人被 @ 时才推送 im.message.receive_v1」——事件到达即视为 @ 本机，
            // 这里做防御性校验（mentions 为空直接丢弃，实施时对照官方文档核对口径）；
            // mention 占位符（@_user_1）从文本剥离，避免污染 NLU 解析。
            if (chatType == "group") {
                val mentions = message["mentions"]?.jsonArray
                if (mentions == null || mentions.isEmpty()) {
                    logger.info("飞书群消息丢弃：无 mention（id=$messageId）")
                    return
                }
                content = content.replace(MENTION_PLACEHOLDER, " ").trim()
                if (content.isBlank()) {
                    logger.info("飞书群消息丢弃：剥离 mention 后无正文（id=$messageId）")
                    return
                }
            }

            logger.info("飞书消息：chat=$chatType open_id=...${senderOpenId.takeLast(6)} content=$content")

            // 自动开户：open_id 即账号，无需手机号/App 前置（同 QQ 模式）
            val existing = userService.findByFeishuOpenId(senderOpenId)
            val isNew = existing == null
            val user = existing ?: userService.createByFeishuOpenId(senderOpenId)
            logger.info("飞书用户解析：id=${user.id} new=$isNew")

            // 「每日推送」指令短路（必须先于问账路由，否则会被 PhoneIntentRouter 当记账/闲聊吞掉——
            // QQ 用户 9/9 的教训）。入口统一走 BotCommands.isPushCommand，区分开/关/查再三连判定。
            if (BotCommands.isPushCommand(content)) {
                val current = userService.findById(user.id)
                val hour = current?.dailyReportHour ?: 9
                val minute = current?.dailyReportMinute ?: 0
                val reply = when {
                    BotCommands.PUSH_OFF.containsMatchIn(content) -> {
                        userService.setDailyReport(user.id, enabled = false, hour = hour, minute = minute)
                        "✅ 已关闭每日推送。想再开启时发「开启每日推送」即可。"
                    }
                    BotCommands.PUSH_ON.containsMatchIn(content) -> {
                        userService.setDailyReport(user.id, enabled = true, hour = hour, minute = minute)
                        "✅ 已开启每日推送：每天 %02d:%02d 推送当日账单总结（当天没记账则不推）。调整时间请在 App「我的」页设置。".format(hour, minute)
                    }
                    else -> "📋 每日推送当前状态：${if (current?.dailyReportEnabled == true) "已开启" else "未开启"}，时间 %02d:%02d。\n发「开启每日推送」或「关闭每日推送」即可切换。".format(hour, minute)
                }
                feishuBotService.sendText(senderOpenId, reply, messageId)
                logger.info("飞书推送开关指令 user=${user.id}: $reply")
                return
            }

            // 「登录码」指令：下发一次性绑定码（内存 map 5 分钟过期），供 Web 管理端绑定飞书身份。
            if (BotCommands.LOGIN_CODE.containsMatchIn(content)) {
                val code = feishuBotService.createBindCode(senderOpenId)
                feishuBotService.sendText(
                    senderOpenId,
                    "绑定码：$code（5 分钟内有效）\n在网页/App 的「飞书绑定」处输入此码，即可把飞书绑到你的记账账号。",
                    messageId
                )
                return
            }

            // 自然语言记账/问账：source=FEISHU 一路透传到 BillService.createBill（bills.bill_source）
            val router = PhoneIntentRouter(billService, budgetService, insightService, nluService)
            val reply = router.route(content, user.id, BotCommands.SOURCE_FEISHU)
            val out = if (isNew) {
                "欢迎！已为你开通飞书记账账号。直接说「午餐20元」即可记账；回复「登录」可获取绑定码。\n\n$reply"
            } else {
                reply
            }
            feishuBotService.sendText(senderOpenId, out, messageId)
            logger.info("飞书回复 user=${user.id}: ${reply.replace("\n", " / ")}")
        } catch (e: Exception) {
            logger.error("飞书消息处理异常", e)
        }
    }

    /**
     * 幂等去重（照 QQMessageProcessor.isFirstEvent 模式）：SHA-256 摘要后插 webhook_events 表，
     * 主键冲突判重（并发投递只有一个 insert 赢），插入成功顺手清理 3 天前旧记录。
     */
    fun isFirstEvent(eventId: String): Boolean = QQMessageProcessor.isFirstEvent(eventId)
}
