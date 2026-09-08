package com.example.rinklnote.ui.screen.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.rinklnote.ui.component.rinkShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.sync.SyncResult
import com.example.rinklnote.notification.DailyReportReceiver
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthState
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import com.example.rinklnote.ui.viewmodel.AiTokenViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import com.example.rinklnote.util.bookkeepingZone
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    settingsManager: SettingsManager,
    tokenManager: TokenManager,
    syncManager: SyncManager,
    repository: BillRepository,
    aiTokenViewModel: AiTokenViewModel,
    onLoginClick: () -> Unit,
    onBindQQClick: () -> Unit,
    onQqBotGuideClick: () -> Unit
) {
    val state by authViewModel.state.collectAsStateWithLifecycle()
    val themeMode by settingsManager.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val autoSync by settingsManager.autoSync.collectAsStateWithLifecycle(initialValue = true)
    val backgroundUri by settingsManager.backgroundUri.collectAsStateWithLifecycle(initialValue = null)
    val lastSync by tokenManager.lastSyncTime.collectAsStateWithLifecycle(initialValue = 0L)
    val dailyReportEnabled by settingsManager.dailyReportEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dailyReportHour by settingsManager.dailyReportHour.collectAsStateWithLifecycle(initialValue = 9)
    val dailyReportMinute by settingsManager.dailyReportMinute.collectAsStateWithLifecycle(initialValue = 0)
    val dailyReportQqBot by settingsManager.dailyReportQqBot.collectAsStateWithLifecycle(initialValue = false)
    var showTimePicker by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // 图库选背景：把结果拷到应用内部存储（自建文件路径，跨重启稳定），再写回设置。
    val pickBackgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val path = copyBackgroundToInternal(context, uri)
                if (path != null) settingsManager.setBackgroundUri(path)
                else Toast.makeText(context, "未读到图片，请换一张重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var showPassword by remember { mutableStateOf(false) }
    var showUnbindQQ by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    var showUnsynced by remember { mutableStateOf(false) }
    var unsyncedCount by remember { mutableStateOf(0) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var showAiToken by remember { mutableStateOf(false) }

    // 登出「先推后清」：先把当前用户的 pending/dirty 尽力推到它自己的服务器，成功后清空本机
    // per-user 数据。这样旧用户的数据落到旧账号，新用户登入时零遗产，绝不跨用户污染。
    val finishLogout: () -> Unit = {
        coroutineScope.launch {
            repository.clearLocalData()
            authViewModel.onEvent(AuthEvent.Logout)
        }
    }
    val attemptLogout: () -> Unit = {
        coroutineScope.launch {
            if (repository.countUnsynced() == 0L) {
                finishLogout()
                return@launch
            }
            // 尽力同步（推送当前用户未同步项）。成功后重新统计，判断是否已排空。
            syncManager.sync()
            if (repository.countUnsynced() == 0L) {
                finishLogout()
            } else {
                unsyncedCount = repository.countUnsynced().toInt()
                showUnsynced = true
            }
        }
    }
    val versionName = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    // Refresh profile when login state changes (login / logout / re-bind)
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) authViewModel.onEvent(AuthEvent.FetchProfile)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            ProfileHeader(state = state, onLogin = onLoginClick)
        }
        item(key = "sync") {
            SyncCard(
                autoSync = autoSync,
                lastSync = lastSync,
                syncStatus = syncStatus,
                onAutoSyncChange = { coroutineScope.launch { settingsManager.setAutoSync(it) } },
                onSyncNow = {
                    coroutineScope.launch {
                        syncStatus = "同步中..."
                        syncStatus = when (val r = syncManager.sync()) {
                            is SyncResult.NotLoggedIn -> "未登录"
                            is SyncResult.Success -> "同步完成 (推送${r.pushed}条, 拉取${r.pulled}条)"
                            is SyncResult.Error -> r.message
                        }
                    }
                }
            )
        }
        item(key = "account") {
            AccountCard(
                state = state,
                themeMode = themeMode,
                backgroundUri = backgroundUri,
                onThemeClick = { showTheme = true },
                onBackgroundClick = {
                    pickBackgroundLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemoveBackground = { coroutineScope.launch { settingsManager.setBackgroundUri(null) } },
                onPasswordClick = { showPassword = true },
                onBindQQClick = onBindQQClick,
                onUnbindQQ = { showUnbindQQ = true },
                onQqBotGuideClick = onQqBotGuideClick,
                onSetAiDisabled = { authViewModel.onEvent(AuthEvent.SetAiDisabled(it)) },
                onAiTokenClick = { showAiToken = true }
            )
        }
        item(key = "about") {
            AboutCard(
                isLoggedIn = state.isLoggedIn,
                versionName = versionName,
                onExportClick = {
                    coroutineScope.launch {
                        val bills = repository.observeAllBills().first()
                        exportBills(context, bills)
                    }
                },
                onLogoutClick = { showLogout = true }
            )
        }
    }

    if (showPassword) {
        PasswordChangeDialog(viewModel = authViewModel, onDismiss = { showPassword = false })
    }
    if (showUnbindQQ && state.isQQBound) {
        AlertDialog(
            onDismissRequest = { showUnbindQQ = false },
            title = { Text("解绑QQ号") },
            text = { Text("解绑后可通过QQ机器人快捷记账功能将关闭。确定解绑？") },
            confirmButton = {
                TextButton(onClick = {
                    showUnbindQQ = false
                    authViewModel.onEvent(AuthEvent.UnbindQQ)
                }) { Text("解绑") }
            },
            dismissButton = { TextButton(onClick = { showUnbindQQ = false }) { Text("取消") } }
        )
    }
    if (showTheme) {
        ThemeDialog(
            themeMode = themeMode,
            onSelect = { mode ->
                showTheme = false
                coroutineScope.launch { settingsManager.setThemeMode(mode) }
            },
            onDismiss = { showTheme = false }
        )
    }
    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("退出登录") },
            text = { Text("退出前会先把未同步的账单推送到你的账号，再清除本地数据。确定退出？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogout = false
                    attemptLogout()
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("取消") } }
        )
    }

    if (showUnsynced) {
        AlertDialog(
            onDismissRequest = { showUnsynced = false },
            title = { Text("有未同步的数据") },
            text = {
                Text(
                    "当前有 $unsyncedCount 条账单/账户尚未同步，无法干净退出。\n\n" +
                        "·「仍然登出并丢弃」将丢失这些未同步数据。\n" +
                        "·「联网重试」会先把它们推送到你的账号再退出。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnsynced = false
                    finishLogout()
                }) { Text("仍然登出并丢弃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsynced = false
                    attemptLogout()
                }) { Text("联网重试") }
            }
        )
    }

    if (showAiToken) {
        AiTokenDialog(
            viewModel = aiTokenViewModel,
            onDismiss = { showAiToken = false }
        )
    }
}

/** 通栏宽头：左大号头像 + 右昵称/副行；未登录给「登录/注册」胶囊按钮。 */
@Composable
private fun ProfileHeader(state: AuthState, onLogin: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!state.isLoggedIn) {
            AvatarBadge(character = "账", size = 64.dp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "未登录",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "登录后即可云端同步账单",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onLogin) { Text("登录 / 注册") }
        } else {
            AvatarBadge(character = state.accountPhone.firstOrNull()?.toString() ?: "账", size = 64.dp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = maskPhone(state.accountPhone),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "QQ号 ${if (state.isQQBound) state.qqNumber else "未绑定"} · 机器人 ${if (state.botBound) "已绑定" else "未绑定"}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.createdAt.isNotBlank()) {
                    Text(
                        text = "注册时间 ${state.createdAt.take(10)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarBadge(character: String, size: androidx.compose.ui.unit.Dp = 56.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = character,
            fontSize = (size.value * 0.43f).sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** 分组卡片：浅色小标题（可选）+ 多行设置。 */
@Composable
private fun GroupCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 2.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)
            )
        }
        content()
    }
}

/** 单行设置：前置图标 + 标签 + 右侧值/「>」/开关。 */
@Composable
private fun SettingsRow(
    icon: Int? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    label: String,
    value: String? = null,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
        }
        Text(
            text = label,
            fontSize = 15.sp,
            color = labelColor,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** «同步» 组：自动同步开关 + 立即同步行（附「上次同步」小字）+ 同步结果。 */
@Composable
private fun SyncCard(
    autoSync: Boolean,
    lastSync: Long,
    syncStatus: String?,
    onAutoSyncChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit
) {
    GroupCard(title = "同步") {
        SettingsRow(
            icon = R.drawable.ic_sync,
            label = "自动同步",
            trailing = { Switch(checked = autoSync, onCheckedChange = onAutoSyncChange) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow(
            icon = R.drawable.ic_sync,
            label = "立即同步",
            value = "上次同步 ${formatSyncTime(lastSync)}",
            onClick = onSyncNow
        )
        if (syncStatus != null) {
            Text(
                text = syncStatus,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        }
    }
}

/** «账户与个性化» 组：主题、背景、修改密码、QQ 绑定、机器人引导、AI 推送。 */
@Composable
private fun AccountCard(
    state: AuthState,
    themeMode: ThemeMode,
    backgroundUri: String?,
    onThemeClick: () -> Unit,
    onBackgroundClick: () -> Unit,
    onRemoveBackground: () -> Unit,
    onPasswordClick: () -> Unit,
    onBindQQClick: () -> Unit,
    onUnbindQQ: () -> Unit,
    onQqBotGuideClick: () -> Unit,
    onSetAiDisabled: (Boolean) -> Unit,
    onAiTokenClick: () -> Unit
) {
    GroupCard(title = "账户与个性化") {
        SettingsRow(
            icon = R.drawable.ic_theme,
            label = "主题",
            value = themeLabel(themeMode),
            onClick = onThemeClick
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow(
            icon = R.drawable.ic_image,
            label = "选择背景",
            value = if (backgroundUri != null) "已设置" else "未设置",
            onClick = onBackgroundClick
        )
        if (backgroundUri != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SettingsRow(
                icon = R.drawable.ic_image,
                iconTint = MaterialTheme.colorScheme.error,
                label = "移除背景",
                labelColor = MaterialTheme.colorScheme.error,
                onClick = onRemoveBackground
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow(
            icon = R.drawable.ic_lock,
            label = "修改密码",
            onClick = onPasswordClick
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow(
            icon = R.drawable.ic_link,
            label = if (state.isQQBound) "解绑QQ号" else "绑定QQ号",
            onClick = if (state.isQQBound) onUnbindQQ else onBindQQClick
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow(
            icon = R.drawable.ic_link,
            label = "QQ 机器人绑定引导",
            onClick = onQqBotGuideClick
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow(
            icon = R.drawable.ic_ai,
            label = "AI 助手接口",
            onClick = onAiTokenClick
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
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
private fun AboutCard(
    isLoggedIn: Boolean,
    versionName: String,
    onExportClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    GroupCard(title = "关于") {
        SettingsRow(
            icon = R.drawable.ic_export,
            label = "导出账单 (CSV)",
            onClick = onExportClick
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow(
            icon = R.drawable.ic_info,
            label = "版本",
            value = versionName
        )
        if (isLoggedIn) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
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

@Composable
private fun PasswordChangeDialog(viewModel: AuthViewModel, onDismiss: () -> Unit) {
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
                    Text(errorMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (successMsg != null) {
                    Text(successMsg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.onEvent(AuthEvent.ChangePassword) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun TimePickerDialog(
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
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    androidx.compose.material3.OutlinedButton(onClick = {
                        selectedHour = (selectedHour - 1 + 24) % 24
                    }) { Text("− 小时") }
                    androidx.compose.material3.OutlinedButton(onClick = {
                        selectedHour = (selectedHour + 1) % 24
                    }) { Text("+ 小时") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    androidx.compose.material3.OutlinedButton(onClick = {
                        selectedMinute = (selectedMinute - 5 + 60) % 60
                    }) { Text("− 5分") }
                    androidx.compose.material3.OutlinedButton(onClick = {
                        selectedMinute = (selectedMinute + 5) % 60
                    }) { Text("+ 5分") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedHour, selectedMinute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ThemeDialog(
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
                            .clickable { onSelect(mode) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = { onSelect(mode) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(themeLabel(mode), fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun maskPhone(phone: String): String =
    if (phone.length >= 7) phone.replaceRange(3, 7, "****") else phone

private fun formatSyncTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "从未"
    return Instant.ofEpochMilli(epochMillis)
        .atZone(bookkeepingZone())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

/** 把图库选的照片拷进应用内部存储并返回其绝对路径；PhotoPicker 的 content:// 仅在会话内可读，
 *  拷到 filesDir 后跨重启稳定，Coil 可直接按路径加载。失败返回 null。 */
private fun copyBackgroundToInternal(context: Context, uri: Uri): String? {
    return try {
        val dir = File(context.filesDir, "backgrounds").apply { mkdirs() }
        val file = File(dir, "bg_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}

private fun exportBills(context: android.content.Context, bills: List<Bill>) {
    val sb = StringBuilder("\uFEFF")
    sb.appendLine("日期,类型,分类,子分类,金额,备注,来源")
    val zone = bookkeepingZone()
    bills.forEach { b ->
        val date = Instant.ofEpochMilli(b.date).atZone(zone).toLocalDate().toString()
        sb.appendLine(
            listOf(date, b.billType, b.categoryName, b.subCategoryName ?: "", b.amount, b.remark ?: "", b.source)
                .joinToString(",") { "\"" + it.toString().replace("\"", "\"\"") + "\"" }
        )
    }
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "rinklnote.csv").apply { writeText(sb.toString(), Charsets.UTF_8) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "导出账单"))
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, "未找到可分享的应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}
