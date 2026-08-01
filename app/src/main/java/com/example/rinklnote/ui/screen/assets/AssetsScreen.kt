package com.example.rinklnote.ui.screen.assets

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import androidx.compose.runtime.LaunchedEffect
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.sync.SyncResult
import com.example.rinklnote.ui.screen.login.LoginScreen
import com.example.rinklnote.ui.screen.profile.BindQQScreen
import com.example.rinklnote.ui.viewmodel.AssetsViewModel
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

private fun accountIconRes(name: String): Int = when (name) {
    "微信" -> R.drawable.ic_wechat
    "支付宝" -> R.drawable.ic_alipay
    "默认" -> R.drawable.ic_default_account
    else -> R.drawable.ic_default_account
}

@Composable
fun AssetsScreen(
    viewModel: AssetsViewModel,
    authViewModel: AuthViewModel,
    syncManager: SyncManager
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var showLogin by remember { mutableStateOf(false) }
    var showBindQQ by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<String?>(null) }

    // Auto-sync on tab open when logged in
    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn && authState.isQQBound) {
            syncStatus = "同步中..."
            val result = syncManager.sync()
            syncStatus = when (result) {
                is SyncResult.NotLoggedIn -> null
                is SyncResult.Success -> {
                    val total = result.pushed + result.pulled
                    if (total > 0) "同步完成 (推送${result.pushed}条, 拉取${result.pulled}条)" else null
                }
                is SyncResult.Error -> result.message
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "资产管理",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // QQ Sync Section
        SyncSection(
            authState = authState,
            syncStatus = syncStatus,
            onLogin = { showLogin = true },
            onBindQQ = { showBindQQ = true },
            onSync = {
                coroutineScope.launch {
                    syncStatus = "同步中..."
                    val result = syncManager.sync()
                    syncStatus = when (result) {
                        is SyncResult.NotLoggedIn -> "未登录"
                        is SyncResult.Success -> "同步完成 (推送${result.pushed}条, 拉取${result.pulled}条)"
                        is SyncResult.Error -> result.message
                    }
                }
            },
            onLogout = { authViewModel.onEvent(com.example.rinklnote.ui.viewmodel.AuthEvent.Logout) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.accounts, key = { it.id }) { account ->
                AccountCard(account = account)
            }
        }
    }

    if (showLogin) {
        LoginScreen(viewModel = authViewModel, onDismiss = { showLogin = false })
    }
    if (showBindQQ) {
        BindQQScreen(viewModel = authViewModel, onDismiss = { showBindQQ = false })
    }
}

@Composable
private fun SyncSection(
    authState: com.example.rinklnote.ui.viewmodel.AuthState,
    syncStatus: String?,
    onLogin: () -> Unit,
    onBindQQ: () -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Text(
            text = "QQ 同步",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        when {
            !authState.isLoggedIn -> {
                Text("登录后可通过QQ机器人快捷记账", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录 / 注册") }
            }
            !authState.isQQBound -> {
                Text("已登录，请绑定QQ号", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBindQQ, modifier = Modifier.weight(1f)) { Text("绑定QQ") }
                    OutlinedButton(onClick = onLogout, modifier = Modifier.weight(1f)) { Text("退出") }
                }
            }
            else -> {
                Text("QQ已绑定，可同步账单", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("同步账单")
                }
                if (syncStatus != null) {
                    Text(syncStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
            }
        }
    }
}

@Composable
private fun AccountCard(account: Account) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(accountIconRes(account.name)),
            contentDescription = account.name,
            modifier = Modifier.size(23.dp),
            tint = Color.Unspecified
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = "***",
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
