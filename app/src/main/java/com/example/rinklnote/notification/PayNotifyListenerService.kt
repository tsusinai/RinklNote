package com.example.rinklnote.notification

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.util.PayNotificationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 支付通知监听服务（NotificationListenerService）——「支付通知一键记账」。
 *
 * **默认关闭 + 双重闸门**：既要在「我的」页打开设置开关（DataStore，默认 false），
 * 又要用户在系统设置里授予本应用「通知使用权」（授予后系统才会绑定本服务）。
 * 两道闸缺一不可；开关关闭时本服务对任何通知都不做反应。
 *
 * 工作流：微信 / 支付宝 / 主流银行包名白名单内的通知到达 → 纯本地解析
 * （[PayNotificationParser]，不联网、不上传）→ 发一条带「记一笔」动作的本地通知，
 * 点按经深链 `rinklnote://add?...` 直达快速记账抽屉预填；解析不出金额只发
 * 通用「记一笔」入口不带预填。
 *
 * 隐私口径：通知文本只在内存里即时解析，不落库、不发送；除本应用自己发的
 * 「记一笔」通知外不读通知正文以外内容。
 */
class PayNotifyListenerService : NotificationListenerService() {

    companion object {
        /** 支付来源白名单：微信 / 支付宝 + 主流银行（安卓包名）。白名单外一律忽略。 */
        private val WHITELIST_PACKAGES = setOf(
            "com.tencent.mm",                    // 微信
            "com.eg.android.AlipayGphone",       // 支付宝
            "com.icbc",                          // 工商银行
            "com.chinamworld.main",              // 建设银行
            "com.android.bankabc",               // 农业银行
            "com.chinamworld.bocmbci",           // 中国银行
            "com.bankcomm.maidianba",            // 交通银行
            "cmb.pb",                            // 招商银行
            "com.psbc.mobilephonebanking"        // 邮储银行
        )

        /** 系统是否已授予本应用「通知使用权」（androidx core 提供的读取口）。 */
        fun listenerGranted(context: Context): Boolean =
            androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)

        fun openListenerSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 开关缓存：绑定期内常驻收集，onNotificationPosted 里同步读，避免每条通知阻塞等 DataStore。 */
    @Volatile
    private var enabled: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 同一通知被系统重发（ranker update）时去重：记录最近已处理的 key。
    private val recentKeys = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 32
    }

    override fun onCreate() {
        super.onCreate()
        // 开关常驻收集：onNotificationPosted 在 binder 线程同步读缓存值，不做阻塞 IO。
        scope.launch {
            val app = applicationContext as RinklNoteApp
            app.settingsManager.payNotifyEnabled.collectLatest { on -> enabled = on }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!enabled) return
        val sbn = sbn ?: return
        if (sbn.packageName !in WHITELIST_PACKAGES) return
        if (sbn.isOngoing) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        // 同一通知的重复投递去重（同 key 短时间内只发一次「记一笔」）
        val dedupeKey = "${sbn.packageName}|${sbn.key}|${title ?: ""}|${text ?: ""}"
        synchronized(recentKeys) {
            val now = System.currentTimeMillis()
            val last = recentKeys[dedupeKey]
            if (last != null && now - last < 30_000) return
            recentKeys[dedupeKey] = now
        }

        val parsed = PayNotificationParser.parse(title, text)
        NotificationHelper.showQuickAddSuggestion(
            context = applicationContext,
            amountMinor = parsed.amountMinor,
            billType = parsed.billType,
            merchant = parsed.merchant
        )
    }
}
