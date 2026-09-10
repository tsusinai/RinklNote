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

/** 账户名 → 图标 drawable 资源映射（微信/支付宝有专属图标，其余用默认图标）。 */
private fun accountIconRes(name: String): Int = when (name) {
    "微信" -> R.drawable.ic_wechat
    "支付宝" -> R.drawable.ic_alipay
    else -> R.drawable.ic_default_account
}

/** 新建账户时可选的预设色板（hex），含微信绿/支付宝蓝/橙/紫/红/灰 6 色。 */
private val ACCOUNT_COLORS = listOf("#28C145", "#06B4FD", "#F97D1D", "#8B5CF6", "#EF4444", "#64748B")

/** 将 hex 字符串（如 `"#28C145"`）解析为 [Color]；解析失败回退浅灰底色 `0xFFE5E7EB`。 */
private fun hexColor(hex: String): Color {
    val value = hex.removePrefix("#").toLongOrNull(16) ?: return Color(0xFFE5E7EB)
    return Color(0xFF000000L or value)
}

/**
 * 资产页（Assets）—— 账户与总资产管理。
 *
 * 风格深度对齐首页（Bookkeeping）：
 * - `Box` 根 + 渐变背景作毛玻璃（haze）blur 源；自选照片时由 nav 层整窗铺满。
 * - 悬浮顶栏（floating top bar）：左「对账」+ 居中「资产管理」+ 右「新建」入口；配图背景或列表滚动后 scrim 渐显压暗标题下层，保证白色文字可读性。
 * - 卡片 `rinkShadow` + `hazeEffect(HazeMaterials.thin())` 毛玻璃 + 圆角。
 * - FAB（floating action button）替代「+ 新建账户」卡：与首页加账单 FAB 同构（`rinkShadow` + 毛玻璃 + 51dp）。
 * - 余额编辑用上滑 `Motion.SheetEnter/Exit`（与快加键盘一致）；其余弹窗用 `AlertDialog`。
 *
 * 业务不变量：ViewModel 的 `State`/`Event` 不动；余额隐藏由 [BalancePrivacy] 全局态管理，本页只读取 + 切换，不持久化。
 *
 * @param viewModel 资产页 ViewModel
 * @param backgroundUri nav 层透传的自选背景照片 URI；`null` 时本页自铺渐变
 * @param hazeState nav 层透传的毛玻璃状态，背景与卡片共用同一 blur 源
 */
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

/**
 * 总资产卡（Total Assets）：主色背景 + 毛玻璃，左「总资产/金额」右「显隐切换」图标。
 *
 * - 金额由 [BalancePrivacy.hidden] 控制显隐：隐藏时显示 `****`，图标为「显示」态。
 * - 点击图标切换全局显隐态（不持久化，仅会话内）。
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun TotalAssetsCard(accounts: List<Account>, hidden: Boolean, hazeState: HazeState) {
    val total = remember(accounts) { accounts.sumOf { it.balance } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .hazeEffect(hazeState, HazeMaterials.regular())
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "总资产",
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (hidden) "****" else String.format("%.2f", total),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Icon(
            painter = painterResource(if (hidden) R.drawable.ic_eye_show else R.drawable.ic_eye_hide),
                contentDescription = if (hidden) "显示余额" else "隐藏余额",
                modifier = Modifier
                    .size(22.dp)
                    .clickable { BalancePrivacy.toggle() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 单账户卡（Account Card）：白底 + 毛玻璃，左图标 + 账户名 + 右金额 + 「⋮」菜单。
 *
 * - 菜单项：对账（所有账户）、重命名 / 删除（仅真实钱包；「无账户」桶 [Account.isBucket] 不可重命名/删除）。
 * - 用 [rememberUpdatedState] 持最新回调引用，避免父级重组时产生 stale lambda 或整卡不必要重组。
 *
 * @param onClick 点卡片 → 编辑余额
 * @param onRename 重命名
 * @param onDelete 删除
 * @param onReconcile 对账（余额重算为账单收支合计）
 */
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
            .hazeEffect(hazeState, HazeMaterials.regular())
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

/**
 * 新建账户弹窗（Add Account Dialog）：账户名 + 余额 + 预设色板 6 选 1。
 *
 * 约束：「无账户」([ACCOUNT_BUCKET_NAME]) 为保留名，禁止用户创建同名账户（确定键禁用）。
 *
 * @param onConfirm `(name, colorHex, balance)` 三元回调
 * @param onDismiss 取消
 */
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

/**
 * 重命名账户弹窗（Rename Account Dialog）：单字段账户名。
 *
 * 约束：禁止重命名为保留名「无账户」([ACCOUNT_BUCKET_NAME])；空名禁用。
 */
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

/**
 * 单账户对账弹窗（Reconcile Dialog）：显示 当前余额 / 账单收支合计 / 期初偏移(可填) / 重算后结果，确认后覆盖。
 *
 * - 账单收支合计（net）由 [loadNet] 挂起查询，`produceState` 异步加载。
 * - 重算后余额 = 期初偏移 + 账单合计；确认后覆盖当前余额并同步云端。
 *
 * @param account 待对账账户
 * @param onConfirm `(offset)` 回调，传入用户填的期初偏移
 * @param loadNet 挂起函数，按账户 id 返回账单收支合计
 */
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

/** 对账弹窗内的标签-值预览行（label-value preview row），左右两栏对齐展示。 */
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

/**
 * 全部对账弹窗（Reconcile All）：列出每个账户的 当前余额 → 账单合计，确认后统一重算（期初偏移 0）。
 *
 * - 每账户的 net 由 [loadNet] 异步查询（`produceState` 批量关联成 Map）。
 * - 与单账户对账不同：不支持单独填期初偏移，统一按「账单合计」覆盖。
 */
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
