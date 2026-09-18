package com.example.rinklnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 多币种汇率纯计算单测（Task 4.2）：交叉汇率换算、金额换算（整数分 HALF_UP）、
 * 真汇率与演示表的合并回落。
 */
class CurrencyRatesTest {

    // 与多币种页演示表同量级的测试牌价（每 1 单位兑 CNY）
    private val rates = mapOf(
        "CNY" to 1.00,
        "USD" to 7.10,
        "EUR" to 7.80,
        "JPY" to 0.048
    )

    // ── 交叉汇率 ──

    @Test
    fun `本位币自身恒为1`() {
        assertEquals(1.0, CurrencyRates.rateVsBase("USD", "USD", rates)!!, 1e-12)
        assertEquals(1.0, CurrencyRates.rateVsBase("CNY", "CNY", rates)!!, 1e-12)
    }

    @Test
    fun `base为CNY时等于原始牌价`() {
        assertEquals(7.10, CurrencyRates.rateVsBase("USD", "CNY", rates)!!, 1e-9)
    }

    @Test
    fun `非CNY本位币交叉换算`() {
        // 1 EUR = 7.80 CNY = 7.80/7.10 USD ≈ 1.0986 USD
        assertEquals(7.80 / 7.10, CurrencyRates.rateVsBase("EUR", "USD", rates)!!, 1e-9)
        // 1 USD 兑 JPY 本位币 = 7.10/0.048
        assertEquals(7.10 / 0.048, CurrencyRates.rateVsBase("USD", "JPY", rates)!!, 1e-9)
    }

    @Test
    fun `缺牌价或非法值返回null`() {
        assertNull(CurrencyRates.rateVsBase("GBP", "CNY", rates))  // code 缺牌价
        assertNull(CurrencyRates.rateVsBase("USD", "GBP", rates))  // base 缺牌价
        assertNull(CurrencyRates.rateVsBase("USD", "ZERO", mapOf("USD" to 7.1, "ZERO" to 0.0)))
        assertNull(CurrencyRates.rateVsBase("USD", "NEG", mapOf("USD" to 7.1, "NEG" to -1.0)))
    }

    // ── 金额换算（整数分） ──

    @Test
    fun `金额换算HALF_UP到分`() {
        // 1000 分 USD → CNY：1000 × 7.1 = 7100 分
        assertEquals(7100L, CurrencyRates.convertMinor(1000L, "USD", "CNY", rates))
        // 1 分 EUR → USD：1 × (7.8/7.1) = 1.0986 → 四舍五入 1 分
        assertEquals(1L, CurrencyRates.convertMinor(1L, "EUR", "USD", rates))
    }

    @Test
    fun `同币种金额原样返回`() {
        assertEquals(1234L, CurrencyRates.convertMinor(1234L, "USD", "USD", rates)!!)
    }

    @Test
    fun `金额换算缺牌价返回null`() {
        assertNull(CurrencyRates.convertMinor(1000L, "GBP", "CNY", rates))
    }

    // ── 合并回落 ──

    @Test
    fun `真汇率覆盖演示值`() {
        val merged = CurrencyRates.mergeRates(mapOf("USD" to 7.25), rates)
        assertEquals(7.25, merged["USD"]!!, 1e-9)
        assertEquals(7.80, merged["EUR"]!!, 1e-9)  // 未覆盖的回落演示表
    }

    @Test
    fun `非法真汇率被剔除回落演示表`() {
        val merged = CurrencyRates.mergeRates(mapOf("EUR" to 0.0, "JPY" to -1.0, "NaN" to Double.NaN), rates)
        assertEquals(7.80, merged["EUR"]!!, 1e-9)
        assertEquals(0.048, merged["JPY"]!!, 1e-12)
    }

    @Test
    fun `空真汇率全量回落演示表`() {
        assertEquals(rates, CurrencyRates.mergeRates(emptyMap(), rates))
    }
}
