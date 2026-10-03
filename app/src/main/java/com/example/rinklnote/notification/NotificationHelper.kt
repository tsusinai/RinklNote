package com.example.rinklnote.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.rinklnote.MainActivity
import com.example.rinklnote.data.repository.DailyReport
import com.example.rinklnote.util.Money

object NotificationHelper {
    const val CHANNEL_DAILY_REPORT = "daily_report"
    const val NOTIFICATION_ID_DAILY_REPORT = 2001
    const val CHANNEL_PAY_NOTIFY = "pay_notify"

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
            // 支付通知一键记账的本地提醒渠道：用户主动开启（默认关），静音级别足够
            val payChannel = NotificationChannel(
                CHANNEL_PAY_NOTIFY,
                "支付提醒记账",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "检测到微信/支付宝/银行卡支付通知后，提醒一键记一笔"
            }
            NotificationManagerCompat.from(context).createNotificationChannel(payChannel)
        }
    }

    /**
     * 「记一笔」一键记账通知（支付通知监听触发）。
     *
     * @param amountMinor 解析出的金额（分）；null = 未能解析（发通用入口，不带预填）
     * @param billType "EXPENSE"/"INCOME"，决定金额前缀符号与文案
     * @param merchant 解析出的商户/对方，可空
     */
    fun showQuickAddSuggestion(context: Context, amountMinor: Long?, billType: String, merchant: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
        }

        // 深链复用现有 rinklnote://add 预填通路（MainActivity intent-filter），setPackage 防劫持
        fun quickAddIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("rinklnote://add").buildUpon().apply {
                if (amountMinor != null) {
                    appendQueryParameter("amount", Money.toYuanInputString(amountMinor))
                    appendQueryParameter("type", billType)
                }
                merchant?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("remark", it) }
            }.build()
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context, (System.currentTimeMillis() and 0xFFFF).toInt(), quickAddIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (amountMinor != null) {
            val sign = if (billType == "INCOME") "+" else "-"
            buildString {
                append("检测到${if (billType == "INCOME") "收入" else "支出"} ")
                append(sign)
                append(Money.format(amountMinor))
                merchant?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                append("，点按记一笔")
            }
        } else {
            "有笔支付可能没记账，点按记一笔"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_PAY_NOTIFY)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle("记一笔")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    /**
     * 自动记账「确认模式」的随手确认通知（2026-10-03 方案 C）：
     * 「记上」直接落库（source=AUTO），「忽略」丢弃——不用进 App 就完成处置。
     * 动作经 [CaptureActionReceiver] 广播落地；通知 id 用 captureId（同一条捕获重发时替换而非堆积）。
     */
    fun showCaptureConfirm(context: Context, captureId: Long, amountMinor: Long, billType: String, merchant: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
        }

        fun actionIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            ((captureId % 100000) and 0xFFFF).toInt() + if (action == CaptureActionReceiver.ACTION_CONFIRM) 1 else 2,
            Intent(context, CaptureActionReceiver::class.java).apply {
                this.action = action
                putExtra(CaptureActionReceiver.EXTRA_CAPTURE_ID, captureId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sign = if (billType == "INCOME") "+" else "-"
        val title = if (billType == "INCOME") "自动记账 · 收入" else "自动记账"
        val text = buildString {
            append(sign).append(Money.format(amountMinor))
            merchant?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            append("，记上这笔吗？")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_PAY_NOTIFY)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(0, "记上", actionIntent(CaptureActionReceiver.ACTION_CONFIRM))
            .addAction(0, "忽略", actionIntent(CaptureActionReceiver.ACTION_IGNORE))
            // 点通知本体进 App（对账底座在自动记账页）；无深链时回落主界面
            .setContentIntent(
                PendingIntent.getActivity(
                    context, ((captureId % 100000) and 0xFFFF).toInt() + 3,
                    context.packageManager.getLaunchIntentForPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(captureId.toInt(), notification)
    }

    /** 撤掉某条捕获的确认通知（已在页内处置 / 落账时同步清理）。 */
    fun cancelCaptureConfirm(context: Context, captureId: Long) {
        NotificationManagerCompat.from(context).cancel(captureId.toInt())
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
