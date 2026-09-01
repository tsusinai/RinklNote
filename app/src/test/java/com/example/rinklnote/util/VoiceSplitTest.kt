package com.example.rinklnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for voice multi-entry: splitting a spoken transcript into
 * per-bill segments on amount boundaries, and Chinese-numeral → arabic.
 */
class VoiceSplitTest {

    @Test
    fun `splits multiple arabic amounts into per-bill segments`() {
        val segs = VoiceParser.splitVoiceText("午餐20元打车30元")
        assertEquals(listOf("午餐20元", "打车30元"), segs)
    }

    @Test
    fun `splits multiple chinese amounts normalising to arabic`() {
        val segs = VoiceParser.splitVoiceText("早餐十块打车二十块")
        assertEquals(listOf("早餐10块", "打车20块"), segs)
    }

    @Test
    fun `single amount yields one segment`() {
        assertEquals(listOf("地铁5块"), VoiceParser.splitVoiceText("地铁5块"))
    }

    @Test
    fun `trailing non-amount words join the last segment to keep remark`() {
        assertEquals(listOf("午餐20元记一下"), VoiceParser.splitVoiceText("午餐20元记一下"))
    }

    @Test
    fun `no currency unit yields empty segments`() {
        assertEquals(emptyList<String>(), VoiceParser.splitVoiceText("今天没带钱"))
    }

    @Test
    fun `blank input yields empty segments`() {
        assertEquals(emptyList<String>(), VoiceParser.splitVoiceText(""))
        assertEquals(emptyList<String>(), VoiceParser.splitVoiceText("   "))
    }

    @Test
    fun `cnNumToDouble handles tens hundreds and hundreds-with-tens`() {
        assertEquals(20.0, VoiceParser.cnNumToDouble("二十") ?: 0.0, 0.0001)
        assertEquals(15.0, VoiceParser.cnNumToDouble("十五") ?: 0.0, 0.0001)
        assertEquals(200.0, VoiceParser.cnNumToDouble("两百") ?: 0.0, 0.0001)
        assertEquals(125.0, VoiceParser.cnNumToDouble("一百二十五") ?: 0.0, 0.0001)
    }

    @Test
    fun `cnNumToDouble rejects junk and blank`() {
        assertNull(VoiceParser.cnNumToDouble("这"))
        assertNull(VoiceParser.cnNumToDouble(""))
    }
}
