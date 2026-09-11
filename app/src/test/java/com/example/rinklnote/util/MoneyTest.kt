package com.example.rinklnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 金额工具（整数分）单测：解析 / 格式化往返必须无损。 */
class MoneyTest {

    @Test
    fun `parseMinor 经典漂移样本精确无误`() {
        // 若用 toDouble() * 100，"57.97" 会得到 5796.999999999999
        assertEquals(5797L, Money.parseMinor("57.97"))
        assertEquals(10L, Money.parseMinor("0.1"))
        assertEquals(7L, Money.parseMinor("0.07"))
        assertEquals(2999L, Money.parseMinor("29.99"))
        assertEquals(123456789L, Money.parseMinor("1234567.89"))
    }

    @Test
    fun `parseMinor 整数与一位小数`() {
        assertEquals(100L, Money.parseMinor("1"))
        assertEquals(150L, Money.parseMinor("1.5"))
        assertEquals(0L, Money.parseMinor("0"))
    }

    @Test
    fun `parseMinor 容忍首尾空白`() {
        assertEquals(1230L, Money.parseMinor("  12.30  "))
    }

    @Test
    fun `parseMinor 非法输入一律返回 null`() {
        for (bad in listOf("", "  ", "abc", "-5", "1.2.3", "1,234", "1.", ".5", "+3", "1.234")) {
            assertNull("input=$bad", Money.parseMinor(bad))
        }
    }

    @Test
    fun `format 带货币符号与千分位`() {
        assertEquals("¥1,234.56", Money.format(123456L))
        assertEquals("¥0.00", Money.format(0L))
        assertEquals("¥0.07", Money.format(7L))
        assertEquals("-¥12.30", Money.format(-1230L))
    }

    @Test
    fun `formatPlain 不含货币符号`() {
        assertEquals("1,234.56", Money.formatPlain(123456L))
        assertEquals("12.30", Money.formatPlain(1230L))
    }

    @Test
    fun `toYuanInputString 去掉多余零（观感与手输一致）`() {
        assertEquals("25", Money.toYuanInputString(2500L))
        assertEquals("25.5", Money.toYuanInputString(2550L))
        assertEquals("25.05", Money.toYuanInputString(2505L))
        assertEquals("0", Money.toYuanInputString(0L))
    }

    @Test
    fun `yuanToMinor 对已是 Double 的边界做 HALF_UP`() {
        assertEquals(5797L, Money.yuanToMinor(57.97))
        assertEquals(0L, Money.yuanToMinor(0.0))
        assertEquals(100L, Money.yuanToMinor(0.995)) // 0.995 元 → 99.5 分 → HALF_UP = 100 分
    }

    @Test
    fun `parse 与 format 往返无损`() {
        for (yuan in listOf("0", "0.01", "0.1", "57.97", "1234.56", "99999999.99")) {
            val minor = Money.parseMinor(yuan)!!
            val shown = Money.formatPlain(minor).replace(",", "")
            assertEquals(yuan.toDouble().toString(), shown.toDouble().toString())
        }
    }
}
