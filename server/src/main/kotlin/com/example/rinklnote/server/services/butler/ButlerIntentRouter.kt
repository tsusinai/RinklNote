package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.Money
import java.time.LocalDate
import java.time.ZoneId

/**
 * 管家意图调度中枢（2026-10-03）。
 *
 * 在 QQMessageProcessor 既有链路（推送开关 → 登录码 → 最近一单修正）之后、
 * PhoneIntentRouter 自然语言路由之前接管多模态与管家专属意图；返回 null 表示
 * 无人认领，处理器照旧落回 PhoneIntentRouter（既有行为零变化）。
 *
 * 分发优先级：
 *  1. 纯图消息（无文本 + 图片附件）→ [ReceiptOcrService] 识图入账（阶段 1）
 *  2. 语音无平台转写 → [VoiceTranscribeService] Whisper 兜底转写，转写文本继续往下走（阶段 1）
 *  3. 群聊 AA 分账 → [GroupSplitService]（阶段 2）
 *  4. 多行/批量记账与待确认清单 → [BatchBookingService]（阶段 2）
 *  5. 「今天没花钱」打卡 → 本路由内置（陪伴层语义，全聚合、无 LLM）
 *  6. 富查账（上周分类/花最多的一天/日均可用）→ [RichQueryService]（阶段 1）
 *  7. 其余 → null 落回 PhoneIntentRouter（记账 / 既有查询 / LLM 兜底）
 *
 * 隐私红线：本路由及下游只允许「分类 + 金额 + 日期」聚合进 LLM；识图是用户主动发来的
 * 自己的票据图片（即功能本身），但识别结果落库时同样只存结构化字段，图片不落盘不进日志。
 */
class ButlerIntentRouter(
    private val billService: BillService,
    private val budgetService: BudgetService,
    private val voiceFallback: VoiceTranscribeService,
    private val receiptOcr: ReceiptOcrService,
    private val richQuery: RichQueryService,
    private val groupSplit: GroupSplitService,
    private val batchBooking: BatchBookingService
) {

    /** 预算亮灯钩子（阶段 3）：QQ 通道每笔落账后回调，返回追加到回执尾部的告警文案（null 不追加）。 */
    var budgetLightHook: (suspend (userId: Long, categoryName: String, amountMinor: Long) -> String?)? = null

    suspend fun dispatch(inc: ButlerIncoming): String? {
        // 1. 纯图消息：支付截图 / 小票 → 识图入账
        if (inc.text.isNullOrBlank() && inc.imageUrl != null) {
            return receiptOcr.tryBook(inc)
        }

        var text = inc.text?.trim()

        // 2. 语音兜底：平台没给 asr_refer_text 时拉音频走 Whisper
        if (text.isNullOrBlank() && inc.voiceUrl != null) {
            text = voiceFallback.transcribeUrl(inc.voiceUrl)?.trim()
            if (text.isNullOrBlank()) return "这条语音没听清，再说一遍或者直接打字给我吧～"
        }
        if (text.isNullOrBlank()) return null

        // 3. 群聊 AA 分账（阶段 2）
        groupSplit.handle(inc, text)?.let { return it }

        // 4. 批量/多行记账与待确认清单（阶段 2）
        batchBooking.handle(inc, text)?.let { return it }

        // 5. 「今天没花钱」打卡：陪伴层语义，纯聚合统计
        if (NO_SPEND_TODAY.containsMatchIn(text) && text.length <= 12) {
            return noSpendToday(inc.userId)
        }

        // 6. 富查账（阶段 1）：上周/本周分类、最花钱的一天、日均可用
        richQuery.tryAnswer(text, inc.userId)?.let { return it }

        // 7. 落回既有路由
        return null
    }

    /** 「今天没花钱」打卡：报当天零支出 + 本月无消费天数（全聚合，本地计算）。 */
    private fun noSpendToday(userId: Long): String {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.now(shanghai)
        val todayStart = today.atStartOfDay(shanghai).toInstant().toEpochMilli()
        val monthStart = today.withDayOfMonth(1).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val bills = billService.allBills(userId).filter { !it.deleted && it.billType == "EXPENSE" }
        val todaySpent = bills.filter { it.date >= todayStart }.sumOf { it.amountMinor }
        if (todaySpent > 0) {
            return "今天其实已经花了 ¥${Money.format(todaySpent)} 哦，小盘都记着呢，别想蒙混过关～"
        }
        val noSpendDays = bills.asSequence()
            .map { it.date }
            .distinct()
            .filter { it >= monthStart }
            .map { LocalDate.ofInstant(java.time.Instant.ofEpochMilli(it), shanghai).toEpochDay() }
            .toSet()
        val daysInMonthSoFar = today.dayOfMonth
        val cleanDays = daysInMonthSoFar - noSpendDays.size
        return "🎉 好样的！今天 ¥0 消费打卡成功～ 这个月已经攒下 $cleanDays 个零消费日，小盘给你记着大功！"
    }

    private companion object {
        val NO_SPEND_TODAY = Regex("今天没花钱|今天没消费|今天零消费|没花钱打卡|零消费打卡")
    }
}
