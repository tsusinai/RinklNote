package com.example.rinklnote.server.services.butler

/**
 * 小盘 QQ 消息处理管家（2026-10-03）—— 入站消息统一载荷。
 *
 * QQMessageProcessor 把 QQ 官方平台事件（webhook 与 WS 网关同构）归一化成 [ButlerIncoming]
 * 交给 ButlerIntentRouter 分发。同一 openid 在 C2C 与群聊是两个不同身份（user_openid /
 * member_openid，平台限制），沿用既有「按身份开户」口径，此处不做合并。
 */
enum class ButlerChatType { PRIVATE, GROUP }

data class ButlerIncoming(
    /** Rinkl 用户 id（openid 已由处理器解析/自动开户）。 */
    val userId: Long,
    /** QQ openid：C2C 为 user_openid，群聊为 member_openid。 */
    val openid: String,
    val chatType: ButlerChatType,
    /** 群聊时非空（group_openid）。 */
    val groupOpenid: String?,
    /** 平台事件 id，被动回复窗口内回执必须携带。 */
    val msgId: String,
    /** 已提取文本（正文 / 平台语音转写）；纯图消息为 null。 */
    val text: String?,
    /** 语音附件直链：平台 asr_refer_text 缺失时的 Whisper 兜底输入。 */
    val voiceUrl: String?,
    /** 图片附件直链：支付截图 / 小票识图输入。 */
    val imageUrl: String?,
    /** 成功落库的具体账单交回消息处理器，供卡片绑定目标。 */
    val onBillCreated: ((com.example.rinklnote.server.services.BillDTO) -> Unit)? = null
) {
    val isGroup: Boolean get() = chatType == ButlerChatType.GROUP
}
