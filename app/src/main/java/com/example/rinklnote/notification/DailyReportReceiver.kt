package com.example.rinklnote.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.rinklnote.RinklNoteApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RinklNoteApp
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val zone = ZoneId.of("Asia/Shanghai")
                val now = ZonedDateTime.now(zone)
                val dayStart = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val report = app.repository.getDailyReport(dayStart, dayEnd)
                NotificationHelper.showDailyReport(context, report)
            } catch (e: Exception) {
                android.util.Log.e("DailyReport", "Failed to generate daily report", e)
            } finally {
                // 自愈重排（R5）：setExactAndAllowWhileIdle 是一次性的，不重排就「只响一次」。
                // 无论本次是否成功发出通知（无权限 / 异常都不该断链），都排下一次；用户已关闭则不排。
                try {
                    val enabled = app.settingsManager.dailyReportEnabled.first()
                    if (enabled) {
                        val hour = app.settingsManager.dailyReportHour.first()
                        val minute = app.settingsManager.dailyReportMinute.first()
                        schedule(context, hour, minute)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DailyReport", "Failed to reschedule next daily report", e)
                }
            }
        }
    }

    companion object {
        /** 下一次触达时刻：今天 hour:minute 已过则排明天同一时刻（Asia/Shanghai）。 */
        fun nextTriggerMillis(hour: Int, minute: Int, zone: ZoneId = ZoneId.of("Asia/Shanghai")): Long {
            val now = ZonedDateTime.now(zone)
            var trigger = now.toLocalDate()
                .atTime(hour, minute, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            if (trigger <= System.currentTimeMillis()) {
                trigger += 24 * 60 * 60 * 1000L
            }
            return trigger
        }

        fun schedule(context: Context, hour: Int, minute: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DailyReportReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val trigger = nextTriggerMillis(hour, minute)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, trigger, pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, trigger, pendingIntent
                    )
                }
            } else {
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP, trigger,
                    AlarmManager.INTERVAL_DAY, pendingIntent
                )
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DailyReportReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }
}
