package com.example.rinklnote.server.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码规则（2026-09-17 优化登录方式）单测：≥6 位且同时包含大写与小写字母，数字不强求。
 * 只约束新设定的密码（注册 / 改密码），存量老密码不受影响（无迁移、无登录侧校验）。
 */
class PasswordPolicyTest {

    @Test
    fun `valid passwords contain upper and lower case with at least 6 chars`() {
        assertTrue(PasswordPolicy.isValid("Abc123"))
        assertTrue(PasswordPolicy.isValid("Xyaaaa"))
        // 纯大小写、无数字也允许
        assertTrue(PasswordPolicy.isValid("Abcdef"))
        // 长密码带特殊字符
        assertTrue(PasswordPolicy.isValid("Str0ng&Long_Password"))
    }

    @Test
    fun `passwords shorter than 6 chars are rejected`() {
        assertFalse(PasswordPolicy.isValid("Ab1"))
        assertFalse(PasswordPolicy.isValid("Aa1"))
        // 恰好 6 位且含大小写 → 通过
        assertTrue(PasswordPolicy.isValid("Aa1234"))
    }

    @Test
    fun `passwords missing lower case are rejected`() {
        assertFalse(PasswordPolicy.isValid("ABC123"))
        assertFalse(PasswordPolicy.isValid("ABCDEF1"))
    }

    @Test
    fun `passwords missing upper case are rejected`() {
        assertFalse(PasswordPolicy.isValid("abc123"))
        assertFalse(PasswordPolicy.isValid("abcdef1"))
    }

    @Test
    fun `validate returns null for valid and rule message for invalid`() {
        assertNull(PasswordPolicy.validate("Good123"))
        assertEquals(PasswordPolicy.RULE_MESSAGE, PasswordPolicy.validate("weak"))
        assertEquals(PasswordPolicy.RULE_MESSAGE, PasswordPolicy.validate("alllower123"))
        assertEquals(PasswordPolicy.RULE_MESSAGE, PasswordPolicy.validate(""))
        // 提示文案为中文（三端一致）
        assertTrue(PasswordPolicy.RULE_MESSAGE.contains("大小写"))
    }
}
