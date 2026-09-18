package com.example.rinklnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支付通知文本解析单测：微信/支付宝/银行三类样例（按公开常见格式编写，真机采集后收紧）、
 * 收支方向关键词、卡号尾号不误判、解析失败回退通用入口。
 */
class PayNotificationParserTest {

    // ── 微信 ──

    @Test
    fun `微信支付通知`() {
        val r = PayNotificationParser.parse("微信支付凭证", "￥25.00\n商户：全家便利店")
        assertTrue(r.matched)
        assertEquals(2500L, r.amountMinor)
        assertEquals("EXPENSE", r.billType)
        assertEquals("全家便利店", r.merchant)
    }

    // ── 支付宝 ──

    @Test
    fun `支付宝付款通知`() {
        val r = PayNotificationParser.parse("支付宝通知", "支付成功，金额35.50元")
        assertTrue(r.matched)
        assertEquals(3550L, r.amountMinor)
        assertEquals("EXPENSE", r.billType)
    }

    // ── 银行 ──

    @Test
    fun `银行支出通知尾号不误判`() {
        val r = PayNotificationParser.parse("招商银行", "您尾号1234的账户9月18日支出100.00元")
        assertTrue(r.matched)
        assertEquals(10000L, r.amountMinor)
        assertEquals("EXPENSE", r.billType)
    }

    @Test
    fun `收款到账为收入`() {
        val r = PayNotificationParser.parse("微信支付", "微信收款到账8.80元")
        assertTrue(r.matched)
        assertEquals(880L, r.amountMinor)
        assertEquals("INCOME", r.billType)
    }

    @Test
    fun `退款也按收入`() {
        val r = PayNotificationParser.parse("支付宝", "退款已退回12.00元")
        assertEquals("INCOME", r.billType)
    }

    // ── 解析失败 ──

    @Test
    fun `无金额通知回退通用入口`() {
        val r = PayNotificationParser.parse("微信", "你有一条新消息")
        assertFalse(r.matched)
        assertNull(r.amountMinor)
    }

    @Test
    fun `空标题与空文本安全`() {
        assertFalse(PayNotificationParser.parse(null, null).matched)
        assertFalse(PayNotificationParser.parse("", "").matched)
    }

    @Test
    fun `纯卡号数字不产生金额`() {
        val r = PayNotificationParser.parse("工商银行", "储蓄卡账号6222021234567890 查询余额成功")
        assertFalse(r.matched)
    }

    // ── 商家 ──

    @Test
    fun `收款方标签提取`() {
        val r = PayNotificationParser.parse("支付宝", "金额：42.00元 收款方：罗森便利店")
        assertEquals("罗森便利店", r.merchant)
    }
}
