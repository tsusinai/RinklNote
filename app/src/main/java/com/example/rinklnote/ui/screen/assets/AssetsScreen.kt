package com.example.rinklnote.ui.screen.assets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.ui.viewmodel.AssetsEvent
import com.example.rinklnote.ui.viewmodel.AssetsViewModel

private fun accountIconRes(name: String): Int = when (name) {
    "微信" -> R.drawable.ic_wechat
    "支付宝" -> R.drawable.ic_alipay
    else -> R.drawable.ic_default_account
}

private val ACCOUNT_COLORS = listOf("#28C145", "#06B4FD", "#F97D1D", "#8B5CF6", "#EF4444", "#64748B")

private fun hexColor(hex: String): Color {
    val value = hex.removePrefix("#").toLongOrNull(16) ?: return Color(0xFFE5E7EB)
    return Color(0xFF000000L or value)
}

@Composable
fun AssetsScreen(viewModel: AssetsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balanceHidden by BalancePrivacy.hidden.collectAsStateWithLifecycle()

    var editingAccount by remember { mutableStateOf<Account?>(null) }
    var addingAccount by remember { mutableStateOf(false) }
    var renamingAccount by remember { mutableStateOf<Account?>(null) }
    var deletingAccount by remember { mutableStateOf<Account?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text(
            text = "资产管理",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "total") {
                TotalAssetsCard(accounts = state.accounts, hidden = balanceHidden)
            }
            items(state.accounts, key = { it.id }) { account ->
                AccountCard(
                    account = account,
                    hidden = balanceHidden,
                    onClick = { editingAccount = account },
                    onRename = { renamingAccount = account },
                    onDelete = { deletingAccount = account }
                )
            }
            item(key = "add") {
                AddAccountCard(onClick = { addingAccount = true })
            }
        }
    }

    // 全屏编辑余额用上滑进入（与快加键盘一致的 SheetEnter/Exit）
    AnimatedVisibility(
        visible = editingAccount != null,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.SheetEnter),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.SheetExit)
    ) {
        editingAccount?.let { account ->
            BalanceEditDialog(
                account = account,
                onConfirm = { updated ->
                    editingAccount = null
                    viewModel.onEvent(AssetsEvent.ChangeBalance(updated, updated.balance))
                },
                onDismiss = { editingAccount = null }
            )
        }
    }

    if (addingAccount) {
        AddAccountDialog(
            onConfirm = { name, color, balance ->
                addingAccount = false
                viewModel.onEvent(AssetsEvent.AddAccount(name, color, balance))
            },
            onDismiss = { addingAccount = false }
        )
    }

    renamingAccount?.let { account ->
        RenameAccountDialog(
            account = account,
            onConfirm = { name ->
                renamingAccount = null
                viewModel.onEvent(AssetsEvent.RenameAccount(account, name))
            },
            onDismiss = { renamingAccount = null }
        )
    }

    deletingAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { deletingAccount = null },
            title = { Text("删除账户") },
            text = { Text("确定删除「${account.name}」？该账户的历史账单记录会保留，仅用于新记账时不再选择该账户。") },
            confirmButton = {
                TextButton(onClick = {
                    deletingAccount = null
                    viewModel.onEvent(AssetsEvent.DeleteAccount(account))
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingAccount = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TotalAssetsCard(accounts: List<Account>, hidden: Boolean) {
    val total = remember(accounts) { accounts.sumOf { it.balance } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "总资产",
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (hidden) "****" else String.format("%.2f", total),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Icon(
            painter = painterResource(if (hidden) R.drawable.ic_eye_show else R.drawable.ic_eye_hide),
            contentDescription = if (hidden) "显示余额" else "隐藏余额",
            modifier = Modifier
                .size(22.dp)
                .clickable { BalancePrivacy.toggle() },
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun AccountCard(
    account: Account,
    hidden: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // 保持最新回调引用：父级重组时避免 stale lambda 或整卡不必要的重组
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnRename by rememberUpdatedState(onRename)
    val currentOnDelete by rememberUpdatedState(onDelete)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(15.dp))
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { currentOnClick() }
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
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
                text = if (hidden) "***" else String.format("%.2f", account.balance),
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = { menuExpanded = true }) {
                Text("⋮", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("重命名") },
                onClick = { menuExpanded = false; currentOnRename() }
            )
            DropdownMenuItem(
                text = { Text("删除账户") },
                onClick = { menuExpanded = false; currentOnDelete() }
            )
        }
    }
}

@Composable
private fun AddAccountCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "+ 新建账户",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AddAccountDialog(
    onConfirm: (String, String, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(ACCOUNT_COLORS.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    label = { Text("余额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ACCOUNT_COLORS.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(hexColor(c))
                                .border(
                                    width = if (c == color) 2.dp else 0.dp,
                                    color = if (c == color) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { color = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val n = name.trim()
                    if (n.isBlank()) return@TextButton
                    onConfirm(n, color, balance.toDoubleOrNull() ?: 0.0)
                },
                enabled = name.trim().isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RenameAccountDialog(
    account: Account,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(account.id) { mutableStateOf(account.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名账户") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("账户名") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val n = name.trim()
                    if (n.isBlank()) return@TextButton
                    onConfirm(n)
                },
                enabled = name.trim().isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
