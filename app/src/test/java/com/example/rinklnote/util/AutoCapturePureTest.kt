package com.example.rinklnote.util

import com.example.rinklnote.data.repository.CaptureRepository
import com.example.rinklnote.data.repository.CaptureRepositoryImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 自动记账捕获管线纯函数单测（2026-10-03 方案 C）：
 * 商户相似度（去重置换） / 渐进信任判定 / 关键词分类推断。
 */
class AutoCapturePureTest {

    // ── 商户相似度：±5 分钟同金额账单是否并入的判据 ──

    @org.junit.Test
    fun `商户互相包含视为相似`() {
        assertTrue(CaptureRepositoryImpl.isMerchantSimilar("全家便利店（徐汇店）", "全家"))
        assertTrue(CaptureRepositoryImpl.isMerchantSimilar("全家", "全家便利店"))
        assertTrue(CaptureRepositoryImpl.isMerchantSimilar("瑞幸咖啡", "瑞幸咖啡"))
    }

    @org.junit.Test
    fun `商户完全不同不相似`() {
        assertFalse(CaptureRepositoryImpl.isMerchantSimilar("瑞幸咖啡", "蜜雪冰城"))
        assertFalse(CaptureRepositoryImpl.isMerchantSimilar("滴滴出行", "美团"))
    }

    @org.junit.Test
    fun `任一方为空视为无法证伪即相似`() {
        assertTrue(CaptureRepositoryImpl.isMerchantSimilar(null, "全家"))
        assertTrue(CaptureRepositoryImpl.isMerchantSimilar("全家", null))
        assertTrue(CaptureRepositoryImpl.isMerchantSimilar("", "  "))
        assertTrue(CaptureRepositoryImpl.isMerchantSimilar(null, null))
    }

    // ── 渐进信任：确认模式积累后是否建议解锁静默 ──

    private fun stats(captureBooked: Int, autoTotal: Int, autoKept: Int) =
        CaptureRepository.TrustStats(captureBooked, autoTotal, autoKept)

    @org.junit.Test
    fun `不足10笔不建议静默`() {
        assertFalse(CaptureRepositoryImpl.shouldSuggestSilent(stats(9, 12, 12)))
    }

    @org.junit.Test
    fun `保留率低于90不建议静默`() {
        // 10 笔自动入账删了 2 笔 = 保留 80%
        assertFalse(CaptureRepositoryImpl.shouldSuggestSilent(stats(10, 10, 8)))
    }

    @org.junit.Test
    fun `达标时建议静默`() {
        // 10 笔删 1 笔 = 保留 90%，整点达标
        assertTrue(CaptureRepositoryImpl.shouldSuggestSilent(stats(10, 10, 9)))
        assertTrue(CaptureRepositoryImpl.shouldSuggestSilent(stats(20, 15, 14)))
    }

    @org.junit.Test
    fun `无AUTO账单不崩溃且不建议`() {
        assertFalse(CaptureRepositoryImpl.shouldSuggestSilent(stats(0, 0, 0)))
    }

    // ── 关键词分类推断（捕获管线落账分类）──

    @org.junit.Test
    fun `通知文本命中分类关键词`() {
        assertEquals("三餐", VoiceParser.inferCategory("微信支付 商户：肯德基"))
        assertEquals("交通", VoiceParser.inferCategory("滴滴出行 支付成功"))
        assertEquals("日用", VoiceParser.inferCategory("超市购物"))
    }

    @org.junit.Test
    fun `无关键词返回null由调用方兜底`() {
        assertNull(VoiceParser.inferCategory("某某科技有限公司"))
        assertNull(VoiceParser.inferCategory(""))
    }
}
