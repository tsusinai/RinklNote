package com.example.rinklnote.server.services

/**
 * 多通道 bot 的共享指令常量：推送开关正则、登录码正则、通道（source）标识。
 *
 * 从 QQMessageProcessor 提取而来（B1 通道底座），QQ 处理器改引用本对象，行为零变化；
 * 后续飞书/企业微信/订阅号处理器直接复用，保证各通道指令句式与落库 source 词表一致。
 */
object BotCommands {

    // ── 通道 / source 标识 ──
    // 记账落库走 BillsTable.billSource；推送调度用同一词表标记目标通道。
    // 新增通道时只在此追加，路由与调度逻辑按常量分支。
    const val SOURCE_QQ = "QQ"
    const val SOURCE_FEISHU = "FEISHU"
    const val SOURCE_WECOM = "WECOM"
    const val SOURCE_MP = "MP"
    const val SOURCE_AI = "AI"

    // ── 推送开关指令 ──
    // 「每日推送」指令：在 bot 会话里直接开/关/查日报主动推送（写的就是 App「我的」页那个字段）。
    // 只认这三种句式——单独的「推送」两字不算，避免把「外卖推送20元」这类记账消息误当指令。
    val PUSH_ON = Regex("开启(每日|日报|主动)?推送")
    val PUSH_OFF = Regex("(关闭|取消|停止|停用)(每日|日报|主动)?推送")
    val PUSH_STATUS = Regex("(每日|日报)?推送(状态|设置)|查询(每日|日报)?推送")

    /** 命中任一推送开关指令（供处理器短路判断）。 */
    fun isPushCommand(text: String): Boolean =
        PUSH_ON.containsMatchIn(text) || PUSH_OFF.containsMatchIn(text) || PUSH_STATUS.containsMatchIn(text)

    // ── 登录码指令 ──
    // 用户发「登录」类消息 → 机器人下发一次性登录码供网页/App 端换取登录。
    val LOGIN_CODE = Regex("登录|登录码|验证码|网页登录|扫码", RegexOption.IGNORE_CASE)
}
