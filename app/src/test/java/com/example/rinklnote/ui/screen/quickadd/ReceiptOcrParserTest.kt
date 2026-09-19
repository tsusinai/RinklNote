package com.example.rinklnote.ui.screen.quickadd

import java.time.LocalDate
import java.time.Year
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小票 OCR 候选提取单测：金额三档可信度排序与去重、日期全/缺年格式与非法日期剔除、
 * 商家标签行与票头行启发式。
 */
class ReceiptOcrParserTest {

    // ── 金额 ──

    @Test
    fun `合计标签金额排最前`() {
        val text = "可乐 3.50元\n合计：￥25.50\n会员号 889900"
        val r = ReceiptOcrParser.extract(text)
        assertEquals(25.50, r.amounts.first(), 1e-9)
        // 单价也作为候选保留（用户可能只想记单品）
        assertTrue(r.amounts.any { Math.round(it * 100) == 350L })
    }

    @Test
    fun `实付标签与货币符号档`() {
        val r = ReceiptOcrParser.extract("实付 ¥12.80\n单价 5.00元")
        assertEquals(12.80, r.amounts.first(), 1e-9)
    }

    @Test
    fun `金额同值去重`() {
        val r = ReceiptOcrParser.extract("金额：¥10.00\n合计 ¥10.00\n饮料 10.00元")
        assertEquals(1, r.amounts.size)
        assertEquals(10.00, r.amounts.first(), 1e-9)
    }

    @Test
    fun `裸数字不产生金额候选`() {
        val r = ReceiptOcrParser.extract("订单号 202609181234 会员 8888 机器号 03")
        assertTrue(r.amounts.isEmpty())
    }

    @Test
    fun `无金额返回空列表`() {
        assertTrue(ReceiptOcrParser.extract("").amounts.isEmpty())
        assertTrue(ReceiptOcrParser.extract("欢迎光临").amounts.isEmpty())
    }

    @Test
    fun `超大数字金额不进候选`() {
        // 金额候选在弹窗里经 Money.yuanToMinor 转分：17+ 位整数溢出 Long 抛 ArithmeticException、
        // 400 位数字转 Double 为 Infinity 再转 BigDecimal 抛 NumberFormatException——点 chip 即崩溃。
        // 超界数字（条码/会员号误拼等解析噪声）必须被丢弃，不进候选。
        val r = ReceiptOcrParser.extract("合计 99999999999999999999\n¥99999999999999999999\n单价 99999999999999999999元")
        assertTrue(r.amounts.isEmpty())
        // 同票内上限内的金额不受牵连
        val mixed = ReceiptOcrParser.extract("合计 99999999999999999999\n可乐 3.50元")
        assertEquals(listOf(3.50), mixed.amounts)
    }

    // ── 日期 ──

    @Test
    fun `全格式日期横杠`() {
        val r = ReceiptOcrParser.extract("日期:2026-09-18 12:30")
        assertEquals(listOf(LocalDate.of(2026, 9, 18)), r.dates)
    }

    @Test
    fun `全格式日期中文年月日`() {
        val r = ReceiptOcrParser.extract("2026年9月18日")
        assertEquals(listOf(LocalDate.of(2026, 9, 18)), r.dates)
    }

    @Test
    fun `斜杠与点分隔`() {
        val r = ReceiptOcrParser.extract("2026/9/18\n单号 2026.9.18x")
        assertEquals(listOf(LocalDate.of(2026, 9, 18)), r.dates)
    }

    @Test
    fun `缺年格式补当前年`() {
        val r = ReceiptOcrParser.extract("打印时间 9月18日")
        assertEquals(listOf(Year.now().atMonthDay(java.time.MonthDay.of(9, 18))), r.dates)
    }

    @Test
    fun `非法日期被剔除`() {
        val r = ReceiptOcrParser.extract("2026-13-45 开票")
        assertTrue(r.dates.isEmpty())
    }

    @Test
    fun `日期去重保序`() {
        val r = ReceiptOcrParser.extract("2026-09-18\n日期：2026年09月18日")
        assertEquals(1, r.dates.size)
    }

    // ── 商家 ──

    @Test
    fun `商户标签行优先`() {
        val r = ReceiptOcrParser.extract("XX便利店\n商户：永辉超市旗舰店\n合计 ¥35.00")
        assertEquals("永辉超市旗舰店", r.merchants.first())
    }

    @Test
    fun `无标签时取票头像店名的行`() {
        val r = ReceiptOcrParser.extract("罗森便利店\n订单号 20260918001\n收银员 01\n合计 ¥12.00")
        assertEquals("罗森便利店", r.merchants.first())
        // 元信息行（订单号/收银员）不应入选
        assertTrue(r.merchants.none { it.contains("订单") })
        assertTrue(r.merchants.none { it.contains("收银") })
    }

    @Test
    fun `商家候选最多三个`() {
        val text = "AAA店\nBBB店\nCCC店\nDDD店\nEEE店"
        assertTrue(ReceiptOcrParser.extract(text).merchants.size <= 3)
    }

    @Test
    fun `空文本安全`() {
        val r = ReceiptOcrParser.extract("")
        assertTrue(r.amounts.isEmpty() && r.dates.isEmpty() && r.merchants.isEmpty())
    }
}
