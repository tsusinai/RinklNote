package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.NLUService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * 企业微信智能机器人消息处理器（C-W1）——照 FeishuMessageProcessor 的管线结构：
 * 内容归一化 → 事件去重 → 自动开户 → 指令短路 → 自然语言路由 → 产出回复文本。
 *
 * 与飞书处理器的关键差异：
 *  - 输入是「解密后的内层回调 XML 字段表」（WecomBotWebhookRoutes 已完成验签/解密），
 *    身份取 `FromUserName`（企业内 userid，见调研文档 3.3；微信系标准回调结构）；
 *  - **同步被动回复**：本函数返回回复文本，由路由层加密后直接写进 HTTP 响应体——
 *    不像 QQ / 飞书那样「立即 ACK + 异步发送」，处理耗时直接消耗企微的 5 秒回复窗口，
 *    所以整个管线包 4s 硬超时（预留 1s 给验签/解密/加密组包与网络传输，与订阅号同策略）；
 *  - 落库 source = BotCommands.SOURCE_WECOM（"WECOM"）；
 *  - 企微回调平台侧是否给语音转写待冒烟核对（调研文档 6 选型矩阵「待核」），本期只接文本，
 *    其他 msgtype 回「暂支持文字」类提示。
 */
object WecomMessageProcessor {
    private val logger = LoggerFactory.getLogger("WecomMessageProcessor")

    /**
     * 被动回复硬超时：企微回调须在 5 秒内返回被动回复（调研文档 3.4），
     * 管线 4s 封顶、预留 1s 组包/传输。与 MpMessageProcessor.REPLY_DEADLINE_MS 同值同因。
     */
    internal const val REPLY_DEADLINE_MS = 4_000L

    /** 超时/异常兜底文案：账单可能已落库，不能说失败，引导去 App 里看结果。 */
    internal const val TIMEOUT_REPLY = "这条消息有点复杂，我先记着，稍后到 App 里看看结果～"

    /**
     * 处理一条企微回调消息，返回**回复文本**（null = 不回复，路由层回空串）。
     * [fields] 为解密后的内层 XML 键值对（FromUserName / MsgType / Content / MsgId …）。
     */
    suspend fun process(
        fields: Map<String, String>,
        wecomBotService: WecomBotService,
        userService: UserService,
        billService: BillService,
        nluService: NLUService,
        budgetService: BudgetService,
        insightService: InsightService,
        // 超时可注入：单测传 0 直接命中兜底分支（withTimeoutOrNull(0) 不执行块立即超时），
        // 生产固定 [REPLY_DEADLINE_MS]
        replyTimeoutMs: Long = REPLY_DEADLINE_MS
    ): String? {
        val userid = fields["FromUserName"]
        val msgId = fields["MsgId"]
        if (userid.isNullOrBlank() || msgId.isNullOrBlank()) {
            // 事件类回调（无 MsgId）或字段异常：不处理不回复
            logger.info("企微消息丢弃：缺少 FromUserName/MsgId")
            return null
        }

        // 去重（照 FeishuMessageProcessor.isFirstEvent 模式）：复用 QQ 的 webhook_events 表 +
        // SHA-256 摘要判重，一张台账服务所有通道。键加 "wecom_" 前缀与 QQ/飞书/订阅号隔离。
        // 微信系 5s 未收到响应会重试重推（企微同机制），去重挡住重试导致的重复落库；
        // 重试语义：首次已处理并回复，重试直接回空串（企微侧不再展示）。
        if (!isFirstEvent("wecom_$msgId")) {
            logger.info("企微重复消息忽略：$msgId")
            return null
        }

        // 整个管线包 4s 硬超时：被动回复在 HTTP 响应体里，超时兜底文案保证 5s 窗口不被击穿。
        // 广捕获放在超时块外（块内不能吞 CancellationException，否则超时失效）；取消异常原样上抛。
        return try {
            withTimeoutOrNull(replyTimeoutMs) {
                handle(fields, userid, wecomBotService, userService, billService, nluService, budgetService, insightService)
            } ?: TIMEOUT_REPLY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("企微消息处理异常", e)
            TIMEOUT_REPLY
        }
    }

    private suspend fun handle(
        fields: Map<String, String>,
        userid: String,
        wecomBotService: WecomBotService,
        userService: UserService,
        billService: BillService,
        nluService: NLUService,
        budgetService: BudgetService,
        insightService: InsightService
    ): String {
        val msgType = fields["MsgType"] ?: ""

        // 只接文本（语音/图片等其他类型给「暂支持文字」提示；平台语音转写能力待冒烟核对）
        val content = if (msgType == "text") fields["Content"] else null
        if (content.isNullOrBlank()) {
            logger.info("企微消息降级提示：msgtype=$msgType userid=...${userid.takeLast(6)}")
            return "目前只支持文字消息哦～ 语音/图片记账请到 App 里操作，打字说「午餐20元」我就能记"
        }

        logger.info("企微消息：userid=...${userid.takeLast(6)} content=$content")

        // 自动开户：userid 即账号，无需手机号/App 前置（同 QQ / 飞书三件套模式）
        val existing = userService.findByWecomUserid(userid)
        val isNew = existing == null
        val user = existing ?: userService.createByWecomUserid(userid)
        logger.info("企微用户解析：id=${user.id} new=$isNew")

        // 「每日推送」指令短路（必须先于问账路由，否则会被 PhoneIntentRouter 当记账/闲聊吞掉——
        // QQ 用户 9/9 的教训）。入口统一走 BotCommands.isPushCommand。
        // 企微能推，但推送走「消息推送」群机器人 webhook（群维度非单聊），文案里说明去向。
        if (BotCommands.isPushCommand(content)) {
            val current = userService.findById(user.id)
            val hour = current?.dailyReportHour ?: 9
            val minute = current?.dailyReportMinute ?: 0
            return when {
                BotCommands.PUSH_OFF.containsMatchIn(content) -> {
                    userService.setDailyReport(user.id, enabled = false, hour = hour, minute = minute)
                    "✅ 已关闭每日推送。想再开启时发「开启每日推送」即可。"
                }
                BotCommands.PUSH_ON.containsMatchIn(content) -> {
                    userService.setDailyReport(user.id, enabled = true, hour = hour, minute = minute)
                    "✅ 已开启每日推送：每天 %02d:%02d 推送当日账单总结（当天没记账则不推）。调整时间请在 App「我的」页设置。\n推送会发到企微「消息推送」配置的群机器人会话（群维度，暂不支持按人单聊）。".format(hour, minute)
                }
                else -> "📋 每日推送当前状态：${if (current?.dailyReportEnabled == true) "已开启" else "未开启"}，时间 %02d:%02d。\n发「开启每日推送」或「关闭每日推送」即可切换。".format(hour, minute)
            }
        }

        // 「登录码」指令：下发一次性绑定码（内存 map 5 分钟过期），供 App/Web 管理端绑定企微身份
        // （消费入口随 Phase D 绑定迁移落地）
        if (BotCommands.LOGIN_CODE.containsMatchIn(content)) {
            val code = wecomBotService.createBindCode(userid)
            return "绑定码：$code（5 分钟内有效）\n在网页/App 的「企微绑定」处输入此码，即可把企业微信绑到你的记账账号。"
        }

        // 自然语言记账/问账：source=WECOM 一路透传到 BillService.createBill（bills.bill_source）
        val router = PhoneIntentRouter(billService, budgetService, insightService, nluService)
        val reply = router.route(content, user.id, BotCommands.SOURCE_WECOM)
        return if (isNew) {
            "欢迎！已为你开通企微记账账号。直接说「午餐20元」即可记账；回复「登录」可获取绑定码。\n\n$reply"
        } else {
            reply
        }
    }

    /**
     * 幂等去重（照 FeishuMessageProcessor.isFirstEvent 模式）：SHA-256 摘要后插 webhook_events 表，
     * 主键冲突判重（并发投递只有一个 insert 赢），插入成功顺手清理 3 天前旧记录。
     */
    fun isFirstEvent(eventId: String): Boolean = QQMessageProcessor.isFirstEvent(eventId)
}
