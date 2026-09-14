package com.example.rinklnote.util

import com.example.rinklnote.ui.util.DisplayPreferences
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * 金额工具：App 内金额一律以「分」（整数 Long）表示、存储与运算，避免 Double 累加产生的
 * 浮点漂移（如 57.970000000000006）。
 *
 * 契约（App / Server / Web 三端一致）：
 * - 单位：分（minor unit）；字段名 `amountMinor` / `balanceMinor`
 * - 数据库列：`amount_minor` / `balance_minor`（INTEGER）
 * - 展示：统一走 [format] / [formatPlain]，**不要**再写 `minor / 100.0` 做格式化
 * - 输入解析：统一走 [parseMinor]，**禁止** `toDouble() * 100`
 *
 * 实现说明：格式化用纯整数拆分 + 手工千分位，不依赖 DecimalFormat / Locale，
 * 保证在任何系统语言下输出一致（「1,234.56」而非本地化分组符）。
 */
object Money {

    private val INPUT_PATTERN = Regex("""^\d+(\.\d{1,2})?$""")

    /**
     * 元字符串 → 分。最多 2 位小数。
     * 非法输入（空串、负数、字母、超过 2 位小数、含千分位等）一律返回 null，由调用方提示。
     */
    fun parseMinor(input: String): Long? {
        val s = input.trim()
        if (!INPUT_PATTERN.matches(s)) return null
        val dot = s.indexOf('.')
        val yuanPart = if (dot >= 0) s.substring(0, dot) else s
        val centPart = if (dot >= 0) (s.substring(dot + 1) + "00").substring(0, 2) else "00"
        val yuan = yuanPart.toLongOrNull() ?: return null
        val cents = centPart.toLongOrNull() ?: return null
        return yuan * 100 + cents
    }

    /**
     * 元（Double）→ 分，四舍五入保留 2 位（HALF_UP）。
     * 仅用于「金额已是 Double」的外部边界（如语音解析结果、第三方接口），
     * 内部输入一律走 [parseMinor] 以免二次引入浮点误差。
     */
    fun yuanToMinor(yuan: Double): Long =
        BigDecimal.valueOf(yuan).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()

    /** 分 → 两位小数的「元」字符串（带千分位，不含货币符号），如 123456 → "1,234.56"、-1230 → "-12.30"。 */
    fun formatPlain(minor: Long): String =
        (if (minor < 0) "-" else "") + formatAbs(abs(minor))

    /**
     * 分 → 「¥1,234.56」；负数形如「-¥12.30」。
     * 是否带 ¥ 由展示偏好 [DisplayPreferences.currencySymbolVisible] 决定
     * （设置页关闭货币符号后，本函数输出与 [formatPlain] 完全相同；[formatPlain] 不受影响）。
     * 读内存单例而非 Flow：调用点大量出现在 ViewModel / Canvas 等非组合上下文，只能同步取值。
     */
    fun format(minor: Long): String {
        val symbol = if (DisplayPreferences.currencySymbolVisible.value) "¥" else ""
        return (if (minor < 0) "-$symbol" else symbol) + formatAbs(abs(minor))
    }

    /** 绝对值的「1,234.56」形态：纯整数拆分 + 手工千分位，不依赖 Locale。 */
    private fun formatAbs(absMinor: Long): String {
        val yuan = absMinor / 100
        val cents = absMinor % 100
        val grouped = yuan.toString().reversed().chunked(3).joinToString(",").reversed()
        return "$grouped.${cents.toString().padStart(2, '0')}"
    }

    /**
     * 分 → 去掉多余 0 的「元」输入串（2500 → "25"、2550 → "25.5"、2505 → "25.05"）。
     * 用于把已存金额回填进输入框，观感与用户手输一致（不带千分位、无补零）。
     */
    fun toYuanInputString(minor: Long): String {
        val plain = formatPlain(abs(minor)).replace(",", "")
        val trimmed = if (plain.endsWith(".00")) plain.dropLast(3) else plain.removeSuffix("0")
        return if (minor < 0) "-$trimmed" else trimmed
    }
}
