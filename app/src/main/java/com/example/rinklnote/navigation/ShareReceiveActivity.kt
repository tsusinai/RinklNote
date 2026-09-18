package com.example.rinklnote.navigation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.rinklnote.MainActivity
import com.example.rinklnote.PendingQuickAdd
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.ShareTextParser

/**
 * 系统分享接收页（透明壳）：接收 `ACTION_SEND text/plain` → 解析出金额/分类/备注 →
 * 打开主界面并预填快速记账抽屉。
 *
 * 流程：解析走纯函数 [ShareTextParser]（金额规则复用/扩展语音记账 VoiceParser）；
 * 结果装入 [RinklNoteApp.setPendingQuickAdd]，再拉起 [MainActivity]——主界面里既有的
 * `pendingQuickAdd` 消费链（AppNavigation 的 LaunchedEffect）会自动落到记账页、
 * 打开抽屉并应用预填，冷启动 / 热启动（singleTop onNewIntent）两条路都覆盖。
 *
 * 未解析出金额时不阻塞：金额留空、原文进备注，用户打开抽屉后手动补金额。
 *
 * 体验细节：透明主题（无可见过渡界面）、noHistory + excludeFromRecents（不在
 * 最近任务里留壳）；拉起主界面用 NEW_TASK + CLEAR_TOP + SINGLE_TOP，复用存活实例。
 */
class ShareReceiveActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RinklNoteApp
        val parsed = ShareTextParser.parse(extractSharedText(intent))
        app.setPendingQuickAdd(
            PendingQuickAdd(
                amount = parsed.amount?.let { Money.toYuanInputString(Money.yuanToMinor(it)) },
                categoryName = parsed.categoryName,
                remark = parsed.remark.takeIf { it.isNotBlank() }
            )
        )
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        )
        finish()
    }

    /** 只认 ACTION_SEND + text/plain；文本取 EXTRA_TEXT，缺省时并入 EXTRA_SUBJECT（网页分享常只带标题）。 */
    private fun extractSharedText(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND) return ""
        if (intent.type != "text/plain") return ""
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        return listOf(subject, text).filter { it.isNotBlank() }.joinToString("\n")
    }
}
