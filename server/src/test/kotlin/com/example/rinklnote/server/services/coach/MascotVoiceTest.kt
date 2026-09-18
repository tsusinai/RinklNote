package com.example.rinklnote.server.services.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1.5：小盘人格化文案层单测（轻量）——非空、锚点关键词、口吻禁止项（无连续感叹号）。
 * 口吻细则见 docs/小盘语气指南.md。
 */
class MascotVoiceTest {

    @Test
    fun `记账回执含锚点已记录且金额两位小数`() {
        repeat(20) {
            val reply = MascotVoice.bookkeepingReceipt("三餐", 2000L)
            assertTrue(reply.contains("已记录"))
            assertTrue(reply.contains("三餐 ¥20.00"))
            assertFalse(reply.contains("!!"))
            assertFalse(reply.contains("！！"))
        }
    }

    @Test
    fun `删除与撤销回执锚点`() {
        val del = MascotVoice.deleteReceipt("三餐", 2000L, "9/18")
        assertTrue(del.contains("已删除"))
        assertTrue(del.contains("（9/18）"))
        assertTrue(MascotVoice.deleteReceipt("三餐", 2000L, null).endsWith("¥20.00"))
        repeat(10) {
            val undo = MascotVoice.undoReceipt("三餐", 2000L, "9/18")
            assertTrue(undo.contains("已撤销最近一单"))
            assertTrue(undo.contains("三餐 ¥20.00"))
        }
    }

    @Test
    fun `问候语自我介绍小盘`() {
        repeat(10) {
            val g = MascotVoice.greeting()
            assertTrue(g.contains("小盘"))
            assertFalse(g.contains("！！"))
        }
    }

    @Test
    fun `标题与语气清洗`() {
        assertEquals("🧾 小盘周报", MascotVoice.WEEKLY_REPORT_TITLE)
        assertEquals("🚨 小盘异常汇报", MascotVoice.ALERT_TITLE)
        assertEquals("别急！", MascotVoice.soften("别急！！！"))
        assertEquals("别急！", MascotVoice.soften("别急!!!!"))
        assertEquals("记完哦～", MascotVoice.soften("记完哦。"))
    }

    @Test
    fun `回执不是单一模板（有多样性）`() {
        val variants = (1..40).map { MascotVoice.bookkeepingReceipt("三餐", 2000L) }.toSet()
        assertTrue("应出现多个变体，实际 ${variants.size}", variants.size > 1)
        assertNotEquals(0, variants.size)
    }
}
