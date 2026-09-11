package com.example.rinklnote.util

/**
 * 拖动重排的 sort_order 名次生成：新序自上而下 i=0..n-1 → 严格降序且互异的值。
 * 值域（n*1000）远小于 created_at（毫秒时间戳 ~1.7e12），配合查询里的
 * COALESCE(sort_order, created_at)，重排之后新增的账单（sort_order 为 NULL）
 * 仍按 created_at 兜底落在该日最前，与历史行为一致。
 */
const val REORDER_RANK_STEP = 1000L

fun reorderRanks(count: Int): LongArray =
    LongArray(count) { i -> (count - i) * REORDER_RANK_STEP }
