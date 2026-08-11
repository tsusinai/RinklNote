package com.example.rinklnote.server.services

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money helpers.
 *
 * Storage columns and the public API keep amounts as `Double` for backward
 * compatibility, but money is a decimal quantity: summing binary doubles drifts
 * (e.g. 57.970000000000006) and exact comparisons then misfire. Every aggregation
 * output that crosses a boundary (API response, insight context, budget math) is
 * rounded to cents here so clients never observe float artifacts.
 */
object Money {
    /** Rounds a money value to 2 decimal places (half-up). */
    fun cents(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()
}
