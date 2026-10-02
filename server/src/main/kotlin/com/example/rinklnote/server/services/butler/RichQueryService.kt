package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService

/**
 * 阶段 1 · 富查账：补齐 PhoneIntentRouter 覆盖不了的时间范围/口径（全聚合、零 LLM、零备注外泄）：
 *   - 「上周买衣服花了多少」「上周吃饭花了多少」（上周 × 分类）
 *   - 「最近哪天花钱最多」「哪天开销最大」（近 30 天最大单日支出）
 *   - 「每天还能花多少」「日均还能花多少」（本月剩余预算 ÷ 剩余天数）
 *
 * TODO(阶段1-子代理A)：实现句式识别 + 聚合 + 文案与单测（见派工契约）。
 */
class RichQueryService(
    private val billService: BillService,
    private val budgetService: BudgetService
) {

    /** 命中富查账句式返回回复文案；未命中返回 null（路由层落回 PhoneIntentRouter）。 */
    suspend fun tryAnswer(text: String, userId: Long): String? {
        // TODO(阶段1)
        return null
    }
}
