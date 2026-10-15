package com.example.rinklnote.ui.screen.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.data.local.LastSyncFailure
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.sync.SyncStatus
import com.example.rinklnote.ui.component.MotionContent
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.RinklTextButton
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.motionClickable
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.util.Money

/** 本文件只负责展示和分发回调，设置、同步与导出仍由页面骨架处理。 */
@Composable
private fun ProfilePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier.fillMaxWidth().animateContentSize(Motion.EditorResize).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .then(applyCardGlass(shape)).padding(vertical = 4.dp),
        content = content
    )
}

/** 分组标题放在面板外，设置项保持统一左边线，减少重复图标和卡内标题。 */
@Composable
private fun ProfileGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val icon = when (title) {
        "外观" -> R.drawable.ic_redesign_profile_settings
        "提醒" -> R.drawable.ic_redesign_profile_sync
        "数据偏好" -> R.drawable.ic_redesign_profile_person
        else -> R.drawable.ic_redesign_profile_person
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = LocalRinklColors.current.themeColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ProfilePanel(content = content)
    }
}

/** 最小点击高度 52dp，说明随文字自然撑开，避免大字号下挤压开关和右侧值。 */
@Composable
private fun ProfileRow(
    label: String,
    value: String? = null,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
            .then(if (onClick != null) Modifier.motionClickable(role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurface)
            if (supporting != null) {
                Text(supporting, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            MotionContent(value, Modifier.widthIn(max = 132.dp)) {
                Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ProfileDivider() = RinklDivider(modifier = Modifier.padding(horizontal = 16.dp))

/** 常用操作并排展示；同步状态只在这一处说明，不再分成三个重复的设置行。 */
@Composable
internal fun ProfileToolsCard(
    isLoggedIn: Boolean,
    status: SyncStatus,
    lastSync: Long,
    lastFailure: LastSyncFailure?,
    onSyncNow: () -> Unit,
    onRetry: () -> Unit,
    onLoginClick: () -> Unit,
    onExportClick: () -> Unit,
    onAnnualReportClick: () -> Unit
) {
    val needsLogin = !isLoggedIn || status is SyncStatus.NeedLogin
    val needsRetry = status is SyncStatus.RetryPending ||
        (status == SyncStatus.Idle && lastFailure != null && lastFailure.time > lastSync)
    ProfilePanel {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            ProfileAction(R.drawable.ic_sync, if (needsLogin) "登录同步" else "同步账单",
                Modifier.weight(1f), enabled = status != SyncStatus.Syncing,
                onClick = if (needsLogin) onLoginClick else if (needsRetry) onRetry else onSyncNow)
            ProfileAction(R.drawable.ic_export, "导出账单", Modifier.weight(1f), onClick = onExportClick)
            ProfileAction(R.drawable.ic_chart, "年度账单", Modifier.weight(1f), onClick = onAnnualReportClick)
        }
        if (isLoggedIn) {
            val caption = when (status) {
                SyncStatus.Syncing -> "正在同步账单…"
                is SyncStatus.Synced -> "已同步 · ${formatSyncTime(status.time)}" +
                    if (status.pushed == 0 && status.pulled == 0) "" else " · 上传 ${status.pushed} 条，下载 ${status.pulled} 条"
                is SyncStatus.RetryPending -> "待重试 · ${status.reason}"
                is SyncStatus.NeedLogin -> "登录已过期，点击「登录同步」重新登录"
                SyncStatus.Idle -> when {
                    needsRetry -> "待重试 · ${lastFailure?.reason.orEmpty()}"
                    lastSync > 0 -> "上次同步 · ${formatSyncTime(lastSync)}"
                    else -> "尚未同步账单"
                }
            }
            ProfileDivider()
            MotionContent(caption, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(it, fontSize = 12.sp, lineHeight = 18.sp,
                    color = if (needsRetry || status is SyncStatus.NeedLogin) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfileAction(icon: Int, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalRinklColors.current
    Column(
        modifier = modifier.motionClickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 84.dp).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(colors.themeColor.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp),
                tint = (colors.iconButtonColor ?: colors.themeColor).copy(alpha = if (enabled) 1f else 0.45f))
        }
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

/** 主题与配色共享入口，背景操作共享入口，可读性说明放在对应开关内部。 */
@Composable
internal fun PersonalizationCard(
    themeMode: ThemeMode,
    backgroundUri: String?,
    cardOverlay: Boolean,
    onThemeClick: () -> Unit,
    onBackgroundClick: () -> Unit,
    onCardOverlayChange: (Boolean) -> Unit
) {
    ProfileGroup("外观") {
        ProfileRow("主题与配色", themeLabel(themeMode), onClick = onThemeClick)
        ProfileDivider()
        ProfileRow("背景图片", if (backgroundUri != null) "已设置" else "默认", onClick = onBackgroundClick)
        ProfileDivider()
        ProfileRow("卡片对比度", supporting = "让背景上的内容更清晰",
            trailing = { Switch(checked = cardOverlay, onCheckedChange = onCardOverlayChange) })
    }
}

/** 日报关闭时收起时间设置；QQ 推送和支付提醒保留独立开关。 */
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
    ProfileGroup("提醒") {
        ProfileRow("每日日报", trailing = { Switch(checked = enabled, onCheckedChange = onEnabledChange) })
        AnimatedVisibility(visible = enabled,
            enter = fadeIn(Motion.Reveal) + expandVertically(Motion.EditorResize),
            exit = fadeOut(Motion.Reveal) + shrinkVertically(Motion.EditorResize)) {
            Column {
                ProfileDivider()
                ProfileRow("通知时间", "%02d:%02d".format(hour, minute), onClick = onTimeClick)
            }
        }
        ProfileDivider()
        ProfileRow("QQ 日报", trailing = { Switch(checked = qqBot, onCheckedChange = onQqBotChange) })
        ProfileDivider()
        ProfileRow("支付提醒", supporting = "从支付通知快速记一笔",
            trailing = { Switch(checked = payNotify, onCheckedChange = onPayNotifyChange) })
    }
}

@Composable
internal fun DataPreferencesCard(
    autoSync: Boolean,
    quickAmountsMinor: List<Long>,
    widgetPrivacyEnabled: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    onWidgetPrivacyChange: (Boolean) -> Unit,
    onQuickAmountsClick: () -> Unit
) {
    ProfileGroup("数据偏好") {
        ProfileRow("自动同步", trailing = { Switch(checked = autoSync, onCheckedChange = onAutoSyncChange) })
        ProfileDivider()
        ProfileRow("桌面隐私保护", supporting = "隐藏金额、预算比例和分类排行；关闭后桌面可见",
            trailing = { Switch(checked = widgetPrivacyEnabled, onCheckedChange = onWidgetPrivacyChange) })
        ProfileDivider()
        ProfileRow("小组件金额", quickAmountsMinor.joinToString(" / ") { Money.format(it) }, onClick = onQuickAmountsClick)
    }
}

/** 版本是次要信息，不占用整张设置卡；退出仍走页面原有确认与同步流程。 */
@Composable
internal fun ProfileFooter(isLoggedIn: Boolean, versionName: String, onLogoutClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (isLoggedIn) {
            RinklTextButton(onClick = onLogoutClick) { Text("退出登录", color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
        }
        Text("记一笔 · v$versionName", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
    }
}
