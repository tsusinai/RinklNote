package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BotCommands
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.nlu.NLUService
import com.example.rinklnote.server.services.nlu.RuleBasedParser

/**
 * 阶段 2 · 批量/多行记账（转发消息识别的落地形态）：QQ 官方平台拿不到合并转发的节点树，
 * 但转发/粘贴的文本会以多行正文到达。这里把多行文本逐行提取「分类 + 金额」，
 * ≥2 笔候选时回复编号清单并挂起待确认状态（[ButlerSession]，10 分钟 TTL），
 * 用户回「确认」全部入账、「取消」丢弃、「去掉2」剔除第 2 笔后再确认。
 *
 * 与正常记账路由的边界：**单行文本一律返回 null 不抢**（单笔记账交给既有链路）；
 * 待确认态下收到非确认/取消/剔除的其它文本也不拦截 —— 丢弃旧 pending 后走新批量识别，
 * 仍不命中就返回 null 落回正常路由，绝不吞掉用户新发的记账消息。
 *
 * 本服务全规则实现（行内分类走 [RuleBasedParser]），无任何网络调用。
 */
class BatchBookingService(
    private val billService: BillService,
    private val nluService: NLUService,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    private val session = ButlerSession()

    /** 命中批量记账场景（多行候选 / 待确认状态指令）返回文案；否则 null 落回普通路由。 */
    suspend fun handle(inc: ButlerIncoming, text: String): String? {
        // ── 先查待确认态：确认 / 取消 / 去掉N 三类指令；其它文本不拦截 ──
        val pending = session.get(inc.userId, nowMillis())
        if (pending != null) {
            if (CONFIRM.containsMatchIn(text)) {
                return confirmAll(inc.userId, pending)
            }
            if (CANCEL.containsMatchIn(text)) {
                session.clear(inc.userId)
                return "好嘞，这批先不入账～"
            }
            REMOVE_ITEM.find(text)?.let { m ->
                return removeItemReply(inc.userId, pending, m.groupValues[1].toIntOrNull() ?: 0)
            }
            // 其它文本（如新发的单笔记账）：丢弃旧 pending，直接走下方新批量识别；
            // 若识别也不命中则返回 null 落回正常路由 —— 旧 pending 绝不挡新消息。
            session.clear(inc.userId)
        }

        // ── 新批量识别：含换行且候选 ≥ 2 笔才接管，单行 / 单候选一律不抢 ──
        if (!text.contains('\n')) return null
        val candidates = extractCandidates(text)
        if (candidates.size < 2) return null
        session.put(
            inc.userId,
            ButlerSession.PendingBatch(candidates, nowMillis() + ButlerSession.TTL_MILLIS)
        )
        return renderPendingList("识别到 ${candidates.size} 笔待入账：", candidates)
    }

    // ── 待确认态指令 ──

    /** 「确认 / 全部入账 / 都记上」：逐条落库，某条异常跳过并标注，末尾汇总。 */
    private fun confirmAll(userId: Long, batch: ButlerSession.PendingBatch): String {
        val lines = mutableListOf<String>()
        var okCount = 0
        var okSum = 0L
        batch.items.forEachIndexed { index, item ->
            try {
                val bill = billService.createBill(
                    userId = userId,
                    amountMinor = item.amountMinor,
                    categoryName = item.categoryName,
                    remark = item.line.take(50),
                    source = BotCommands.SOURCE_QQ
                )
                lines += "✅ ${bill.categoryName} ¥${Money.format(bill.amountMinor)}"
                okCount++
                okSum += bill.amountMinor
            } catch (e: Exception) {
                lines += "⚠️ 第${index + 1}笔「${item.line.take(20)}」没记上，已跳过"
            }
        }
        session.clear(userId)
        lines += "共 $okCount 笔 ¥${Money.format(okSum)}"
        return lines.joinToString("\n")
    }

    /** 「去掉N / 移除N」：剔除第 N 笔后回更新清单；剔空自动取消。 */
    private fun removeItemReply(userId: Long, batch: ButlerSession.PendingBatch, index: Int): String {
        if (index !in 1..batch.items.size) {
            return renderPendingList("清单里没有第 $index 笔哦～ 当前共 ${batch.items.size} 笔：", batch.items)
        }
        val updated = session.removeItem(userId, index)
            ?: return "好嘞，都剔完了，这批先不入账～"
        return renderPendingList("已剔除第 $index 笔，还剩 ${updated.items.size} 笔：", updated.items)
    }

    // ── 多行提取 ──

    /**
     * 按 `\n` 拆行（最多 10 行）逐行提取候选：金额优先「N元/N块」，否则该行最后一个数字
     * （≤ 0 或换算溢出跳过）；分类走 [RuleBasedParser] 规则词表；命令行（确认/取消/帮助）跳过。
     */
    fun extractCandidates(text: String): List<ButlerSession.PendingItem> {
        val parser = RuleBasedParser()
        val items = mutableListOf<ButlerSession.PendingItem>()
        for (raw in text.split('\n').take(MAX_LINES)) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (SKIP_LINE.containsMatchIn(line)) continue
            val amountMinor = extractLineAmountMinor(line) ?: continue
            items += ButlerSession.PendingItem(line, amountMinor, parser.parse(line, emptyList()))
        }
        return items
    }

    /** 单行金额（分）：优先「N元/N块」，否则该行最后一个数字；无数字 / ≤0 / 溢出返回 null。 */
    private fun extractLineAmountMinor(line: String): Long? {
        val numText = YUAN_AMOUNT.find(line)?.groupValues?.get(1)
            ?: NUMBER.findAll(line).lastOrNull()?.value
            ?: return null
        val yuan = numText.toDoubleOrNull() ?: return null
        val minor = runCatching { Money.toMinor(yuan) }.getOrNull() ?: return null
        return minor.takeIf { it > 0 }
    }

    /** 待确认清单回执：编号行 + 操作提示尾注。 */
    private fun renderPendingList(header: String?, items: List<ButlerSession.PendingItem>): String {
        val lines = items.mapIndexed { i, item ->
            "${i + 1}. ${item.categoryName ?: "其他"} ¥${Money.format(item.amountMinor)}"
        }
        return (listOfNotNull(header) + lines).joinToString("\n") +
            "\n回复「确认」全部入账；「去掉1」剔除某笔；「取消」放弃"
    }

    private companion object {
        /** 最多识别前 10 行，防超长转发刷屏。 */
        const val MAX_LINES = 10

        val CONFIRM = Regex("确认|全部入账|都记上")
        val CANCEL = Regex("取消|不要了|算了")
        /** 「去掉2 / 去2 / 移除2」：去 + 可选「掉」+ 序号。 */
        val REMOVE_ITEM = Regex("(?:去掉?|移除)\\s*(\\d+)")
        val SKIP_LINE = Regex("确认|取消|帮助")
        val YUAN_AMOUNT = Regex("(\\d+\\.?\\d*)\\s*[元块]")
        val NUMBER = Regex("\\d+\\.?\\d*")
    }
}
