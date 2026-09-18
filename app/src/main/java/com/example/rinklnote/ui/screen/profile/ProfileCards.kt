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
import com.example.rinklnote.util.Money

/**
 * «我的»页的四张设置分组卡。
 *
 * 2026-09-11 重构：从 ProfileScreen.kt 拆出，页面主体只负责「收集状态 + 组装 + 分发弹窗」。
 * 每张卡都是纯展示组件——状态与回调全部由 ProfileScreen 传入，内部不含业务逻辑。
 * 卡片外壳统一走 [SettingsGroupCard]，行统一走 [SettingsRow]（行高 48dp）。
 *
 * 2026-09-17 再收敛：原「账户与安全」卡（AI 开关 / AI 接口 / 修改密码 / 三通道绑定）整体迁入
 * 「个人资料」页（personal-profile 路由）；原「个性化」卡里的头像 / 昵称行也迁入资料页，
 * 本卡只留外观与显示偏好。分组现为：数据与同步 → 通知 → 个性化 → 通用。
 */

/** «数据与同步» 组：自动同步开关 + 立即同步行（附「上次同步」小字）+ 同步结果。 */
@Composable
internal fun SyncCard(
    autoSync: Boolean,
    lastSync: Long,
    syncStatus: String?,
    onAutoSyncChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit
) {
    SettingsGroupCard(title = "数据与同步") {
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

/** «通知» 组：本地通知开关 + 通知时间 + QQ 日报推送 + 支付通知一键记账（默认关）。 */
@Composable
internal fun DailyReportCard(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    qqBot: Boolean,
    payNotify: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTimeClick: () -> Unit,
    onQqBotChange: (Boolean) -> Unit,
    onPayNotifyChange: (Boolean) -> Unit
) {
    SettingsGroupCard(title = "通知") {
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
        RinklDivider()
        // 支付通知监听：默认关；开启时由 ProfileScreen 弹权限说明并引导授予「通知使用权」
        SettingsRow(
            icon = R.drawable.ic_notification,
            label = "支付通知一键记账",
            trailing = { Switch(checked = payNotify, onCheckedChange = onPayNotifyChange) }
        )
    }
}

/**
 * «个性化» 组（2026-09-17 收敛）：主题外观（主题模式 / 自定义主题 / 背景图）+
 * 卡片白色蒙版开关（开启后卡片玻璃层垫白色蒙版，增强照片背景下的可读性）。
 * 个人形象（头像 / 昵称）已迁入「个人资料」页，此处不再重复。
 *
 * @param backgroundUri 同时也是「当前已设置的自选背景」——决定是否显示「移除背景」行；
 *   全 App 只有这一个背景来源（nav 层与 SettingsManager 同源），因此不再单列第二个参数。
 * @param cardOverlay 卡片白色蒙版开关状态（默认关闭）。
 */
@Composable
internal fun PersonalizationCard(
    themeMode: ThemeMode,
    backgroundUri: String?,
    cardOverlay: Boolean,
    quickAmountsMinor: List<Long>,
    onThemeClick: () -> Unit,
    onCustomThemeClick: () -> Unit,
    onBackgroundClick: () -> Unit,
    onRemoveBackground: () -> Unit,
    onCardOverlayChange: (Boolean) -> Unit,
    onQuickAmountsClick: () -> Unit
) {
    SettingsGroupCard(title = "个性化") {
        // —— 主题外观 ——
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
        // —— 小组件 ——
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_wallet,
            label = "小组件快捷金额",
            value = quickAmountsMinor.joinToString(" / ") { "¥${Money.toYuanInputString(it)}" },
            onClick = onQuickAmountsClick
        )
        // —— 可读性 ——
        RinklDivider()
        SettingsRow(
            icon = R.drawable.ic_eye_show,
            label = "卡片白色蒙版",
            trailing = { Switch(checked = cardOverlay, onCheckedChange = onCardOverlayChange) }
        )
        // 开关行的 12sp 说明小字（左缩进 52dp = 行内边距 16 + 图标 24 + 间距 12，与标签对齐）。
        Text(
            text = "增强照片背景下卡片可读性",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp)
        )
    }
}

/** «通用» 组：导出账单、年度账单分享图、版本信息、退出登录（红字置底）。 */
@Composable
internal fun AboutCard(
    isLoggedIn: Boolean,
    versionName: String,
    onExportClick: () -> Unit,
    onAnnualReportClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    SettingsGroupCard(title = "通用") {
        SettingsRow(
            icon = R.drawable.ic_export,
            label = "导出账单 (CSV)",
            onClick = onExportClick
        )
        RinklDivider()
        // 年度账单分享图：12 月热力格 + 年总收支 + Top5 分类 + 小盘贺词（Task 2.7）
        SettingsRow(
            icon = R.drawable.ic_chart,
            label = "年度账单",
            value = "分享图",
            onClick = onAnnualReportClick
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
