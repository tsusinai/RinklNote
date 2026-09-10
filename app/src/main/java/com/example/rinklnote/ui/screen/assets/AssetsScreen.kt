package com.example.rinklnote.ui.screen.assets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.example.rinklnote.ui.component.rinkShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.ACCOUNT_BUCKET_NAME
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.isBucket
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.ui.viewmodel.AssetsEvent
import com.example.rinklnote.ui.viewmodel.AssetsViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

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

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AssetsScreen(
    viewModel: AssetsViewModel,
    backgroundUri: String?,
    hazeState: HazeState
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balanceHidden by BalancePrivacy.hidden.collectAsStateWithLifecycle()

    var editingAccount by remember { mutableStateOf<Account?>(null) }
    var addingAccount by remember { mutableStateOf(false) }
    var renamingAccount by remember { mutableStateOf<Account?>(null) }
    var deletingAccount by remember { mutableStateOf<Account?>(null) }
    var reconcilingAccount by remember { mutableStateOf<Account?>(null) }
    var reconcilingAll by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 顶栏悬浮：列表首项垫到它下面（不留整块空白）。高度 = 状态栏避让 + 图标行（30dp + 上下各 8dp）。
    val topBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + 46.dp
    // 列表滚动后内容滑到顶栏下方，白色文字需要渐隐暗底兜住可读性。
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    // 配图背景或滚动后顶栏 scrim 渐显；无照片且未滚动时 scrim 透明（标题直接压在渐变背景上）。
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (backgroundUri != null || listScrolled) 1f else 0f,
        label = "assetsTopBarScrim"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 毛玻璃源：有自选照片时由 nav 层整窗铺满；无照片时本页铺主题渐变供各卡片 hazeEffect 采样。
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = hazeState)
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            // 顶栏是浮层：配图背景时把列表首项垫到它下面（不留整块空白）。
            item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }
            item(key = "spacer-0") { Spacer(modifier = Modifier.height(10.dp)) }

            item(key = "total") {
                TotalAssetsCard(accounts = state.accounts, hidden = balanceHidden, hazeState = hazeState)
            }
            items(state.accounts, key = { it.id }) { account ->
                AccountCard(
                    account = account,
                    hidden = balanceHidden,
                    hazeState = hazeState,
                    onClick = { editingAccount = account },
                    onRename = { renamingAccount = account },
                    onDelete = { deletingAccount = account },
                    onReconcile = { reconcilingAccount = account }
                )
            }
            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(120.dp)) }
        }

        // 顶栏固定在页面顶部，压在背景/列表内容之上（对齐首页 TopBar）。
        AssetsTopBar(
            scrimAlpha = topBarScrimAlpha,
            canReconcileAll = state.accounts.isNotEmpty(),
            onReconcileAll = { reconcilingAll = true },
            onAddAccount = { addingAccount = true }
        )

        // FAB：新建账户（与首页加账单 FAB 同款：rinkShadow + 毛玻璃 + 51dp）。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .rinkShadow(CircleShape)
                .size(51.dp)
                .clip(CircleShape)
                .hazeEffect(hazeState, HazeMaterials.thin())
                .clickable { addingAccount = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add_bill),
                contentDescription = "新建账户",
                tint = Color.Unspecified
            )
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

        reconcilingAccount?.let { account ->
            ReconcileDialog(
                account = account,
                onConfirm = { offset ->
                    reconcilingAccount = null
                    viewModel.onEvent(AssetsEvent.ReconcileAccount(account, offset))
                },
                onDismiss = { reconcilingAccount = null },
                loadNet = viewModel::getAccountNet
            )
        }

        if (reconcilingAll) {
            ReconcileAllDialog(
                accounts = state.accounts,
                onConfirm = {
                    reconcilingAll = false
                    viewModel.onEvent(AssetsEvent.ReconcileAll)
                },
                onDismiss = { reconcilingAll = false },
                loadNet = viewModel::getAccountNet
            )
        }
    }
}

/** 资产页悬浮顶栏：左侧「对账」（全部对账）+ 居中「资产管理」标题 + 右侧「新建」入口。 */
@Composable
private fun AssetsTopBar(
    scrimAlpha: Float,
    canReconcileAll: Boolean,
    onReconcileAll: () -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        // 顶部渐隐遮罩：白色标题下的内容（照片/滚动上来的账户卡）被它压暗，保证可读性。
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.30f * scrimAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        // 内容层：状态栏避让 + 内边距，悬浮于背景/列表之上（与首页 TopBar 同构）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 左侧：全部对账（账户为空时不可点）。
            Text(
                text = "对账",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = if (canReconcileAll) 1f else 0.4f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clickable(enabled = canReconcileAll) { onReconcileAll() }
            )
            // 居中：标题。
            Text(
                text = "资产管理",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            // 右侧：新建账户入口。
            Icon(
                painter = painterResource(R.drawable.ic_add_bill),
                contentDescription = "新建账户",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(30.dp)
                    .clickable { onAddAccount() },
                tint = Color.White
            )
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun TotalAssetsCard(accounts: List<Account>, hidden: Boolean, hazeState: HazeState) {
    val total = remember(accounts) { accounts.sumOf { it.balance } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .hazeEffect(hazeState, HazeMaterials.thin())
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

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun AccountCard(
    account: Account,
    hidden: Boolean,
    hazeState: HazeState,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onReconcile: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // 保持最新回调引用：父级重组时避免 stale lambda 或整卡不必要的重组。
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnRename by rememberUpdatedState(onRename)
    val currentOnDelete by rememberUpdatedState(onDelete)
    val currentOnReconcile by rememberUpdatedState(onReconcile)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .rinkShadow(RoundedCornerShape(15.dp))
                .clip(RoundedCornerShape(15.dp))
                .hazeEffect(hazeState, HazeMaterials.thin())
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
            // 「无账户」桶为保留账户：不可重命名/删除，仅可改余额；真实钱包 ⋮ 菜单额外给重命名/删除。
            // 所有账户都提供「对账」（把余额重算为其账单收支合计）。
            IconButton(onClick = { menuExpanded = true }) {
                Text("⋮", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("对账") },
                    onClick = { menuExpanded = false; currentOnReconcile() }
                )
                if (!account.isBucket()) {
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
                // 「无账户」为保留名，禁止用户再建一个。
                enabled = name.trim().isNotBlank() && name.trim() != ACCOUNT_BUCKET_NAME
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
                // 禁止重命名成「无账户」（保留名）。
                enabled = name.trim().isNotBlank() && name.trim() != ACCOUNT_BUCKET_NAME
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 单账户对账：显示 当前余额 / 账单收支合计 / 期初偏移(可填) / 重算后结果，确认后覆盖。 */
@Composable
private fun ReconcileDialog(
    account: Account,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
    loadNet: suspend (Long) -> Double
) {
    val net by produceState<Double?>(initialValue = null, account.id) {
        value = loadNet(account.id)
    }
    var offset by remember(account.id) { mutableStateOf("") }

    val netVal = net ?: 0.0
    val offsetVal = offset.toDoubleOrNull() ?: 0.0
    val result = offsetVal + netVal

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("对账 · ${account.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PreviewRow("当前余额", String.format("%.2f", account.balance))
                PreviewRow("账单收支合计", String.format("%.2f", netVal))
                OutlinedTextField(
                    value = offset,
                    onValueChange = { offset = it },
                    label = { Text("期初偏移（可选，现实里有、账里没的资金）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                PreviewRow("重算后余额", String.format("%.2f", result))
                Text(
                    text = "确认后将以「重算后余额」覆盖当前余额，并同步到云端。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(offsetVal) }) { Text("覆盖") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 全部对账：列出每个账户的 当前余额 → 账单合计，确认后统一重算（期初偏移 0）。 */
@Composable
private fun ReconcileAllDialog(
    accounts: List<Account>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    loadNet: suspend (Long) -> Double
) {
    val nets by produceState<Map<Long, Double>>(initialValue = emptyMap(), accounts) {
        value = accounts.associate { it.id to loadNet(it.id) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("全部对账") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "将每个账户余额重算为各自账单收支合计（期初偏移 0）。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                accounts.forEach { account ->
                    val net = nets[account.id] ?: 0.0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(account.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = "${String.format("%.2f", account.balance)} → ${String.format("%.2f", net)}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("全部覆盖") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
