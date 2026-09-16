package com.example.rinklnote.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 展示徽章解析 / 拼接 / 勾选（showcase_badges 逗号串 ⇄ 列表）的单测。 */
class ShowcaseBadgesTest {

    @Test
    fun `parse splits trims dedupes and caps at three`() {
        assertEquals(listOf("record-30", "budget-first"), parseShowcaseBadges("record-30,budget-first"))
        // 去空白 + 去重
        assertEquals(listOf("record-30"), parseShowcaseBadges(" record-30 , , record-30 "))
        // 超过 3 枚时截断到前 3 枚（写入端已校验，这里只兜底）
        assertEquals(
            listOf("a-1", "b-2", "c-3"),
            parseShowcaseBadges("a-1,b-2,c-3,d-4")
        )
    }

    @Test
    fun `parse tolerates null blank and dirty input`() {
        assertEquals(emptyList<String>(), parseShowcaseBadges(null))
        assertEquals(emptyList<String>(), parseShowcaseBadges(""))
        assertEquals(emptyList<String>(), parseShowcaseBadges(" , , "))
    }

    @Test
    fun `join is comma separated and roundtrips with parse`() {
        val badges = listOf("record-30", "budget-first")
        assertEquals("record-30,budget-first", joinShowcaseBadges(badges))
        assertEquals(badges, parseShowcaseBadges(joinShowcaseBadges(badges)))
    }

    @Test
    fun `toggle adds removes and respects cap`() {
        assertEquals(listOf("a-1"), toggleShowcaseBadge(emptyList(), "a-1"))
        assertEquals(listOf("b-2"), toggleShowcaseBadge(listOf("a-1", "b-2"), "a-1"))
        // 已满 3 枚再添加 → 原样返回（UI 层负责提示）
        val full = listOf("a-1", "b-2", "c-3")
        assertEquals(full, toggleShowcaseBadge(full, "d-4"))
        // 满员时取消仍允许
        assertEquals(listOf("b-2", "c-3"), toggleShowcaseBadge(full, "a-1"))
    }
}
