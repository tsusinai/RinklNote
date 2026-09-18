package com.example.rinklnote.server.services

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 服务端业务时区（2026-09-18 全端优化 Task 0.4）：与 App `util/DateUtil.bookkeepingZone()`
 * 同源，统一 Asia/Shanghai。审计时间戳（createdAt / correctedAt 等）与日期边界一律用它，
 * 避免宿主机系统时区不同导致跨端日期错位。
 */
object TimeUtil {
    /** 记账业务统一时区。 */
    val BOOKKEEPING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 业务时区下的当前时间（createdAt / correctedAt 等审计时间戳用）。 */
    fun now(): LocalDateTime = LocalDateTime.now(BOOKKEEPING_ZONE)

    /** 业务时区下的「今天」。 */
    fun today(): LocalDate = LocalDate.now(BOOKKEEPING_ZONE)

    /** 业务时区当日 0 点的 epoch 毫秒。 */
    fun todayStartMillis(): Long =
        today().atStartOfDay(BOOKKEEPING_ZONE).toInstant().toEpochMilli()
}
