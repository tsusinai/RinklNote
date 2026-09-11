package com.example.rinklnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拖动重排名次生成规则：见 docs/plans/2026-09-11-drag-reorder-delete.md。 */
class ReorderRanksTest {

    @Test
    fun `ranks are strictly descending distinct and positive`() {
        val ranks = reorderRanks(5).toList()
        assertEquals(ranks.sortedDescending(), ranks)
        assertEquals(ranks.distinct().size, ranks.size)
        assertTrue(ranks.all { it > 0 })
        assertEquals(5, ranks.size)
    }

    @Test
    fun `empty and single produce sane values`() {
        assertEquals(0, reorderRanks(0).size)
        assertEquals(listOf(REORDER_RANK_STEP), reorderRanks(1).toList())
    }

    @Test
    fun `max rank stays far below created_at magnitude so later bills fall back on top`() {
        // created_at 为毫秒时间戳（~1.7e12）；显式名次必须远小于它，
        // 保证重排后新增的账单（sortOrder=NULL → 兜底 created_at）仍排在该日最前。
        val maxRank = reorderRanks(200).max()
        assertTrue(maxRank < 1_000_000_000_000L)
    }
}
