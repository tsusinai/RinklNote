package com.example.rinklnote.ui.screen.profile

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.sync.SyncResult
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthState
import com.example.rinklnote.ui.viewmodel.AuthViewModel
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
    onLoginClick: () -> Unit,
    onBindQQClick: () -> Unit,
    onQqBotGuideClick: () -> Unit
) {
    val state by authViewModel.state.collectAsStateWithLifecycle()
    val themeMode by settingsManager.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val autoSync by settingsManager.autoSync.collectAsStateWithLifecycle(initialValue = true)
    val lastSync by tokenManager.lastSyncTime.collectAsStateWithLifecycle(initialValue = 0L)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var showPassword by remember { mutableStateOf(false) }
    var showUnbindQQ by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<String?>(null) }

    // Refresh profile when login state changes (login / logout / re-bind)
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) authViewModel.onEvent(AuthEvent.FetchProfile)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "userbox") {
            UserBox(state = state, onLogin = onLoginClick)
        }
        item(key = "func") {
            FuncBox(
                state = state,
                themeMode = themeMode,
                autoSync = autoSync,
                lastSync = lastSync,
                syncStatus = syncStatus,
                onPasswordClick = { showPassword = true },
                onBindQQClick = onBindQQClick,
                onQqBotGuideClick = onQqBotGuideClick,
                onUnbindQQ = { showUnbindQQ = true },
                onThemeClick = { showTheme = true },
                onAutoSyncChange = { coroutineScope.launch { settingsManager.setAutoSync(it) } },
                onSetAiDisabled = { authViewModel.onEvent(AuthEvent.SetAiDisabled(it)) },
                onSyncNow = {
                    coroutineScope.launch {
                        syncStatus = "同步中..."
                        syncStatus = when (val r = syncManager.sync()) {
                            is SyncResult.NotLoggedIn -> "未登录"
                            is SyncResult.Success -> "同步完成 (推送${r.pushed}条, 拉取${r.pulled}条)"
                            is SyncResult.Error -> r.message
                        }
                    }
                },
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
            text = { Text("退出将清除本地数据，未同步的账单可能丢失。确定退出？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogout = false
                    coroutineScope.launch { repository.clearLocalData() }
                    authViewModel.onEvent(AuthEvent.Logout)
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun UserBox(state: AuthState, onLogin: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!state.isLoggedIn) {
            AvatarBadge(character = "账")
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "未登录",
                    fontSize = 16.sp,
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
            AvatarBadge(character = state.accountPhone.firstOrNull()?.toString() ?: "账")
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = maskPhone(state.accountPhone),
                    fontSize = 16.sp,
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
private fun AvatarBadge(character: String) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = character,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun FuncBox(
    state: AuthState,
    themeMode: ThemeMode,
    autoSync: Boolean,
    lastSync: Long,
    syncStatus: String?,
    onPasswordClick: () -> Unit,
    onBindQQClick: () -> Unit,
    onQqBotGuideClick: () -> Unit,
    onUnbindQQ: () -> Unit,
    onThemeClick: () -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onSetAiDisabled: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onExportClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp)
    ) {
        FuncRow(label = "修改密码", onClick = onPasswordClick)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        FuncRow(
            label = if (state.isQQBound) "解绑QQ号" else "绑定QQ号",
            onClick = if (state.isQQBound) onUnbindQQ else onBindQQClick
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        FuncRow(label = "QQ 机器人绑定引导", onClick = onQqBotGuideClick)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        FuncRow(label = "导出账单 (CSV)", onClick = onExportClick)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        FuncRow(label = "主题", value = themeLabel(themeMode), onClick = onThemeClick)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("关闭 AI 主动推送", fontSize = 15.sp)
            Switch(checked = state.aiDisabled, onCheckedChange = onSetAiDisabled)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自动同步", fontSize = 15.sp)
            Switch(checked = autoSync, onCheckedChange = onAutoSyncChange)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Column(modifier = Modifier.padding(16.dp)) {
            Text("上次同步: ${formatSyncTime(lastSync)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (syncStatus != null) {
                Text(syncStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) { Text("立即同步") }
        }
        if (state.isLoggedIn) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            FuncRow(
                label = "退出登录",
                onClick = onLogoutClick,
                labelColor = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun FuncRow(
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    labelColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = labelColor)
        if (value != null) {
            Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
