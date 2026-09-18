package com.example.rinklnote.util

/**
 * 多币种汇率纯计算（Task 4.2，纯 JVM 可单测）。
 *
 * 口径（三端钉死契约）：`rates` 一律为「**每 1 单位该币种兑 CNY 的数**」（如 USD → 7.10），
 * 与 App 多币种页的 `DEMO_RATES_VS_CNY` 演示表同语义；本位币（base）选择只影响
 * **展示换算**（交叉汇率），不改 bills 整数分本位币存储约定。
 */
object CurrencyRates {

    /** 交叉汇率：每 1 单位 `code` 兑 `base` 的数。code == base → 恒 1.0；缺牌价/除零 → null。 */
    fun rateVsBase(code: String, base: String, ratesVsCny: Map<String, Double>): Double? {
        if (code == base) return 1.0
        val codeCny = ratesVsCny[code] ?: return null
        val baseCny = ratesVsCny[base] ?: return null
        if (baseCny == 0.0 || codeCny < 0 || baseCny < 0) return null
        return codeCny / baseCny
    }

    /**
     * 金额换算（整数分 → 整数分，HALF_UP）：amountMinor（单位 = code）→ base 金额分。
     * 缺牌价 → null（调用方保留原金额展示，不做有损换算）。
     */
    fun convertMinor(amountMinor: Long, code: String, base: String, ratesVsCny: Map<String, Double>): Long? {
        val rate = rateVsBase(code, base, ratesVsCny) ?: return null
        return Math.round(amountMinor * rate)
    }

    /**
     * 真汇率与演示表合并：服务端有**合法正值**牌价的币种覆盖演示值，其余（缺码 / 非法值 /
     * 离线失败）回落演示表——保证支持的币种清单永远有牌价可显示。
     */
    fun mergeRates(liveRatesVsCny: Map<String, Double>, fallbackVsCny: Map<String, Double>): Map<String, Double> =
        fallbackVsCny + liveRatesVsCny.filterValues { it > 0.0 && it.isFinite() }
}
