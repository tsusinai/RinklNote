package com.example.rinklnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码规则与强度纯函数单测（2026-09-17 优化登录方式）。
 * 规则与服务端 PasswordPolicy 同构：≥6 位且同时含大小写字母（数字不强求），只约束新设定。
 */
class PasswordRulesTest {

    // ── isValid / validate：与服务端 PasswordPolicy 对齐 ──

    @Test
    fun `valid password has upper and lower case with at least 6 chars`() {
        assertTrue(PasswordRules.isValid("Abc123"))
        assertTrue(PasswordRules.isValid("Abcdef")) // 无数字也允许
        assertTrue(PasswordRules.isValid("Aa1234")) // 恰好 6 位
    }

    @Test
    fun `too short password is invalid`() {
        assertFalse(PasswordRules.isValid("Ab1"))
        assertFalse(PasswordRules.isValid("Aa123")) // 5 位
    }

    @Test
    fun `missing lower or upper case is invalid`() {
        assertFalse(PasswordRules.isValid("ABC123"))
        assertFalse(PasswordRules.isValid("abc123"))
        assertFalse(PasswordRules.isValid("123456"))
    }

    @Test
    fun `validate returns null when ok and rule message otherwise`() {
        assertNull(PasswordRules.validate("Good123"))
        assertEquals(PasswordRules.RULE_MESSAGE, PasswordRules.validate("weak"))
        assertEquals(PasswordRules.RULE_MESSAGE, PasswordRules.validate(""))
        // 文案与服务端 PasswordPolicy.RULE_MESSAGE 一致（三端契约）
        assertEquals("密码需至少6位且包含大小写字母", PasswordRules.RULE_MESSAGE)
    }

    // ── strength：长度 + 数字/特殊字符组合 ──

    @Test
    fun `invalid password is always weak`() {
        assertEquals(PasswordRules.Strength.WEAK, PasswordRules.strength("abc"))
        assertEquals(PasswordRules.Strength.WEAK, PasswordRules.strength("alllower123"))
    }

    @Test
    fun `bare minimum password is weak`() {
        assertEquals(PasswordRules.Strength.WEAK, PasswordRules.strength("Abcdef"))
    }

    @Test
    fun `digit or longer length lifts to medium`() {
        assertEquals(PasswordRules.Strength.MEDIUM, PasswordRules.strength("Abc123"))   // 含数字
        assertEquals(PasswordRules.Strength.MEDIUM, PasswordRules.strength("Abcdefgh")) // 8 位无数字
        assertEquals(PasswordRules.Strength.MEDIUM, PasswordRules.strength("Abcdef!"))  // 含特殊字符
        // 7 位且无数字/特殊字符 → 仍是弱
        assertEquals(PasswordRules.Strength.WEAK, PasswordRules.strength("Abcdefg"))
    }

    @Test
    fun `long password with variety is strong`() {
        assertEquals(PasswordRules.Strength.STRONG, PasswordRules.strength("Abcdefgh12"))
        assertEquals(PasswordRules.Strength.STRONG, PasswordRules.strength("Abcdefghi!"))
    }
}
