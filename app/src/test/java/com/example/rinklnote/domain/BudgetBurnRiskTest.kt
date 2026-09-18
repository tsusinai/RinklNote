package com.example.rinklnote.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预算燃烧风险（Task 4.1）档位边界单测。
 * **口径钉死（与 Web 端一致）**：pct = 按当前燃烧速度预测到周期末的累计消耗 ÷ 周期预算 × 100；
 * <85 低、85~100 中、>100 高，**边界值 85 与 100 归中风险**。
 */
class BudgetBurnRiskTest {

    // ── budgetRiskLevel 档位判定（直击边界值） ──

    @Test
    fun `pct低于85为低风险`() {
        assertEquals(BudgetRiskLevel.LOW, budgetRiskLevel(84.99))
        assertEquals(BudgetRiskLevel.LOW, budgetRiskLevel(0.0))
    }

    @Test
    fun `边界85归中风险`() {
        assertEquals(BudgetRiskLevel.MEDIUM, budgetRiskLevel(85.0))
    }

    @Test
    fun `85到100之间为中风险`() {
        assertEquals(BudgetRiskLevel.MEDIUM, budgetRiskLevel(90.0))
    }

    @Test
    fun `边界100归中风险`() {
        assertEquals(BudgetRiskLevel.MEDIUM, budgetRiskLevel(100.0))
    }

    @Test
    fun `超过100为高风险`() {
        assertEquals(BudgetRiskLevel.HIGH, budgetRiskLevel(100.01))
        assertEquals(BudgetRiskLevel.HIGH, budgetRiskLevel(150.0))
    }

    // ── budgetBurnRisk 预测与组合 ──

    @Test
    fun `匀速燃烧正好贴线`() {
        // 每天 100 元 × 7 天 = 700，预算 700 → 预测 700 → pct=100 → 中风险
        val risk = budgetBurnRisk(spentMinor = 10_000, budgetMinor = 70_000, elapsedDays = 1, totalDays = 7)
        assertNotNull(risk)
        assertEquals(70_000L, risk!!.forecastMinor)
        assertEquals(100.0, risk.pct, 1e-9)
        assertEquals(BudgetRiskLevel.MEDIUM, risk.level)
    }

    @Test
    fun `燃烧速度外推HALF_UP`() {
        // spent=59, elapsed=2, total=7 → forecast = (59×7+1)/2 = 207（HALF_UP：413/2=206.5→207）
        val risk = budgetBurnRisk(59L, 700L, 2, 7)
        assertEquals(207L, risk!!.forecastMinor)
        // pct = 207×100/700 = 29.571… → 低风险
        assertEquals(BudgetRiskLevel.LOW, risk.level)
    }

    @Test
    fun `非整边界的中风险`() {
        // forecast = 170×7/2 = 595，预算 700 → pct=85.0 → 中风险（多天外推同样命中边界）
        val risk = budgetBurnRisk(170L, 700L, 2, 7)
        assertEquals(595L, risk!!.forecastMinor)
        assertEquals(85.0, risk.pct, 1e-9)
        assertEquals(BudgetRiskLevel.MEDIUM, risk.level)
    }

    @Test
    fun `超支预测为高风险`() {
        // 已花 30，elapsed=3，total=3（周期最后一天），预算 25 → 预测 30 → 120% → 高
        val risk = budgetBurnRisk(30L, 25L, 3, 3)
        assertEquals(BudgetRiskLevel.HIGH, risk!!.level)
    }

    // ── 不可派生输入 → null（调用方不预警） ──

    @Test
    fun `未设预算返回null`() {
        assertNull(budgetBurnRisk(1000L, 0L, 5, 30))
        assertNull(budgetBurnRisk(1000L, -1L, 5, 30))
    }

    @Test
    fun `零天数返回null`() {
        assertNull(budgetBurnRisk(1000L, 5000L, 0, 30))
        assertNull(budgetBurnRisk(1000L, 5000L, 5, 0))
    }
}
