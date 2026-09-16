package com.example.rinklnote.ui.component

import com.example.rinklnote.R

/**
 * 成就徽章的展示映射（2026-09-17 从 ChallengeScreen 抽出共享）：
 * 「Rk省钱计划」成就墙与「我的」页徽章展示管理共用同一份，避免两处各写一遍 when。
 *
 * 图标资产契约：`R.drawable.badge_<id 下划线化>`，与 `resource/challenge/badge-*.svg` 逐字对应
 * （SVG → VectorDrawable 已在 E2 任务完成）；兜底图标防止新增 id 未配资产时崩溃。
 */

/** 徽章 id（如 "record-30"）→ 图标资源。 */
fun achievementBadgeRes(id: String): Int = when (id) {
    "record-first" -> R.drawable.badge_record_first
    "record-7" -> R.drawable.badge_record_7
    "record-30" -> R.drawable.badge_record_30
    "record-100" -> R.drawable.badge_record_100
    "record-365" -> R.drawable.badge_record_365
    "nospend-month-3" -> R.drawable.badge_nospend_month_3
    "nospend-month-8" -> R.drawable.badge_nospend_month_8
    "nospend-month-15" -> R.drawable.badge_nospend_month_15
    "nospend-total-30" -> R.drawable.badge_nospend_total_30
    "nospend-total-100" -> R.drawable.badge_nospend_total_100
    "budget-first" -> R.drawable.badge_budget_first
    "budget-month" -> R.drawable.badge_budget_month
    "budget-3months" -> R.drawable.badge_budget_3months
    "challenge-3" -> R.drawable.badge_challenge_3
    "challenge-10" -> R.drawable.badge_challenge_10
    else -> R.drawable.ic_launcher_foreground
}

/** 徽章 id → 中文名（成就墙与徽章展示管理共用）。 */
fun achievementBadgeName(id: String): String = when (id) {
    "record-first" -> "首笔账单"
    "record-7" -> "记账 7 天"
    "record-30" -> "记账 30 天"
    "record-100" -> "记账 100 天"
    "record-365" -> "记账 365 天"
    "nospend-month-3" -> "月无消费 3 天"
    "nospend-month-8" -> "月无消费 8 天"
    "nospend-month-15" -> "月无消费 15 天"
    "nospend-total-30" -> "无消费 30 天"
    "nospend-total-100" -> "无消费 100 天"
    "budget-first" -> "首设预算"
    "budget-month" -> "月度不超支"
    "budget-3months" -> "三月不超支"
    "challenge-3" -> "完成 3 挑战"
    "challenge-10" -> "完成 10 挑战"
    else -> id
}
