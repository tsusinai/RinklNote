package com.example.rinklnote.ui.screen.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import com.example.rinklnote.ui.viewmodel.BotChannel

/**
 * 「我的」页同一时刻**至多只有一个弹窗**，用单一 sealed 状态表达。
 *
 * 2026-09-11 重构：此前是 7 个互不相干的 `var showXxx by remember { mutableStateOf(false) }`
 * 加 7 个并列的 `if (showXxx) { ... }`，且「未同步条数」另用一个 `unsyncedCount` 变量跟着走。
 * 现在收敛成一个状态 + 一个 `when` 分发点，新增弹窗只需加一个分支（编译器会提醒穷尽性）。
 *
 * 2026-09-17：修改密码 / 解绑通道 / AI Token 三类弹窗随账号事务迁入「个人资料」页
 * （PersonalProfileDialog），本状态机不再承载；PasswordDialog / UnbindBotDialog /
 * AiTokenDialog 三个弹窗组件仍留在本文件供资料页复用（同包 internal）。
 */
sealed interface ProfileDialog {
    /** 主题模式选择（跟随系统 / 浅色 / 深色） */
    data object Theme : ProfileDialog

    /** 退出登录二次确认 */
    data object Logout : ProfileDialog

    /** 日报通知时间选择 */
    data object TimePicker : ProfileDialog

    /** 登出时仍有未同步数据；`count` 为未同步条数，收进状态避免额外变量 */
    data class Unsynced(val count: Int) : ProfileDialog
}

/** 修改密码弹窗（读写 [AuthViewModel] 的 oldPassword/newPassword 与提示信息）。 */
@Composable
internal fun PasswordDialog(viewModel: AuthViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.oldPassword,
                    onValueChange = { viewModel.onEvent(AuthEvent.OldPasswordChanged(it)) },
                    label = { Text("原密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = state.newPassword,
                    onValueChange = { viewModel.onEvent(AuthEvent.NewPasswordChanged(it)) },
                    label = { Text("新密码(至少6位)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                val errorMsg = state.error
                val successMsg = state.successMessage
                if (errorMsg != null) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
                if (successMsg != null) {
                    Text(successMsg, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.onEvent(AuthEvent.ChangePassword) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 解绑机器人通道二次确认（QQ / 飞书 / 企业微信按通道分发 [AuthEvent.UnbindBot]）。 */
@Composable
internal fun UnbindBotDialog(channel: BotChannel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解绑${channel.label}") },
        text = { Text("解绑后将无法继续用${channel.guideApp}给机器人发消息记账。确定解绑？") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("解绑") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 主题模式单选弹窗（选中即回调，无需「确定」）。 */
@Composable
internal fun ThemeModeDialog(
    themeMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题") },
        text = {
            Column {
                ThemeMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable { onSelect(mode) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = { onSelect(mode) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(themeLabel(mode), fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 退出登录二次确认（真正「先推后清」的逻辑在 ProfileScreen 回调里）。 */
@Composable
internal fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("退出登录") },
        text = { Text("退出前会先把未同步的账单推送到你的账号，再清除本地数据。确定退出？") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("退出") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 「仍有未同步数据」三选一弹窗：先联网重试（推荐）→ 仍失败才允许丢弃登出。
 *
 * @param count 未同步的账单/账户条数
 * @param onDiscard 仍然登出并丢弃未同步数据
 * @param onRetry 联网重试推送后再登出
 */
@Composable
internal fun UnsyncedDataDialog(
    count: Int,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("有未同步的数据") },
        text = {
            Text(
                "当前有 $count 条账单/账户尚未同步，无法干净退出。\n\n" +
                    "·「仍然登出并丢弃」将丢失这些未同步数据。\n" +
                    "·「联网重试」会先把它们推送到你的账号再退出。"
            )
        },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text("仍然登出并丢弃", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onRetry) { Text("联网重试") } }
    )
}

/** 日报通知时间选择：小时 ±1、分钟 ±5，点「确定」才回调。 */
@Composable
internal fun TimePickerDialog(
    hour: Int,
    minute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedHour by remember { mutableIntStateOf(hour) }
    var selectedMinute by remember { mutableIntStateOf(minute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置日报通知时间") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "%02d:%02d".format(selectedHour, selectedMinute),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = { selectedHour = (selectedHour - 1 + 24) % 24 }) {
                        Text("− 小时")
                    }
                    OutlinedButton(onClick = { selectedHour = (selectedHour + 1) % 24 }) {
                        Text("+ 小时")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = { selectedMinute = (selectedMinute - 5 + 60) % 60 }) {
                        Text("− 5分")
                    }
                    OutlinedButton(onClick = { selectedMinute = (selectedMinute + 5) % 60 }) {
                        Text("+ 5分")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedHour, selectedMinute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
