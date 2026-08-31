package com.example.rinklnote.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("MM.dd")
private val dayOfWeekFormatter = DateTimeFormatter.ofPattern("EEE", Locale.CHINESE)
private val headerFormatter = DateTimeFormatter.ofPattern("yyyy-M-d")

/**
 * 记账业务统一时区：Asia/Shanghai (E8)。
 * 多时区支持本轮不做——所有记账日期边界（月初/当日/落账时间戳）都锚定到该时区，
 * 避免设备时区不同导致「跨端日期不一致 / 月总结错位」。将来支持多时区时，仅改这一处。
 */
fun bookkeepingZone(): ZoneId = ZoneId.of("Asia/Shanghai")

fun Long.toDateString(): String {
    val localDate = Instant.ofEpochMilli(this).atZone(bookkeepingZone()).toLocalDate()
    return localDate.format(dateFormatter)
}

fun Long.toDayOfWeek(): String {
    val localDate = Instant.ofEpochMilli(this).atZone(bookkeepingZone()).toLocalDate()
    return localDate.format(dayOfWeekFormatter)
}

fun Long.toHeaderString(): String {
    val localDate = Instant.ofEpochMilli(this).atZone(bookkeepingZone()).toLocalDate()
    return localDate.format(headerFormatter)
}

fun getMonthStart(offsetMonths: Int = 0): Long {
    val now = LocalDate.now(bookkeepingZone())
    val start = now.withDayOfMonth(1).plusMonths(offsetMonths.toLong())
    return start.atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()
}

fun getNextMonthStart(offsetMonths: Int = 0): Long {
    val now = LocalDate.now(bookkeepingZone())
    val next = now.withDayOfMonth(1).plusMonths(offsetMonths.toLong() + 1)
    return next.atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()
}

fun String.toDateTimestamp(): Long {
    val parts = split("-")
    val localDate = LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    return localDate.atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()
}
