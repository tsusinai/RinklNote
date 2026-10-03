package com.example.rinklnote.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.rinklnote.RinklNoteApp
import kotlinx.coroutines.launch

/**
 * 自动记账「确认模式」通知动作（2026-10-03 方案 C）：
 * 「记上」→ [com.example.rinklnote.data.repository.CaptureRepository.confirmCapture]（落账 source=AUTO），
 * 「忽略」→ ignoreCapture。用应用级协程作用域执行，goAsync 窗口内尽快移交。
 */
class CaptureActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val captureId = intent.getLongExtra(EXTRA_CAPTURE_ID, -1L)
        if (captureId <= 0) return
        val app = context.applicationContext as? RinklNoteApp ?: return
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                when (intent.action) {
                    ACTION_CONFIRM -> app.captureRepository.confirmCapture(captureId)
                    ACTION_IGNORE -> app.captureRepository.ignoreCapture(captureId)
                }
                NotificationHelper.cancelCaptureConfirm(context, captureId)
            } catch (_: Exception) {
                // 处置失败不打扰：捕获仍为 PENDING，自动记账页可再处置
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONFIRM = "com.example.rinklnote.action.CAPTURE_CONFIRM"
        const val ACTION_IGNORE = "com.example.rinklnote.action.CAPTURE_IGNORE"
        const val EXTRA_CAPTURE_ID = "capture_id"
    }
}
