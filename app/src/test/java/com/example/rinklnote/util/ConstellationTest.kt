package com.example.rinklnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 星座推算单测：边界日期（每个星座起止日）+ 非法入参。 */
class ConstellationTest {

    @Test
    fun `covers every constellation start and end date`() {
        // 各星座起止日（公历，通行占星口径）
        assertEquals("摩羯座", Constellation.of(1, 1))
        assertEquals("摩羯座", Constellation.of(1, 19))
        assertEquals("水瓶座", Constellation.of(1, 20))
        assertEquals("水瓶座", Constellation.of(2, 18))
        assertEquals("双鱼座", Constellation.of(2, 19))
        assertEquals("双鱼座", Constellation.of(3, 20))
        assertEquals("白羊座", Constellation.of(3, 21))
        assertEquals("白羊座", Constellation.of(4, 19))
        assertEquals("金牛座", Constellation.of(4, 20))
        assertEquals("金牛座", Constellation.of(5, 20))
        assertEquals("双子座", Constellation.of(5, 21))
        assertEquals("双子座", Constellation.of(6, 21))
        assertEquals("巨蟹座", Constellation.of(6, 22))
        assertEquals("巨蟹座", Constellation.of(7, 22))
        assertEquals("狮子座", Constellation.of(7, 23))
        assertEquals("狮子座", Constellation.of(8, 22))
        assertEquals("处女座", Constellation.of(8, 23))
        assertEquals("处女座", Constellation.of(9, 22))
        assertEquals("天秤座", Constellation.of(9, 23))
        assertEquals("天秤座", Constellation.of(10, 23))
        assertEquals("天蝎座", Constellation.of(10, 24))
        assertEquals("天蝎座", Constellation.of(11, 22))
        assertEquals("射手座", Constellation.of(11, 23))
        assertEquals("射手座", Constellation.of(12, 21))
        // 跨年星座：12.22 起回到摩羯
        assertEquals("摩羯座", Constellation.of(12, 22))
        assertEquals("摩羯座", Constellation.of(12, 31))
    }

    @Test
    fun `mid constellation dates resolve correctly`() {
        assertEquals("狮子座", Constellation.of(8, 1))
        assertEquals("天蝎座", Constellation.of(11, 11))
    }

    @Test
    fun `invalid month or day returns null`() {
        assertNull(Constellation.of(0, 15))
        assertNull(Constellation.of(13, 1))
        assertNull(Constellation.of(2, 0))
        assertNull(Constellation.of(4, 32))
        // 「2 月 30 日」这类按月非法日期不在 of() 层校验（调用方传入的月/日已合法），
        // 由 ofBirthday（LocalDate.parse）兜住：2000-02-30 → null（见 ofBirthday 用例）。
    }

    @Test
    fun `ofBirthday parses iso string and rejects garbage`() {
        assertEquals("摩羯座", Constellation.ofBirthday("2000-01-01"))
        assertEquals("处女座", Constellation.ofBirthday("1999-09-01"))
        assertEquals("白羊座", Constellation.ofBirthday("2024-03-25"))
        assertNull(Constellation.ofBirthday(null))
        assertNull(Constellation.ofBirthday(""))
        assertNull(Constellation.ofBirthday("2000/01/01"))
        assertNull(Constellation.ofBirthday("not-a-date"))
        // 2 月 30 日：LocalDate.parse 拒绝 → null
        assertNull(Constellation.ofBirthday("2000-02-30"))
    }
}
