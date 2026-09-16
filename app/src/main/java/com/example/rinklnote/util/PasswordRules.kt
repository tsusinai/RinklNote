package com.example.rinklnote.util

/**
 * 密码规则与强度（2026-09-17 优化登录方式）：纯 JVM 函数，无 Compose / Android 依赖，可单测。
 *
 * - [PasswordRules.validate]：新设定的密码必须 **≥6 位且同时包含大写字母与小写字母**（数字不强求），
 *   与服务端 `PasswordPolicy` 同构（文案一致），只约束注册 / 改密码的「新设定」，存量老密码不受影响。
 * - [PasswordRules.strength]：注册模式的实时强度提示（弱/中/强），依据 长度 + 数字/特殊字符组合。
 */
object PasswordRules {

    /** 不满足规则时的统一提示文案（与服务端 PasswordPolicy.RULE_MESSAGE 一致，三端同步改动）。 */
    const val RULE_MESSAGE = "密码需至少6位且包含大小写字母"

    /** 是否满足密码规则：≥6 位且同时含大写与小写字母。 */
    fun isValid(password: String): Boolean =
        password.length >= 6 &&
            password.any { it.isUpperCase() } &&
            password.any { it.isLowerCase() }

    /** 校验密码：通过返回 null，不通过返回中文提示文案。 */
    fun validate(password: String): String? = if (isValid(password)) null else RULE_MESSAGE

    /**
     * 实时强度（注册模式提示用）：
     * - 弱：仅满足底线（大小写 + 6 位）；
     * - 中：长度 ≥8，或含数字 / 特殊字符之一；
     * - 强：长度 ≥10 且数字 / 特殊字符至少含其一。
     */
    fun strength(password: String): Strength {
        if (!isValid(password)) return Strength.WEAK
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() }
        val variety = listOf(hasDigit, hasSpecial).count { it }
        return when {
            password.length >= 10 && variety >= 1 -> Strength.STRONG
            password.length >= 8 || variety >= 1 -> Strength.MEDIUM
            else -> Strength.WEAK
        }
    }

    /** 密码强度三档（弱 / 中 / 强）。 */
    enum class Strength { WEAK, MEDIUM, STRONG }
}
