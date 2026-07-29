package com.example.rinklnote.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("MM.dd")
private val dayOfWeekFormatter = DateTimeFormatter.ofPattern("EEE", Locale.CHINESE)
private val headerFormatter = DateTimeFormatter.ofPattern("yyyy-M-d")

fun Long.toDateString(): String {
    val localDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
    return localDate.format(dateFormatter)
}

fun Long.toDayOfWeek(): String {
    val localDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
    return localDate.format(dayOfWeekFormatter)
}

fun Long.toHeaderString(): String {
    val localDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
    return localDate.format(headerFormatter)
}

fun getMonthStart(): Long {
    val now = LocalDate.now()
    val start = now.withDayOfMonth(1)
    return start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

fun getNextMonthStart(): Long {
    val now = LocalDate.now()
    val next = now.withDayOfMonth(1).plusMonths(1)
    return next.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

fun String.toDateTimestamp(): Long {
    val parts = split("-")
    val localDate = LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
