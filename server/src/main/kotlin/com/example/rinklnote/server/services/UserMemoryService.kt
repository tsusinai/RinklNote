package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.UserMemoryTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import kotlinx.serialization.encodeToString

/**
 * 个人记忆层（2026-09-18 Task 1.2）：用户聚合画像 KV（user_id + key + value + updated_at）。
 *
 * **隐私红线（计划批准口径）**：只存「商家名 + 次数 + 首选分类」的**聚合**，
 * 绝不存单笔明细、金额或完整备注原文；注入 LLM 的摘要经 [buildSummary] 截断到
 * [SUMMARY_MAX_LEN]（200）字以内。商家词由备注轻量启发式提取（[extractMerchantToken]，
 * 去金额/日期后取前 6 字），单条提取失败直接跳过。
 *
 * 写路径（落账后异步累计，见 PhoneIntentRouter / AiAssistService.record 的钩子）：
 * 写失败只落日志，**绝不影响落账主流程**。读路径：[memorySummary] 给
 * InsightService.naturalQueryContext 注入，空记录返回空串（prompt 无该段）。
 */
object UserMemoryService {
    private val logger = LoggerFactory.getLogger("UserMemoryService")
    private val json = Json { ignoreUnknownKeys = true }

    /** 独立 scope：fire-and-forget 累计；进程关闭时在途写入丢弃可接受（下次落账再累计）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    const val KEY_TOP_MERCHANTS = "top_merchants"

    /** KV 里最多保留的商家条数（按次数降序截断，防 value 无限增长）。 */
    internal const val MAX_MERCHANTS = 20

    /** LLM prompt 注入摘要的最大长度（字）。 */
    internal const val SUMMARY_MAX_LEN = 200

    /** 单个商家的聚合条目：次数 + 各分类票数（首选分类 = 票数最多的分类）。 */
    @Serializable
    data class MerchantStat(val name: String, val count: Int, val categories: Map<String, Int> = emptyMap()) {
        val topCategory: String get() = categories.maxByOrNull { it.value }?.key ?: "其他"
    }

    /**
     * 从备注/原文提取「商家词」：先去日期（9/18、2026-09-18、2026年9月18日，第二段可省）、
     * 再去金额（阿拉伯数字+元/圆/块）、最后去空白，取前 4 字（中文商家名多为 2~4 字，
     * 截 4 字能让「瑞幸咖啡拿铁」与「瑞幸咖啡」聚到同一词）；不足 2 字视为提取不出
     * （返回 null，跳过累计）。纯函数，可单测。
     */
    internal fun extractMerchantToken(remark: String?): String? {
        if (remark.isNullOrBlank()) return null
        val cleaned = remark
            .replace(Regex("""[0-9]{1,4}[-/年.][0-9]{1,2}(?:[-/月.][0-9]{1,4})?日?"""), "")
            .replace(Regex("""\d+\.?\d*\s*[元圆块]?"""), "")
            .replace(Regex("""[-/年月.日\s]"""), "")
        val token = cleaned.take(4)
        return token.takeIf { it.length >= 2 }
    }

    /** 落账后异步累计（fire-and-forget）：写失败只落日志，绝不影响落账主流程。 */
    fun recordBillAsync(userId: Long, remark: String?, categoryName: String?) {
        scope.launch {
            try {
                recordBill(userId, remark, categoryName)
            } catch (e: Exception) {
                logger.warn("个人记忆累计失败（不影响落账）user=$userId: ${e.message}")
            }
        }
    }

    /** 同步累计一条：对应商家词次数 +1、分类票数 +1，按次数降序截断后写回 KV。 */
    fun recordBill(userId: Long, remark: String?, categoryName: String?) {
        val token = extractMerchantToken(remark) ?: return
        val cat = categoryName?.takeIf { it.isNotBlank() } ?: "其他"
        transaction {
            val stats = loadStats(userId)
            val next = stats.firstOrNull { it.name == token }?.let { hit ->
                stats.map { if (it.name == token) it.copy(count = it.count + 1, categories = it.categories + (cat to (it.categories[cat] ?: 0) + 1)) else it }
            } ?: (stats + MerchantStat(token, 1, mapOf(cat to 1)))
            val trimmed = next.sortedWith(compareByDescending<MerchantStat> { it.count }.thenBy { it.name })
                .take(MAX_MERCHANTS)
            upsert(userId, KEY_TOP_MERCHANTS, json.encodeToString(trimmed))
        }
    }

    /** 读取该用户的聚合画像（无记录返回空列表）。 */
    fun topMerchants(userId: Long): List<MerchantStat> = transaction { loadStats(userId) }

    /**
     * ≤[SUMMARY_MAX_LEN] 字的聚合摘要（LLM prompt 注入用）。无记录返回空串。
     * 只含商家名/分类/次数，无金额、无日期、无单笔明细 —— 注入前逐字自查口径。
     */
    fun memorySummary(userId: Long): String {
        val stats = topMerchants(userId)
        return if (stats.isEmpty()) "" else buildSummary(stats)
    }

    /** 纯函数：聚合画像 → 摘要文案（可单测）。 */
    internal fun buildSummary(stats: List<MerchantStat>): String {
        if (stats.isEmpty()) return ""
        return stats.take(5)
            .joinToString("、") { "${it.name}常去${it.count}次，多记在「${it.topCategory}」" }
            .let { "用户常去商家（聚合，无明细无金额）: $it" }
            .take(SUMMARY_MAX_LEN)
    }

    // ── KV 存取（内部） ──

    private fun loadStats(userId: Long): List<MerchantStat> {
        val row = UserMemoryTable.selectAll()
            .where { (UserMemoryTable.userId eq userId) and (UserMemoryTable.key eq KEY_TOP_MERCHANTS) }
            .singleOrNull() ?: return emptyList()
        return try {
            json.decodeFromString<List<MerchantStat>>(row[UserMemoryTable.value])
        } catch (e: Exception) {
            logger.warn("个人记忆 KV 解析失败，按空画像处理: ${e.message}")
            emptyList()
        }
    }

    private fun upsert(userId: Long, key: String, value: String) {
        val now = System.currentTimeMillis()
        val updated = UserMemoryTable.update({
            (UserMemoryTable.userId eq userId) and (UserMemoryTable.key eq key)
        }) {
            it[UserMemoryTable.value] = value
            it[updatedAt] = now
        }
        if (updated == 0) {
            UserMemoryTable.insert {
                it[UserMemoryTable.userId] = userId
                it[UserMemoryTable.key] = key
                it[UserMemoryTable.value] = value
                it[updatedAt] = now
            }
        }
    }
}
