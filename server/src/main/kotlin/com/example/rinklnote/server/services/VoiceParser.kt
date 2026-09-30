package com.example.rinklnote.server.services

/**
 * 建议置信度等级（2026-10-01 Task 3 建议可信度）：
 *  - HIGH：来自用户自己的数据（手动自定义词表 / 唯一记忆商家）—— 可一键记入（needsConfirmation=false）；
 *  - MEDIUM：规则词表 / 历史学习词等有依据但不够个性化 —— 填充表单并要求确认；
 *  - LOW：LLM 归类或无分类 —— 必须检查后记入。
 * 模糊金额（三四十/几十块）会把 HIGH 压制为 MEDIUM：金额本身是估计值，不允许静默一键落账。
 */
enum class SuggestionConfidence {
    HIGH, MEDIUM, LOW
}

/** 建议来源：HISTORY=历史统计（用户词表 / 个人记忆层）、RULE=规则词表、LLM=AI 模型归类。 */
enum class SuggestionSource {
    HISTORY, RULE, LLM
}

data class VoiceResult(
    val amount: Double?,
    val categoryName: String?,
    val remark: String,
    // 2026-09-18 Task 1.3（NLU 理解升级）：模糊金额区间的回执标注（如「区间30~40元，按中值35元记」）。
    val amountNote: String? = null,
    // 非空时路由层直接把该文案回给用户（上下文指代「跟上次一样」的追问/引导），跳过记账与 LLM。
    val askReply: String? = null,
    // ── 2026-10-01 Task 3（建议可信度）新增，全部带默认值，既有构造点与调用方零改动 ──
    // 置信度等级；默认 LOW（需要确认）—— 宁可多确认，不可静默扩大自动记账范围。
    val confidence: SuggestionConfidence = SuggestionConfidence.LOW,
    // 推荐分类的来源；null = 没有给出分类推荐。
    val categorySource: SuggestionSource? = null,
    // 推荐账户的来源；服务端暂不做账户推荐，恒为 null（保留字段，App 端可按本地历史自行推荐）。
    val accountSource: SuggestionSource? = null,
    // 简短中文解释（建议依据），如「命中你的自定义词「星巴克」」；null = 无解释。
    val explanation: String? = null,
    // 是否需要用户确认后才能记入（与 confidence 严格联动：= confidence != HIGH；模糊金额一律 true）。
    val needsConfirmation: Boolean = true,
    // 是否命中模糊金额词（三四十/几十块等）；此时 amount 是区间中值而非精确金额。
    val fuzzyAmount: Boolean = false,
    // 模糊区间下界/上界（整数分，经 Money.toMinor 换算）。仅 fuzzyAmount=true 时非空。
    val amountRangeMinMinor: Long? = null,
    val amountRangeMaxMinor: Long? = null
)

data class UserKeyword(
    val keyword: String,
    val categoryName: String,
    // 词表优先级（2026-10-01 Task 3）：≥10 为用户手动添加，<10 为历史学习自动沉淀（默认 10 兼容旧构造点）。
    val priority: Int = 10
)
