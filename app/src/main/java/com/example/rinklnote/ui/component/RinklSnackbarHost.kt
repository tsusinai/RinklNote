package com.example.rinklnote.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 统一的结构化反馈事件（体验优化 Task 2）：把原先分散的 Toast 文案归并为
 * 「语义类型 + 文案 + 可选操作」，由 [RinklSnackbarHost] 渲染成带操作的 Snackbar。
 *
 * - 成功类（[persistent] = false）短暂展示后自动消失；
 * - 失败 / 可操作类（[persistent] = true）**保持显示**，直到用户点操作或主动关闭。
 *
 * 「已同步到云端 / 同步失败」的判定来自 SyncManager 的推送结果（体验优化 Task 1），
 * 与同步状态流同源，不在反馈层另起一套判断。
 */
sealed interface BillFeedback {

    /** 面向用户的文案（中文，已组装好，宿主不再拼接）。 */
    val message: String

    /** 主操作按钮文案；null = 不显示操作按钮。 */
    val actionLabel: String? get() = null

    /** true = 失败/可撤销类：保持显示直到用户操作或关闭；false = 成功类：自动消失。 */
    val persistent: Boolean get() = false

    /** 账单已保存到本机（离线 / 未登录也走这里；云端结果随后由 SyncManager 推送结果另行反馈）。 */
    data class SavedLocally(override val message: String = "已保存到本机") : BillFeedback

    /** 已同步到云端（判定来自 SyncManager 的推送结果，与同步状态流同源）。 */
    data class SyncedToCloud(override val message: String = "已同步到云端") : BillFeedback

    /** 同步失败但账单仍保存在本机：附用户可读原因，提供「重试」。 */
    data class SyncFailedRetained(
        val reason: String,
        override val message: String = "同步失败，账单仍保存在本机 · $reason"
    ) : BillFeedback {
        override val actionLabel: String get() = "重试"
        override val persistent: Boolean get() = true
    }

    /** 账单已删除可撤销：[snapshot] 是删除前的本地快照，点「撤销」恢复 deleted=0、dirty=1。 */
    data class DeletedUndoable(
        val snapshot: Bill,
        override val message: String = "账单已删除"
    ) : BillFeedback {
        override val actionLabel: String get() = "撤销"
        override val persistent: Boolean get() = true
    }

    /** 撤销失败：删除状态保留（绝不误恢复成已同步），附原因并可「重试」撤销。 */
    data class UndoFailed(
        val snapshot: Bill,
        val reason: String,
        override val message: String = "撤销失败，账单仍为删除状态 · $reason"
    ) : BillFeedback {
        override val actionLabel: String get() = "重试"
        override val persistent: Boolean get() = true
    }

    /** 账单已保存可编辑（编辑页确认成功）：点「编辑」回到编辑页继续修改。 */
    data class SavedEditable(
        val bill: Bill,
        override val message: String = "账单已保存"
    ) : BillFeedback {
        override val actionLabel: String get() = "编辑"
    }

    /** 一般一次性提示（校验失败、恢复成功等），自动消失、无操作按钮。 */
    data class Plain(override val message: String) : BillFeedback
}

/**
 * 反馈控制器：AppNavigation 根部持有一个实例（跨路由存活），所有反馈经 [show] 展示。
 *
 * 不使用 SnackbarHostState 的排队语义——新反馈**顶替**当前反馈（否则失败类常驻期间
 * 「已保存到本机 → 已同步到云端」的升级提示会被压在队列里迟迟不出现）。
 */
class RinklFeedbackController(private val scope: CoroutineScope) {

    private val _current = mutableStateOf<BillFeedback?>(null)

    /** 当前正在展示的反馈；null = 无。宿主据此渲染并决定配色。 */
    var current: BillFeedback?
        get() = _current.value
        private set(value) { _current.value = value }

    private var autoDismissJob: Job? = null
    private var actionCallback: (() -> Unit)? = null

    /**
     * 展示一条反馈：新反馈顶替旧反馈；成功类 [SUCCESS_DISPLAY_MS] 后自动消失，
     * 失败/可操作类保持到用户点操作或主动关闭。
     * @param onAction 操作按钮点击时执行的动作（撤销 / 重试 / 编辑 / 继续记账等），可空。
     */
    fun show(feedback: BillFeedback, onAction: (() -> Unit)? = null) {
        autoDismissJob?.cancel()
        actionCallback = onAction
        current = feedback
        if (!feedback.persistent) {
            autoDismissJob = scope.launch {
                delay(SUCCESS_DISPLAY_MS)
                if (current == feedback) clear()
            }
        }
    }

    /** 主动关闭当前反馈（失败类右侧「✕」）。 */
    fun dismiss() = clear()

    /** 操作按钮点击：先关闭反馈，再执行挂接的动作。 */
    fun performAction() {
        val callback = actionCallback
        clear()
        callback?.invoke()
    }

    private fun clear() {
        autoDismissJob?.cancel()
        autoDismissJob = null
        actionCallback = null
        current = null
    }

    companion object {
        /** 成功类反馈的自动消失时长（毫秒），与页面浮层提示的节奏一致。 */
        internal const val SUCCESS_DISPLAY_MS = 2_200L
    }
}

/** 在组合内创建并记住反馈控制器（AppNavigation 根部持有，跨路由存活）。 */
@Composable
fun rememberRinklFeedbackController(): RinklFeedbackController {
    val scope = rememberCoroutineScope()
    return remember { RinklFeedbackController(scope) }
}

/**
 * 带操作按钮的 Snackbar 宿主（体验优化 Task 2）：在页面底部渲染 [BillFeedback]。
 *
 * 视觉沿用全 App 卡片风格：`rinkShadow` 阴影 + 15dp 圆角 + surface 底；操作按钮文字
 * 成功类走自定义主题「主题色」槽、失败类走 error 语义色，不新造颜色。
 */
@Composable
fun RinklSnackbarHost(
    controller: RinklFeedbackController,
    modifier: Modifier = Modifier
) {
    val feedback = controller.current
    AnimatedVisibility(
        visible = feedback != null,
        enter = fadeIn(animationSpec = Motion.Fade) +
            slideInVertically(animationSpec = Motion.SheetEnter) { it / 3 },
        exit = fadeOut(animationSpec = Motion.Fade) +
            slideOutVertically(animationSpec = Motion.SheetExit) { it / 3 },
        modifier = modifier
    ) {
        feedback?.let { fb ->
            RinklSnackbarRow(
                feedback = fb,
                onAction = controller::performAction,
                onClose = controller::dismiss
            )
        }
    }
}

@Composable
private fun RinklSnackbarRow(
    feedback: BillFeedback,
    onAction: () -> Unit,
    onClose: () -> Unit
) {
    // 失败/可撤销类用 error 语义色点缀；成功类操作文字走主题「主题色」槽（不硬编码新主色）。
    val actionColor = if (feedback.persistent) {
        MaterialTheme.colorScheme.error
    } else {
        LocalRinklColors.current.themeColor
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = feedback.message,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        )
        feedback.actionLabel?.let { label ->
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = actionColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        if (feedback.persistent) {
            // 失败类常驻：提供「✕」主动关闭；成功类自动消失，不放关闭按钮。
            Text(
                text = "✕",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(6.dp)
            )
        }
    }
}
