package com.example.rinklnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分享文本解析单测：覆盖「金额标签 / 货币符号 / 单位后缀（阿拉伯+中文）」四条金额规则、
 * 商户提取、分类关键词复用、以及「解析不出金额时原文入备注」的产品口径。
 */
class ShareTextParserTest {

    // ── 金额：标签行 ──

    @Test
    fun `金额标签行带冒号`() {
        val r = ShareTextParser.parse("支付宝支付成功\n金额：35.50\n收款方：罗森便利店")
        assertEquals(35.50, r.amount!!, 1e-9)
        assertEquals("罗森便利店", r.merchant)
        assertEquals("罗森便利店", r.remark)
    }

    @Test
    fun `金额标签行带货币符号且无冒号`() {
        val r = ShareTextParser.parse("微信支付\n金额 ¥25\n商户 全家便利店")
        assertEquals(25.0, r.amount!!, 1e-9)
        assertEquals("全家便利店", r.merchant)
    }

    // ── 金额：货币符号前缀 ──

    @Test
    fun `全角货币符号前缀`() {
        val r = ShareTextParser.parse("微信支付凭证\n￥25.50\n商户：全家便利店\n2026-09-18 12:30")
        assertEquals(25.50, r.amount!!, 1e-9)
    }

    @Test
    fun `半角货币符号前缀且日期订单号不被误当金额`() {
        // 刻意不做裸数字兜底：订单号/日期里的数字绝不提取为金额
        val r = ShareTextParser.parse("订单号 2026091812345678\n¥12.30")
        assertEquals(12.30, r.amount!!, 1e-9)
    }

    // ── 金额：单位后缀 ──

    @Test
    fun `阿拉伯数字带元后缀`() {
        val r = ShareTextParser.parse("打车花了15元")
        assertEquals(15.0, r.amount!!, 1e-9)
    }

    @Test
    fun `中文数字带块后缀`() {
        val r = ShareTextParser.parse("今天午饭花了二十块")
        assertEquals(20.0, r.amount!!, 1e-9)
    }

    @Test
    fun `标签行优先于单位后缀`() {
        val r = ShareTextParser.parse("金额：30.00\n共消费3元手续费")
        assertEquals(30.0, r.amount!!, 1e-9)
    }

    // ── 无金额 → 原文入备注 ──

    @Test
    fun `未解析出金额时原文入备注`() {
        val text = "周末去超市逛了一圈，忘记看小票了"
        val r = ShareTextParser.parse(text)
        assertNull(r.amount)
        assertEquals(text, r.remark)
    }

    @Test
    fun `纯订单号文本不产生金额且原文入备注`() {
        val r = ShareTextParser.parse("订单号 2026091812345678 已完成")
        assertNull(r.amount)
        assertEquals("订单号 2026091812345678 已完成", r.remark)
    }

    // ── 分类关键词（复用 VoiceParser 词表） ──

    @Test
    fun `商户名参与分类关键词匹配`() {
        val r = ShareTextParser.parse("金额：42.00\n商户：美团外卖平台")
        assertEquals("三餐", r.categoryName)
    }

    @Test
    fun `正文关键词命中交通`() {
        val r = ShareTextParser.parse("地铁出行 ¥4.00")
        assertEquals("交通", r.categoryName)
    }

    // ── 边界 ──

    @Test
    fun `空文本与null均安全`() {
        assertNull(ShareTextParser.parse(null).amount)
        assertNull(ShareTextParser.parse("").amount)
        assertEquals("", ShareTextParser.parse("").remark)
    }

    @Test
    fun `金额最多两位小数`() {
        val r = ShareTextParser.parse("¥9.99")
        assertEquals(9.99, r.amount!!, 1e-9)
    }

    @Test
    fun `超长备注截断到上限`() {
        val long = "记".repeat(500)
        val r = ShareTextParser.parse(long)
        assertEquals(200, r.remark.length)
    }
}
