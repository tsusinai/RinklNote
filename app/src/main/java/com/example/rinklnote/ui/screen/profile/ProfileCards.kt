package com.example.rinklnote.ui.screen.profile

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.SettingsGroupCard
import com.example.rinklnote.ui.component.SettingsRow
import com.example.rinklnote.ui.viewmodel.AuthState

/**
 * 「我的」页的四张设置分组卡。
 *
 * 2026-09-11 重构：从 ProfileScreen.kt 拆出，页面主体只负责「收集状态 + 组装 + 分发弹窗」。
 * 每张卡都是纯展示组件——状态与回调全部由 ProfileScreen 传入，内部不含业务逻辑。
 * 卡片外壳统一走 [SettingsGroupCard]，行统一走 [SettingsRow]（行高 48dp）。
 */

/** «同步» 组：自动同步开关 + 立即同步行（附「上次同步」小字）+ 同步结果。 */
@Composable
internal fun SyncCard(
    autoSync: Boolean,
    lastSync: Long,
    syncStatus: String?,
    onAutoSyncChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit
) {
    SettingsGroupCard(title = "同步") {
        SettingsRow(
            icon = R.drawable.ic_sync,
            label = "自动同步",
            trailing = { Switch(checked = autoSync, onCheckedChange = onAutoSyncChange) }
        )
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_sync,
            label = "立即同步",
            value = "上次同步 ${formatSyncTime(lastSync)}",
            onClick = onSyncNow
        )
        if (syncStatus != null) {
            Text(
                text = syncStatus,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        }
    }
}

/** «日报通知» 组：本地通知开关 + 通知时间 + QQ 日报推送。 */
@Composable
internal fun DailyReportCard(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    qqBot: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTimeClick: () -> Unit,
    onQqBotChange: (Boolean) -> Unit
) {
    SettingsGroupCard(title = "日报通知") {
        SettingsRow(
            icon = R.drawable.ic_notification,
            label = "每日日报通知",
            trailing = { Switch(checked = enabled, onCheckedChange = onEnabledChange) }
        )
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_notification,
            label = "通知时间",
            value = "%02d:%02d".format(hour, minute),
            onClick = onTimeClick
        )
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_link,
            label = "QQ 日报推送",
            trailing = { Switch(checked = qqBot, onCheckedChange = onQqBotChange) }
        )
    }
}

/**
 * «账户与个性化» 组：主题、背景、修改密码、QQ 绑定、机器人引导、AI 推送。
 *
 * @param backgroundUri 同时也是「当前已设置的自选背景」——决定是否显示「移除背景」行；
 *   全 App 只有这一个背景来源（nav 层与 SettingsManager 同源），因此不再单列第二个参数。
 */
@Composable
internal fun AccountCard(
    state: AuthState,
    themeMode: ThemeMode,
    backgroundUri: String?,
    onThemeClick: () -> Unit,
    onCustomThemeClick: () -> Unit,
    onBackgroundClick: () -> Unit,
    onRemoveBackground: () -> Unit,
    onPasswordClick: () -> Unit,
    onBindQQClick: () -> Unit,
    onUnbindQQ: () -> Unit,
    onQqBotGuideClick: () -> Unit,
    onSetAiDisabled: (Boolean) -> Unit,
    onAiTokenClick: () -> Unit
) {
    SettingsGroupCard(title = "账户与个性化") {
        SettingsRow(
            icon = R.drawable.ic_theme,
            label = "主题",
            value = themeLabel(themeMode),
            onClick = onThemeClick
        )
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_theme,
            label = "自定义主题",
            value = "字体 · 主题 · 边框",
            onClick = onCustomThemeClick
        )
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_image,
            label = "选择背景",
            value = if (backgroundUri != null) "已设置" else "未设置",
            onClick = onBackgroundClick
        )
        if (backgroundUri != null) {
            RinklDivider()
            SettingsRow(
                icon = R.drawable.ic_image,
                iconTint = MaterialTheme.colorScheme.error,
                label = "移除背景",
                labelColor = MaterialTheme.colorScheme.error,
                onClick = onRemoveBackground
            )
        }
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_lock,
            label = "修改密码",
            onClick = onPasswordClick
        )
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_link,
            label = if (state.isQQBound) "解绑QQ号" else "绑定QQ号",
            onClick = if (state.isQQBound) onUnbindQQ else onBindQQClick
        )
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_link,
            label = "QQ 机器人绑定引导",
            onClick = onQqBotGuideClick
        )
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_ai,
            label = "AI 助手接口",
            onClick = onAiTokenClick
        )
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_ai,
            label = "AI 主动推送",
            trailing = {
                Switch(checked = !state.aiDisabled, onCheckedChange = { onSetAiDisabled(!it) })
            }
        )
    }
}

/** «关于» 组：导出账单、版本信息、退出登录（红字置底）。 */
@Composable
internal fun AboutCard(
    isLoggedIn: Boolean,
    versionName: String,
    onExportClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    SettingsGroupCard(title = "关于") {
        SettingsRow(
            icon = R.drawable.ic_export,
            label = "导出账单 (CSV)",
            onClick = onExportClick
        )
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_info,
            label = "版本",
            value = versionName
        )
        if (isLoggedIn) {
            RinklDivider()
            SettingsRow(
                icon = R.drawable.ic_logout,
                iconTint = MaterialTheme.colorScheme.error,
                label = "退出登录",
                labelColor = MaterialTheme.colorScheme.error,
                onClick = onLogoutClick
            )
        }
    }
}
