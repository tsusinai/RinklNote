package com.example.rinklnote.ui.screen.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.sync.SyncResult
import com.example.rinklnote.ui.screen.login.LoginScreen
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthState
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    settingsManager: SettingsManager,
    tokenManager: TokenManager,
    syncManager: SyncManager,
    repository: BillRepository
) {
    val state by authViewModel.state.collectAsStateWithLifecycle()
    val themeMode by settingsManager.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val autoSync by settingsManager.autoSync.collectAsStateWithLifecycle(initialValue = true)
    val lastSync by tokenManager.lastSyncTime.collectAsStateWithLifecycle(initialValue = 0L)
    val coroutineScope = rememberCoroutineScope()

    var showLogin by remember { mutableStateOf(false) }
    var showBindQQ by remember { mutableStateOf(false) }
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
        item(key = "account") {
            AccountCard(
                state = state,
                onLogin = { showLogin = true },
                onBindQQ = { showBindQQ = true },
                onPasswordClick = { showPassword = true },
                onUnbindQQ = { showUnbindQQ = true }
            )
        }
        item(key = "settings") {
            SettingsCard(
                themeMode = themeMode,
                autoSync = autoSync,
                lastSync = lastSync,
                syncStatus = syncStatus,
                onThemeClick = { showTheme = true },
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
        if (state.isLoggedIn) {
            item(key = "logout") {
                Button(
                    onClick = { showLogout = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("退出登录", color = MaterialTheme.colorScheme.onError) }
            }
        }
    }

    if (showLogin) {
        LoginScreen(viewModel = authViewModel, onDismiss = { showLogin = false })
    }
    if (showBindQQ) {
        BindQQScreen(viewModel = authViewModel, onDismiss = { showBindQQ = false })
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
private fun AccountCard(
    state: AuthState,
    onLogin: () -> Unit,
    onBindQQ: () -> Unit,
    onPasswordClick: () -> Unit,
    onUnbindQQ: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("我的账号", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (!state.isLoggedIn) {
            Text("未登录", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录 / 注册") }
        } else {
            InfoRow("手机号", state.accountPhone.ifBlank { "加载中..." })
            InfoRow("QQ号", if (state.isQQBound) state.qqNumber else "未绑定")
            InfoRow("QQ机器人", if (state.botBound) "已绑定" else "未绑定")
            if (state.createdAt.isNotBlank()) {
                InfoRow("注册时间", state.createdAt.take(10))
            }
            Spacer(modifier = Modifier.height(2.dp))
            if (state.isQQBound) {
                OutlinedButton(onClick = onUnbindQQ, modifier = Modifier.fillMaxWidth()) { Text("解绑QQ号") }
            } else {
                Button(onClick = onBindQQ, modifier = Modifier.fillMaxWidth()) { Text("绑定QQ号") }
            }
            OutlinedButton(onClick = onPasswordClick, modifier = Modifier.fillMaxWidth()) { Text("修改密码") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SettingsCard(
    themeMode: ThemeMode,
    autoSync: Boolean,
    lastSync: Long,
    syncStatus: String?,
    onThemeClick: () -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("设置", fontSize = 16.sp, fontWeight = FontWeight.Medium)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onThemeClick() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("主题", fontSize = 14.sp)
            Text(themeLabel(themeMode), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自动同步", fontSize = 14.sp)
            Switch(checked = autoSync, onCheckedChange = onAutoSyncChange)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Text("上次同步: ${formatSyncTime(lastSync)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (syncStatus != null) {
            Text(syncStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) { Text("立即同步") }
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

private fun formatSyncTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "从未"
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}
