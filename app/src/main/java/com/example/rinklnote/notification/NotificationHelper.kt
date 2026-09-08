package com.example.rinklnote.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.rinklnote.MainActivity
import com.example.rinklnote.data.repository.DailyReport

object NotificationHelper {
    const val CHANNEL_DAILY_REPORT = "daily_report"
    const val NOTIFICATION_ID_DAILY_REPORT = 2001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_DAILY_REPORT,
                "每日日报",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "每日记账日报通知"
            }
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }
    }

    fun showDailyReport(context: Context, report: DailyReport) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sb = StringBuilder()
        sb.appendLine("━━ 今日记账日报 ━━")
        sb.appendLine()
        if (report.totalExpense > 0) {
            sb.appendLine("支出：￥%.2f".format(report.totalExpense))
            report.expenseCategories.take(3).forEach { cat ->
                sb.appendLine("  · %s：￥%.2f".format(cat.categoryName, cat.total))
            }
        }
        if (report.totalIncome > 0) {
            sb.appendLine("收入：￥%.2f".format(report.totalIncome))
            report.incomeCategories.take(3).forEach { cat ->
                sb.appendLine("  · %s：￥%.2f".format(cat.categoryName, cat.total))
            }
        }
        sb.appendLine("共 %d 笔".format(report.billCount))

        val notification = NotificationCompat.Builder(context, CHANNEL_DAILY_REPORT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("每日日报")
            .setContentText("今日支出 ￥%.2f".format(report.totalExpense))
            .setStyle(NotificationCompat.BigTextStyle().bigText(sb.toString()))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DAILY_REPORT, notification)
    }
}
