package com.example.rinklnote.ui.screen.assets

import com.example.rinklnote.data.db.entity.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountEditorValidationTest {

    private val accounts = listOf(
        Account(id = 1, name = "微信", iconColor = "#28C145"),
        Account(id = 2, name = "支付宝", iconColor = "#06B4FD"),
        Account(id = 3, name = "无账户", iconColor = "#F97D1D", iconKey = "OTHER"),
    )

    @Test
    fun `name trims before validation`() {
        assertNull(validateAccountName("  招商银行  ", accounts))
    }

    @Test
    fun `name rejects blank bucket duplicate and overlong values`() {
        assertEquals("请输入账户名称", validateAccountName("   ", accounts))
        assertEquals("该名称不可用", validateAccountName("无账户", accounts))
        assertEquals("账户名称已存在", validateAccountName("微信", accounts))
        assertEquals("账户名称不能超过50个字符", validateAccountName("账".repeat(51), accounts))
    }

    @Test
    fun `editing keeps the current account name valid`() {
        assertNull(validateAccountName("微信", accounts, editingAccountId = 1L))
    }

    @Test
    fun `balance accepts empty zero and two decimals`() {
        assertEquals(0L, parseBalanceInput(""))
        assertEquals(0L, parseBalanceInput("0"))
        assertEquals(1234L, parseBalanceInput("12.34"))
        assertEquals("余额最多保留两位小数", validateBalanceInput("12.345"))
    }

    @Test
    fun `balance rejects negative values`() {
        assertEquals("余额不能为负", validateBalanceInput("-1"))
        assertNull(parseBalanceInput("-1"))
    }
}
