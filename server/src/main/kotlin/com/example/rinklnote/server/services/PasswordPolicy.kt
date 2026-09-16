package com.example.rinklnote.server.services

/**
 * 密码规则（2026-09-17 优化登录方式）：新设定的密码必须 **≥6 位且同时包含大写字母与小写字母**
 * （数字不强求）。只约束注册 / 改密码的「新设定」环节，存量用户的老密码不受影响。
 *
 * 纯 JVM 函数，App / Web 端有同构实现（App `util/PasswordStrength.kt`、Web 登录页内联正则），
 * 三端提示文案保持一致；改动文案时务必三端同步。
 */
object PasswordPolicy {

    /** 不满足规则时的统一提示文案（三端一致）。 */
    const val RULE_MESSAGE = "密码需至少6位且包含大小写字母"

    /** 是否满足密码规则。 */
    fun isValid(password: String): Boolean =
        password.length >= 6 &&
            password.any { it.isUpperCase() } &&
            password.any { it.isLowerCase() }

    /** 校验密码：通过返回 null，不通过返回中文提示文案。 */
    fun validate(password: String): String? = if (isValid(password)) null else RULE_MESSAGE
}
