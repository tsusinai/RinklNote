package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BotCommands
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import com.example.rinklnote.server.tables.AaRecordsTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * 阶段 2 · 群聊 AA 分账（2026-10-03）：识别「AA 480 3人」「聚餐平摊 480元」「我们仨均摊 300」类消息，
 * 整数分安全拆分（除不尽余数分给前几人），生成 AA 结算单文案并落 AA 台账（[AaRecordsTable]，催收可查）。
 *
 * 本服务全规则实现、无任何网络调用（隐私红线：不进 LLM）。命中 AA 指令或建单意图返回结算单 /
 * 清单 / 引导文案；未命中返回 null 落回 ButlerIntentRouter 的下一环。
 *
 * 拆分口径：base = 总额 / 人数，余数 r = 总额 % 人数 分给前 r 人每人 +1 分
 * （100分/3人 → [34,33,33]），发起人固定拿第 1 份 —— 余数存在时发起人份额恒 ≥ 1 分，
 * 满足 createBill 的「金额必须大于 0」约束，无需额外兜底。
 */
class GroupSplitService(private val billService: BillService) {

    /** 命中 AA 意图返回结算单文案；未命中返回 null。 */
    suspend fun handle(inc: ButlerIncoming, text: String): String? {
        // ── AA 专属指令：必须先于建单解析，避免「AA记录」等词被误当建单意图 ──
        if (AA_HELP.containsMatchIn(text)) return AA_HELP_TEXT
        if (AA_LIST.containsMatchIn(text)) return listRecords(inc)
        AA_SETTLE.find(text)?.let { m ->
            return settleRecord(inc, m.groupValues[1].toLong())
        }

        // ── 建单：AA 意图词 + 可提取金额，二者缺一不算命中（落回正常路由）──
        if (!AA_INTENT.containsMatchIn(text)) return null
        val totalMinor = extractTotalMinor(text) ?: return null
        if (totalMinor <= 0) return "金额要大于 0 才好分呀～ 比如「AA 480 3人」"
        val headCount = resolveHeadCount(text)
            ?: return "几个人平摊呀？回「AA 480 3人」就行～"
        if (headCount > MAX_HEAD_COUNT) {
            return "人太多啦，小盘一次算不动（$MAX_HEAD_COUNT 人以内）～"
        }

        val topic = resolveTopic(text)
        val splits = splitMinor(totalMinor, headCount)
        val selfShare = splits.first() // 发起人拿第 1 份；有余数时第 1 份恒 ≥ 1 分

        // ① AA 台账落库
        val recordId = transaction {
            AaRecordsTable.insert {
                it[AaRecordsTable.userId] = inc.userId
                it[groupOpenid] = inc.groupOpenid
                it[title] = topic.take(50)
                it[AaRecordsTable.totalMinor] = totalMinor
                it[AaRecordsTable.headCount] = headCount
                it[membersText] = buildMembersText(splits)
                it[createdAt] = System.currentTimeMillis()
            } get AaRecordsTable.id
        }
        // ② 发起人自付份额记账（分类按事由词解析，解析不出由 createBill 落默认分类）
        try {
            billService.createBill(
                userId = inc.userId,
                amountMinor = selfShare,
                categoryName = RuleBasedParser().parse(topic, emptyList()),
                remark = "AA·$topic（自付1/$headCount）",
                source = BotCommands.SOURCE_QQ
            )
        } catch (e: Exception) {
            // 补偿：自付账单没记上，这条 AA 单别留着误导用户（台账行软删）
            transaction {
                AaRecordsTable.update({ AaRecordsTable.id eq recordId }) { it[deleted] = true }
            }
            return "AA 单没记上（服务打了个盹），稍后再试一次吧～"
        }
        return buildReceipt(topic, totalMinor, headCount, splits)
    }

    /**
     * 整数分安全拆分：base = total / n，余数 r = total % n 分给前 r 人每人 +1 分
     * （如 100分/3人 → [34,33,33]）。total ≤ 0 或 n ≤ 0 返回空列表。
     */
    fun splitMinor(totalMinor: Long, headCount: Int): List<Long> {
        if (totalMinor <= 0 || headCount <= 0) return emptyList()
        val base = totalMinor / headCount
        val remainder = (totalMinor % headCount).toInt()
        return List(headCount) { i -> base + if (i < remainder) 1L else 0L }
    }

    // ── 金额 / 人数 / 事由解析 ──

    /**
     * 总额（分）：优先「N元 / N块」；否则取最后一个数字（与 PhoneIntentRouter 的启发一致），
     * 但排除「N人」里的那个数字 —— 否则「AA 480 3人」会把人数 3 当成总额。
     * 没有任何可用数字返回 null（= 无建单意图）；换算走 Money.toMinor（超界返回 null 不抛）。
     */
    private fun extractTotalMinor(text: String): Long? {
        val peopleSpans = DIGIT_PEOPLE.findAll(text).map { it.range }.toList()
        fun toMinorOrNull(numText: String): Long? =
            numText.toDoubleOrNull()?.let { runCatching { Money.toMinor(it) }.getOrNull() }

        YUAN_AMOUNT.find(text)?.groupValues?.get(1)?.let { return toMinorOrNull(it) }
        return NUMBER.findAll(text)
            .filter { m -> peopleSpans.none { span -> m.range.first >= span.first && m.range.last <= span.last } }
            .lastOrNull()?.value?.let { toMinorOrNull(it) }
    }

    /** 人数：N人 数字 → 中文数字人 → 「我们仨」→ 「和/跟/与/、」分隔人名计数（含发起人）。 */
    private fun resolveHeadCount(text: String): Int? {
        DIGIT_PEOPLE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.takeIf { n -> n in 1..MAX_HEAD_COUNT } }
        CHINESE_PEOPLE.find(text)?.groupValues?.get(1)?.firstOrNull()?.let { c -> CHINESE_NUM[c]?.let { return it } }
        if (WO_MEN_SA.containsMatchIn(text)) return 3
        return countByNames(text)
    }

    /**
     * 人名计数兜底：按「和 / 跟 / 与 / 、」切分后数段数（发起人算一段，故 ≥ 2 段才算有效）。
     * 先抹掉金额数字，避免「我、小明、小红 平摊 90元」把 90 当人名干扰。
     * 启发式有误伤可能（如「聚餐和打车AA 480」会被当 2 人），解析不出宁可引导重发。
     */
    private fun countByNames(text: String): Int? {
        if (!NAME_SEPARATORS.containsMatchIn(text)) return null
        val stripped = text.replace(NUMBER, "")
        val parts = stripped.split('和', '跟', '与', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.size in 2..MAX_HEAD_COUNT) parts.size else null
    }

    /** 事由：优先命中词表第一个（聚餐 / 烧烤 / 打车…），否则「聚会」。 */
    private fun resolveTopic(text: String): String =
        TOPIC_WORDS.firstOrNull { text.contains(it, ignoreCase = true) } ?: "聚会"

    /** 成员描述串（每人应付清单，金额为分）；超长（人太多）时退化为摘要，防 varchar(500) 溢出。 */
    private fun buildMembersText(splits: List<Long>): String {
        val joined = splits.mapIndexed { i, share -> "人${i + 1}:$share" }.joinToString(";")
        return if (joined.length <= 500) joined else "共${splits.size}人，每人应付见结算单"
    }

    // ── 结算单 / 台账指令 ──

    /**
     * 结算单回执（群聊私聊通用）。除不尽时「每人应付」选「前 r 人 ¥X，其余 ¥Y」写法
     * （r = 余数人数）：对任意总额/人数组合都精确，测试锁定该格式。
     */
    private fun buildReceipt(topic: String, totalMinor: Long, headCount: Int, splits: List<Long>): String {
        val perLine = if (splits.distinct().size == 1) {
            "每人应付：¥${Money.format(splits.first())}"
        } else {
            val front = splits.count { it == splits.first() }
            "每人应付：前${front}人 ¥${Money.format(splits.first())}，其余 ¥${Money.format(splits.last())}"
        }
        return buildString {
            append("🧾 小盘AA结算单\n")
            append("━━━━━━━━━\n")
            append("事由：").append(topic).append('\n')
            append("总额：¥").append(Money.format(totalMinor)).append("（").append(headCount).append("人）\n")
            append(perLine).append('\n')
            append("━━━━━━━━━\n")
            append("你的那份 ¥").append(Money.format(splits.first())).append(" 已帮记上，记得催小伙伴们转账哦～\n")
            append("回「AA记录」查看历史 AA 单")
        }
    }

    /** 「AA记录」：该用户最近 5 条未删 AA 单；群聊里优先本群（本群没有再看全部）。 */
    private fun listRecords(inc: ButlerIncoming): String {
        data class AaRow(
            val id: Long,
            val groupOpenid: String?,
            val title: String,
            val totalMinor: Long,
            val headCount: Int,
            val settled: Boolean
        )

        val rows = transaction {
            AaRecordsTable.selectAll()
                .where { (AaRecordsTable.userId eq inc.userId) and (AaRecordsTable.deleted eq false) }
                .orderBy(AaRecordsTable.id to SortOrder.DESC)
                .limit(20)
                .map { r ->
                    AaRow(
                        r[AaRecordsTable.id], r[AaRecordsTable.groupOpenid], r[AaRecordsTable.title],
                        r[AaRecordsTable.totalMinor], r[AaRecordsTable.headCount], r[AaRecordsTable.settled]
                    )
                }
        }
        val groupRows = inc.groupOpenid?.let { gid -> rows.filter { it.groupOpenid == gid } }.orEmpty()
        val chosen = if (groupRows.isNotEmpty()) groupRows.take(5) else rows.take(5)
        if (chosen.isEmpty()) return "还没有 AA 单哦～ 发「AA 480 3人」就能发起分摊啦"
        val lines = chosen.map { r ->
            val per = if (r.headCount > 0) Money.format(r.totalMinor / r.headCount) else "0.00"
            "#${r.id} ${r.title} ¥${Money.format(r.totalMinor)} · ${r.headCount}人 · 每人 ¥$per${if (r.settled) " ✅已结清" else ""}"
        }
        return "📋 最近的 AA 单：\n" + lines.joinToString("\n")
    }

    /** 「AA结清 编号」：只动本人记录，标记 settled。 */
    private fun settleRecord(inc: ButlerIncoming, recordId: Long): String {
        val row = transaction {
            AaRecordsTable.selectAll()
                .where {
                    (AaRecordsTable.id eq recordId) and
                        (AaRecordsTable.userId eq inc.userId) and
                        (AaRecordsTable.deleted eq false)
                }
                .limit(1)
                .firstOrNull()
        } ?: return "没找到编号 $recordId 的 AA 单哦～ 回「AA记录」看看编号"
        val title = row[AaRecordsTable.title]
        if (row[AaRecordsTable.settled]) return "AA单「$title」之前就结清啦～"
        transaction {
            AaRecordsTable.update({
                (AaRecordsTable.id eq recordId) and (AaRecordsTable.userId eq inc.userId)
            }) { it[settled] = true }
        }
        return "✅ AA单「$title」已结清"
    }

    private companion object {
        /** AA 意图词（IGNORE_CASE 覆盖 aa / Aa；ａａ 全角单列）。 */
        val AA_INTENT = Regex("AA|ａａ|平摊|均摊|分摊", RegexOption.IGNORE_CASE)
        val AA_HELP = Regex("AA帮助", RegexOption.IGNORE_CASE)
        val AA_LIST = Regex("AA记录|AA单|AA历史", RegexOption.IGNORE_CASE)
        val AA_SETTLE = Regex("AA结清\\s*(\\d+)", RegexOption.IGNORE_CASE)

        val YUAN_AMOUNT = Regex("(\\d+\\.?\\d*)\\s*[元块]")
        val NUMBER = Regex("\\d+\\.?\\d*")
        // 「个」可省：「3人 / 3个人 / 三人 / 三个人」都要命中
        val DIGIT_PEOPLE = Regex("(\\d+)\\s*个?人")
        val CHINESE_PEOPLE = Regex("([两三四五六七八九十])个?人")
        val WO_MEN_SA = Regex("我们仨")
        val NAME_SEPARATORS = Regex("和|跟|与|、")
        val CHINESE_NUM = mapOf('两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)

        val TOPIC_WORDS = listOf("聚餐", "烧烤", "火锅", "打车", "KTV", "唱K", "电影", "旅游", "轰趴", "团建", "奶茶")

        /** 人数上限：防「2026人」把 members_text / 拆分列表撑爆。 */
        const val MAX_HEAD_COUNT = 100

        val AA_HELP_TEXT = """
            🧾 AA 分摊这样用：
            · 发「AA 480 3人」或「聚餐平摊 480元」→ 生成 AA 结算单，你的那份帮记上
            · 回「AA记录」看最近的 AA 单
            · 收齐钱后回「AA结清 编号」标记结清
        """.trimIndent()
    }
}
