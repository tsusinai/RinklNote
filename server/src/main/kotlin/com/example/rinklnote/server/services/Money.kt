package com.example.rinklnote.server.services

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额统一以整数分（minor unit）存储与运算，避免 Double 累加的浮点漂移
 * （如 57.970000000000006）。数据库列用 amount_minor / balance_minor（BIGINT），
 * JSON 字段用 amountMinor / balanceMinor（Long），SQL 聚合直接得到整数分。
 *
 * - toMinor(yuan): 元(Double) → 分(Long)，四舍五入保留 2 位小数（HALF_UP）。
 * - fromMinor(minor): 分(Long) → 元(Double)，仅供向旧客户端输出兼容字段 amount/balance。
 * - format(minor): 分(Long) → 两位小数的「元」字符串，用于文案展示。
 * - cents(value): 旧边界兼容，把某元金额四舍五入到 2 位小数；新代码请直接用整数分。
 * - resolveAmountMinor / resolveBalanceMinor: 兼容新旧请求，优先取分字段，缺省回退元字段；
 *   两者都为空或非法时抛 IllegalArgumentException（由路由层转 400）。
 */
object Money {
    /** 旧边界兼容：把元金额四舍五入到 2 位小数（half-up）。新代码请用整数分。 */
    fun cents(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    /** 元(Double) → 分(Long)：四舍五入保留 2 位小数（HALF_UP）。 */
    fun toMinor(yuan: Double): Long =
        BigDecimal.valueOf(yuan).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()

    /** 分(Long) → 元(Double)，仅供向旧客户端输出兼容字段。 */
    fun fromMinor(minor: Long): Double = minor / 100.0

    /** 分(Long) → 两位小数的「元」字符串（如 12345 → "123.45"）。 */
    fun format(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()

    /** 兼容新旧请求：优先用 amountMinor（分，权威值）；缺省回退 amount（元）。两者皆空则报错。 */
    fun resolveAmountMinor(amountMinor: Long?, amount: Double?): Long {
        return when {
            amountMinor != null -> amountMinor
            amount != null -> toMinor(amount)
            else -> throw IllegalArgumentException("金额缺失：amountMinor 与 amount 不能同时为空")
        }
    }

    /** 兼容新旧请求：优先用 balanceMinor（分，权威值）；缺省回退 balance（元）。两者皆空则报错。 */
    fun resolveBalanceMinor(balanceMinor: Long?, balance: Double?): Long {
        return when {
            balanceMinor != null -> balanceMinor
            balance != null -> toMinor(balance)
            else -> throw IllegalArgumentException("余额缺失：balanceMinor 与 balance 不能同时为空")
        }
    }
}
