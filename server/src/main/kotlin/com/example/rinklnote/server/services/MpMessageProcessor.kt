package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.NLUService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * 个人订阅号消息处理器（C-W2）——照 WecomMessageProcessor 的管线结构：
 * 内容归一化 → 事件去重 → 自动开户 → 指令短路 → 自然语言路由 → 产出回复文本。
 *
 * 与企微处理器的关键差异（调研文档第 4 节）：
 *  - 身份用 `FromUserName`（即 openid）走 `wechat_openid` 三件套自动开户；
 *  - **只收不推**：订阅号无任何主动推送能力（客服/模板/订阅通知均需认证服务号），
 *    推送目标选择里订阅号已被排除（`UserService.findAllPushUsers` 只看飞书/企微/QQ，B1 已保证），
 *    因此「开启每日推送」类指令只能按「不支持」回复；
 *  - voice 消息取 `Recognition` 字段（后台开启「接收语音识别结果」后平台免费转写）进管线——
 *    三通道里唯一免费拿平台语音转写的通道；
 *  - **5 秒硬窗口**：微信服务器 5s 收不到响应会断连重试（共 3 次），超时用户看到
 *    「该公众号暂时无法提供服务」——整个处理管线包 4s 硬超时（预留 1s 组包），
 *    超时返回固定兜底文案，绝不超过被动回复窗口。
 */
object MpMessageProcessor {
    private val logger = LoggerFactory.getLogger("MpMessageProcessor")

    /**
     * 被动回复硬超时：微信 5s 窗口预留 1s 给组包/传输（调研文档 4.6 LLM 兜底 4s 硬超时策略）。
     * 与 WecomMessageProcessor.REPLY_DEADLINE_MS 同值同因。
     */
    internal const val REPLY_DEADLINE_MS = 4_000L

    /** 超时/异常兜底文案：账单可能已落库，不能说失败，引导去 App 里看结果。 */
    internal const val TIMEOUT_REPLY = "这条消息有点复杂，我先记着，稍后到 App 里看看结果～"

    /**
     * 处理一条订阅号回调消息，返回**回复文本**（null = 不回复，路由层回空串）。
     * [fields] 为（解密后的）消息 XML 键值对（FromUserName / MsgType / Content / MsgId / Recognition …）。
     */
    suspend fun process(
        fields: Map<String, String>,
        userService: UserService,
        billService: BillService,
        nluService: NLUService,
        budgetService: BudgetService,
        insightService: InsightService,
        // 超时可注入：单测传 0 直接命中兜底分支（withTimeoutOrNull(0) 不执行块立即超时），
        // 生产固定 [REPLY_DEADLINE_MS]
        replyTimeoutMs: Long = REPLY_DEADLINE_MS
    ): String? {
        val openid = fields["FromUserName"]
        val msgId = fields["MsgId"]
        if (openid.isNullOrBlank() || msgId.isNullOrBlank()) {
            // 事件类回调（subscribe 等，无 MsgId）或字段异常：不处理不回复
            logger.info("订阅号消息丢弃：缺少 FromUserName/MsgId（msgtype=${fields["MsgType"]}）")
            return null
        }

        // 去重（照 FeishuMessageProcessor.isFirstEvent 模式）：复用 QQ 的 webhook_events 表 +
        // SHA-256 摘要判重，一张台账服务所有通道。键加 "mp_" 前缀与 QQ/飞书/企微隔离。
        // 重试语义：微信 5s 未收到响应会重试重推（最多 3 次），首次已处理并回复的情况下
        // 重试在这里被挡掉（回空串，微信不再展示）；若无此去重，重试会导致同一笔账重复落库。
        if (!isFirstEvent("mp_$msgId")) {
            logger.info("订阅号重复消息忽略：$msgId")
            return null
        }

        // 整个管线包 4s 硬超时：被动回复在 HTTP 响应体里，超时兜底文案保证 5s 窗口不被击穿
        // （LLM 兜底查询默认 10s 超时，必须由这层硬顶下来）。广捕获放在超时块外。
        return try {
            withTimeoutOrNull(replyTimeoutMs) {
                handle(fields, openid, userService, billService, nluService, budgetService, insightService)
            } ?: TIMEOUT_REPLY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("订阅号消息处理异常", e)
            TIMEOUT_REPLY
        }
    }

    private suspend fun handle(
        fields: Map<String, String>,
        openid: String,
        userService: UserService,
        billService: BillService,
        nluService: NLUService,
        budgetService: BudgetService,
        insightService: InsightService
    ): String {
        val msgType = fields["MsgType"] ?: ""

        // 文本取 Content；语音取 Recognition（免费平台转写）；其他类型提示
        val content = when (msgType) {
            "text" -> fields["Content"]
            "voice" -> fields["Recognition"]
            else -> null
        }
        if (content.isNullOrBlank()) {
            logger.info("订阅号消息降级提示：msgtype=$msgType openid=...${openid.takeLast(6)}")
            return if (msgType == "voice") {
                "没听清语音内容，再说一遍或直接打字试试～"
            } else {
                "目前支持文字和语音消息哦～ 打字说「午餐20元」或按住说话就能记账"
            }
        }

        logger.info("订阅号消息：msgtype=$msgType openid=...${openid.takeLast(6)} content=$content")

        // 自动开户：openid 即账号，无需手机号/App 前置（同 QQ / 飞书 / 企微三件套模式）
        val existing = userService.findByWechatOpenid(openid)
        val isNew = existing == null
        val user = existing ?: userService.createByWechatOpenid(openid)
        logger.info("订阅号用户解析：id=${user.id} new=$isNew")

        // 「每日推送」指令短路（必须先于问账路由，否则会被 PhoneIntentRouter 当记账/闲聊吞掉）。
        // 订阅号无主动推送能力（调研文档 4.6：推送类指令只能回「订阅号不支持推送」），
        // 只回提示不改状态——改了状态推送也送不到（findAllPushUsers 已排除仅订阅号用户）。
        if (BotCommands.isPushCommand(content)) {
            return "订阅号不支持主动推送，消息只在我回复你时送到～ 开启每日推送请到 App「我的」页设置。"
        }

        // 「登录码」指令：绑定发码对订阅号属可选项（调研文档 5 对照表 W2 列），本期未实现
        if (BotCommands.LOGIN_CODE.containsMatchIn(content)) {
            return "订阅号暂不支持发绑定码～ 绑定请用 App/Web 的其他通道（QQ / 飞书 / 企业微信）。"
        }

        // 「改金额/改分类/删上一笔/撤销」最近一单修正（Task 1.1）：四通道共用 BotCorrectService。
        BotCorrectService.handle(content, user.id, billService)?.let { return it }

        // 自然语言记账/问账：source=MP 一路透传到 BillService.createBill（bills.bill_source）
        val router = PhoneIntentRouter(billService, budgetService, insightService, nluService)
        val reply = router.route(content, user.id, BotCommands.SOURCE_MP)
        return if (isNew) {
            "欢迎！已为你开通订阅号记账账号。直接说「午餐20元」即可记账。\n\n$reply"
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
