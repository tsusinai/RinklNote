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

    // ── 最近一单修正指令（2026-09-18 Task 1.1，四通道共用 BotCorrectService）──
    // 「改金额30 / 改金额改成30 / 金额改成30 / 金额改为30」：把最近一单金额改成指定值（元）。
    // 金额分组可空（空 = 用户没给数字，回执引导格式）；中文数字交给正常记账链路。
    val EDIT_AMOUNT = Regex("(?:改金额|金额改成|金额改为)\\s*(?:改成?\\s*)?([0-9]+(?:\\.[0-9]+)?)?\\s*元?")
    // 「改分类 交通 / 改分类改成交通 / 改分类为交通」：把最近一单分类改成指定分类名。
    val EDIT_CATEGORY = Regex("改分类\\s*(?:改成?|为|到)?\\s*([一-龥A-Za-z0-9]{1,12})")
    // 「删上一笔 / 删除上一笔 / 删掉上一笔 / 撤销」：软删最近一单。与 PhoneIntentRouter 的
    // 泛化删除意图不同：本指令由 BotCorrectService 在四通道处理器里**先**短路执行，
    // 只针对「最新一笔未删账单」，不做模糊匹配。
    val DELETE_LAST = Regex("删(?:除|掉)?上一笔|撤销|撤回")

    /** 命中任一最近一单修正指令（供处理器短路判断）。 */
    fun isLastBillCorrection(text: String): Boolean =
        EDIT_AMOUNT.containsMatchIn(text) || EDIT_CATEGORY.containsMatchIn(text) || DELETE_LAST.containsMatchIn(text)
}
