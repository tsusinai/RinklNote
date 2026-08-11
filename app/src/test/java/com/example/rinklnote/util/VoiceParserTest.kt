package com.example.rinklnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for the local rule-based voice parser.
 * Guards the amount regex and category keyword matching (Phase 4).
 */
class VoiceParserTest {

    @Test
    fun `parse extracts arabic amount with yuan suffix`() {
        val r = VoiceParser.parse("午餐20元")
        assertEquals(20.0, r.amount ?: 0.0, 0.0001)
        assertEquals("三餐", r.categoryName)
        assertEquals("午餐20元", r.remark)
    }

    @Test
    fun `parse extracts decimal amount with 块 suffix`() {
        val r = VoiceParser.parse("打车12.5块")
        assertEquals(12.5, r.amount ?: 0.0, 0.0001)
        assertEquals("交通", r.categoryName)
    }

    @Test
    fun `parse returns null amount when no digit present`() {
        val r = VoiceParser.parse("地铁")
        assertNull(r.amount)
        assertEquals("交通", r.categoryName)
    }

    @Test
    fun `parse keeps remark as full input text`() {
        val r = VoiceParser.parse("买咖啡30")
        assertEquals(30.0, r.amount ?: 0.0, 0.0001)
        assertNull(r.categoryName)
        assertEquals("买咖啡30", r.remark)
    }

    @Test
    fun `parse empty string yields nulls`() {
        val r = VoiceParser.parse("")
        assertNull(r.amount)
        assertNull(r.categoryName)
    }

    @Test
    fun `parse matches transport keywords`() {
        assertEquals("交通", VoiceParser.parse("加油200元").categoryName)
        assertEquals("交通", VoiceParser.parse("公交2元").categoryName)
        assertEquals("交通", VoiceParser.parse("地铁5块").categoryName)
    }

    @Test
    fun `parse matches entertainment and shopping`() {
        assertEquals("娱乐", VoiceParser.parse("电影票45元").categoryName)
        assertEquals("娱乐", VoiceParser.parse("游戏30元").categoryName)
        assertEquals("网购", VoiceParser.parse("淘宝买衣服99").categoryName)
    }

    @Test
    fun `parse keyword matching is boundary-safe - unrelated text stays uncategorized`() {
        // "吃饭" contains "吃" → 三餐
        assertEquals("三餐", VoiceParser.parse("吃饭花了18块").categoryName)
        // "交通" appears as a word, not a substring of unrelated text
        assertNull(VoiceParser.parse("普通消费10元").categoryName)
    }

    @Test
    fun `parse ignores trailing junk after amount`() {
        val r = VoiceParser.parse("早餐15元两个包子")
        assertEquals(15.0, r.amount ?: 0.0, 0.0001)
        assertEquals("三餐", r.categoryName)
    }
}
