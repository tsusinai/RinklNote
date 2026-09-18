package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.UserKeyword
import com.example.rinklnote.server.services.UserMemoryService
import com.example.rinklnote.server.services.VoiceResult
import com.example.rinklnote.server.tables.VoiceKeywordsTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import com.example.rinklnote.server.services.TimeUtil

// 中文数字字符集（小写 + 大写财务数字 + 零/两/廿），用于金额正则匹配与归一。
private const val CN_NUM_CHARS = "零〇一二两廿三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟"

/** 中文数字字符串 → Double。支持大写财务数字与小写混用，含 零/〇/两/廿。仅整数元，无法识别返回 null。 */
private fun cnNumToDouble(s: String): Double? {
    if (s.isBlank()) return null
    val digits = mapOf(
        '零' to 0.0, '〇' to 0.0, '一' to 1.0, '壹' to 1.0, '二' to 2.0, '贰' to 2.0, '两' to 2.0,
        '三' to 3.0, '叁' to 3.0, '四' to 4.0, '肆' to 4.0, '五' to 5.0, '伍' to 5.0,
        '六' to 6.0, '陆' to 6.0, '七' to 7.0, '柒' to 7.0, '八' to 8.0, '捌' to 8.0,
        '九' to 9.0, '玖' to 9.0
    )
    var total = 0.0
    var section = 0.0
    var unit = 0.0
    for (ch in s) {
        when (ch) {
            '廿' -> { section += 20; unit = 0.0 }
            '亿' -> { total += (section + unit) * 100_000_000; section = 0.0; unit = 0.0 }
            '万' -> { total += (section + unit) * 10_000; section = 0.0; unit = 0.0 }
            '十', '拾' -> { section += (unit.takeIf { it > 0 } ?: 1.0) * 10; unit = 0.0 }
            '百', '佰' -> { section += (unit.takeIf { it > 0 } ?: 1.0) * 100; unit = 0.0 }
            '千', '仟' -> { section += (unit.takeIf { it > 0 } ?: 1.0) * 1000; unit = 0.0 }
            else -> { unit = digits[ch] ?: return null }
        }
    }
    val result = total + section + unit
    return result.takeIf { it > 0 }
}

class DefaultNLUService(
    private val ruleBasedParser: RuleBasedParser,
    private val llmParser: LLMParser,
    private val billService: BillService
) : NLUService {

    companion object {
        /** 上下文指代（Task 1.3）：「跟上次一样/老样子/照旧」→ 查个人记忆层最近同商家。 */
        internal val REFERENCE = Regex("跟上次一样|和上次一样|与上次一样|跟上次相同|老样子|老规矩|照旧")
    }

    override suspend fun parse(text: String, userId: Long): VoiceResult {
        // Step 0: 上下文指代（Task 1.3）——「跟上次一样/老样子」先于常规解析，查个人记忆层；
        // 但消息里点名了明确分类（如「照旧记午饭20元」的「饭」）时让位给显式分类。
        // 命中唯一近期商家 → 用其首选分类记账；歧义/无记忆/缺金额 → 返回追问文案（askReply）。
        if (REFERENCE.containsMatchIn(text)) {
            if (ruleBasedParser.parse(text, getUserKeywords(userId)) == null) {
                resolveReference(text, userId)?.let { return it }
            }
        }

        // Step 1: extract amount (reuse VoiceParser's regex)；模糊区间（三四十/几十块）取中值并带区间标注
        val (amount, amountNote) = extractAmountWithNote(text)

        // Step 2: lookup user custom keywords from DB
        val userKeywords = getUserKeywords(userId)

        // Step 3: try rule-based match (user keywords → system keywords，含品牌→类目映射)
        val categoryName = ruleBasedParser.parse(text, userKeywords)

        if (categoryName != null) {
            return VoiceResult(amount = amount, categoryName = categoryName, remark = text, amountNote = amountNote)
        }

        // Step 4: LLM fallback
        val categories = transaction {
            billService.getCategories().map { it.name }
        }
        val llmResult = llmParser.parse(text, categories)

        if (llmResult != null && llmResult.categoryName != null) {
            // Auto-save learned keyword (priority=5, lower than manual default 10)
            saveLearnedKeyword(userId, text, llmResult.categoryName)

            val remark = llmResult.remark ?: text
            return VoiceResult(
                amount = amount,
                categoryName = llmResult.categoryName,
                remark = remark,
                amountNote = amountNote
            )
        }

        // Ultimate fallback: return without category
        return VoiceResult(amount = amount, categoryName = null, remark = text, amountNote = amountNote)
    }

    /**
     * 「跟上次一样/老样子」解析（Task 1.3）：查个人记忆层（聚合画像，无明细无金额）。
     *  - 无记忆 → 引导先正常记账；
     *  - 最高频商家唯一 → 用其首选分类：消息带金额直接出记账结果（remark 记商家名，
     *    让记忆层累计到同一家）；不带金额 → 追问金额；
     *  - 最高频并列（歧义）→ 若消息点名了其中一家则用点名的，否则回执追问列候选。
     * 无法解析返回 null（调用方继续常规解析）。
     */
    private fun resolveReference(text: String, userId: Long): VoiceResult? {
        val stats = UserMemoryService.topMerchants(userId)
        if (stats.isEmpty()) {
            return VoiceResult(
                amount = null, categoryName = null, remark = text,
                askReply = "我还没记住你常去哪儿～ 先正常记几笔，我就能听懂「跟上次一样」啦"
            )
        }
        val maxCount = stats.maxOf { it.count }
        var tops = stats.filter { it.count == maxCount }
        if (tops.size > 1) {
            val named = tops.filter { text.contains(it.name) }
            if (named.size == 1) {
                tops = named
            } else {
                return VoiceResult(
                    amount = null, categoryName = null, remark = text,
                    askReply = "最近你常记 ${tops.joinToString("、") { "「${it.name}」" }}，跟哪家一样？" +
                        "说「跟上次一样 ${tops[0].name}20元」就行"
                )
            }
        }
        val merchant = tops.first()
        val category = merchant.topCategory
        val amount = extractAmountWithNote(text).first
        return if (amount != null && amount > 0) {
            VoiceResult(
                amount = amount, categoryName = category, remark = merchant.name,
                amountNote = "按上次「${merchant.name}」记$category"
            )
        } else {
            VoiceResult(
                amount = null, categoryName = category, remark = text,
                askReply = "好，还是「${merchant.name}」那套～ 多少钱？说「跟上次一样 20」就行"
            )
        }
    }

    /**
     * 金额提取 + 模糊区间标注（Task 1.3）：模糊区间（三四十/几十块）优先于精确中文数字判定 ——
     * 「三四十块」是区间而「四十三块」是精确 43，两者都是「中文数字+块」外形，顺序不能颠倒。
     */
    private fun extractAmountWithNote(text: String): Pair<Double?, String?> {
        FuzzyAmount.parse(text)?.let { range ->
            return FuzzyAmount.mid(range) to FuzzyAmount.note(range)
        }
        // 1) 带单位的阿拉伯数字（优先，避免"8月1日"的日期数字被当金额）
        Regex("""(\d+\.?\d*)\s*[元圆块]""").find(text)?.let {
            return it.groupValues[1].toDoubleOrNull() to null
        }
        // 2) 带单位的中文数字（大写/小写混用，如"贰拾圆"→20、"壹佰零伍元"→105）
        Regex("""([${CN_NUM_CHARS}]+)\s*[元圆块]""").find(text)?.let {
            val n = cnNumToDouble(it.groupValues[1])
            if (n != null) return n to null
        }
        // 3) 兜底最后一个裸阿拉伯数字
        val allNumbers = Regex("""(\d+\.?\d*)""").findAll(text).toList()
        if (allNumbers.isNotEmpty()) {
            return allNumbers.last().groupValues[1].toDoubleOrNull() to null
        }
        return null to null
    }

    private fun getUserKeywords(userId: Long): List<UserKeyword> {
        return transaction {
            VoiceKeywordsTable.selectAll()
                .where { VoiceKeywordsTable.userId eq userId }
                .orderBy(VoiceKeywordsTable.priority, SortOrder.DESC)
                .map {
                    UserKeyword(
                        keyword = it[VoiceKeywordsTable.keyword],
                        categoryName = it[VoiceKeywordsTable.categoryName]
                    )
                }
        }
    }

    private fun saveLearnedKeyword(userId: Long, text: String, categoryName: String) {
        // Strip amount (阿拉伯 + 中文数字) and common suffixes before extracting keyword
        val cleaned = text
            .replace(Regex("""\d+\.?\d*\s*[元圆块]?"""), "")
            .replace(Regex("""([${CN_NUM_CHARS}]+)\s*[元圆块]?"""), "")
            .trim()
        val keyword = if (cleaned.length >= 2) cleaned.take(6) else text.take(6).trim()
        if (keyword.length < 2) return

        transaction {
            val existing = VoiceKeywordsTable.selectAll()
                .where {
                    (VoiceKeywordsTable.userId eq userId) and
                    (VoiceKeywordsTable.keyword eq keyword)
                }.singleOrNull()

            if (existing == null) {
                VoiceKeywordsTable.insert {
                    it[VoiceKeywordsTable.userId] = userId
                    it[VoiceKeywordsTable.keyword] = keyword
                    it[VoiceKeywordsTable.categoryName] = categoryName
                    it[VoiceKeywordsTable.priority] = 5
                    it[VoiceKeywordsTable.createdAt] = TimeUtil.now().toString()
                }
            }
        }
    }
}
