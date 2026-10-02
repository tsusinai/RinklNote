package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.ChallengeService
import com.example.rinklnote.server.services.TimeUtil
import com.example.rinklnote.server.services.UserService
import io.ktor.util.logging.Logger

/**
 * 阶段 3 · 小盘主动照料：晨报 / 暮检补记 / 预算亮灯 / 邮件入账通知（全聚合、零备注外泄）。
 *
 * - [morningReport] / [eveningNudge] 由 PushScheduler 按 user+type+day 去重调用；
 *   未到配置时刻 / 开关关闭 / 无内容时返回 null 即不推。开关与时刻读 bot_config KV：
 *   butler_morning_enabled（默认 true）、butler_morning_time（默认 "08:30"）、
 *   butler_evening_enabled（默认 true）、butler_evening_time（默认 "21:30"）。
 * - [budgetLight]：QQ 通道每笔落账后回调（ButlerIntentRouter.budgetLightHook 接线），
 *   分类预算占比跨过 85% / 100% 时返回告警文案（跨沿触发，同月同档只告一次）。
 * - [mailBookedNotice]：邮件账单入账后向绑定通道推一条确认消息。
 *
 * TODO(阶段3-子代理C)：实现四类文案 + KV 配置 + 跨沿去重与单测（见派工契约）。
 * 文案口吻遵循 docs/小盘语气指南.md（MascotVoice 同款锚点纪律，新文案落在本文件内，勿改 MascotVoice）。
 */
class ButlerCareService(
    private val billService: BillService,
    private val budgetService: BudgetService,
    private val challengeService: ChallengeService,
    private val userService: UserService,
    private val log: Logger,
    /** 可注入时钟（测试固定到目标时刻）；业务时区 Asia/Shanghai。 */
    private val clock: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now(TimeUtil.BOOKKEEPING_ZONE) }
) {

    /**
     * 通道发送器（Application.kt 装配后才有值）：按 飞书 > QQ 选用户已绑定通道发送。
     * null 时 [mailBookedNotice] 只记日志不发送。
     */
    var channelSender: (suspend (userId: Long, content: String) -> Boolean)? = null

    /** 晨报（昨日小结 + 今日预算可用 + 无消费/挑战状态）；无内容返回 null 不推。 */
    suspend fun morningReport(userId: Long): String? {
        // TODO(阶段3)
        return null
    }

    /** 暮检（当天有账 → 简短确认；没账 → 补记关怀；零消费 → 恭喜打卡）；无内容返回 null 不推。 */
    suspend fun eveningNudge(userId: Long): String? {
        // TODO(阶段3)
        return null
    }

    /** 预算亮灯：返回追加到记账回执的告警文案；未跨沿/无分类预算返回 null。 */
    suspend fun budgetLight(categoryName: String, amountMinor: Long, userId: Long): String? {
        // TODO(阶段3)
        return null
    }

    /** 邮件账单入账通知（Application.kt 已把 MailIngestService.onBooked 接到这里）。 */
    suspend fun mailBookedNotice(userId: Long, merchant: String, amountMinor: Long, categoryName: String) {
        // TODO(阶段3)
    }
}
