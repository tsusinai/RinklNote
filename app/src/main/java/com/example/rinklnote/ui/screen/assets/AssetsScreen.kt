package com.example.rinklnote.ui.screen.assets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.isBucket
import com.example.rinklnote.ui.component.AccountIcon
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.ui.viewmodel.AssetsEvent
import com.example.rinklnote.ui.viewmodel.AssetsViewModel
import com.example.rinklnote.util.Money
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch

/**
 * 资产页（Assets）—— 账户与总资产管理。
 *
 * 风格对齐首页（Bookkeeping）毛玻璃气质，按 UI 设计规范执行：
 * - `Box` 根 + 渐变背景 + 装饰光斑（让毛玻璃有"模糊内容"，无照片也好用）；自选照片时由 nav 层整窗铺满。
 * - 悬浮顶栏（floating top bar）：左「对账」+ 居中「资产管理」+ 右「新建」；触摸目标 ≥ 44dp（IconButton）。
 * - 卡片 `rinkShadow` + `hazeEffect(CardFrostedStyle)` 毛玻璃（半透明 surface tint）+ 圆角（12/16dp 梯度）。
 * - ⋮ 菜单用 `ModalBottomSheet`（明确分层浮层，不挡任何下方卡）。
 * - FAB 替代「+ 新建账户」卡：与首页加账单 FAB 同构（`rinkShadow` + `thin()` 毛玻璃 + 56dp 居中）。
 * - 余额编辑用上滑 `Motion.SheetEnter/Exit`（与快加键盘一致）；其余弹窗用 `AlertDialog`。
 * - 间距全部落 4px 梯度（4/8/12/16/24/32dp），字号落梯度（12/14/16/20/30sp），圆角落梯度（8/12/16dp）。
 *
 * 业务不变量：ViewModel 的 `State`/`Event` 不动；余额隐藏由 [BalancePrivacy] 全局态管理，本页只读取 + 切换。
 *
 * @param viewModel 资产页 ViewModel
 * @param backgroundUri nav 层透传的自选背景照片 URI；`null` 时本页自铺渐变
 * @param hazeState nav 层透传的毛玻璃状态，背景与卡片共用同一 blur 源
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    viewModel: AssetsViewModel,
    backgroundUri: String?,
    hazeState: HazeState,
    onAddAccount: () -> Unit,
    onEditBalance: (Long) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balanceHidden by BalancePrivacy.hidden.collectAsStateWithLifecycle()

    var renamingAccount by remember { mutableStateOf<Account?>(null) }
    var deletingAccount by remember { mutableStateOf<Account?>(null) }
    var reconcilingAccount by remember { mutableStateOf<Account?>(null) }
    var reconcilingAll by remember { mutableStateOf(false) }
    var menuForAccount by remember { mutableStateOf<Account?>(null) }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 顶栏悬浮：列表首项垫到它下面。高度 = 状态栏避让 + 标题行（20sp + 上下各 8dp = 36dp + 余量）。
    val topBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + 48.dp
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
        // 毛玻璃源：有自选照片时由 nav 层整窗铺满；无照片时本页铺渐变+光斑（公共 [DefaultHazeBackground]）。
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = hazeState)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶栏是浮层：列表首项垫到它下面（不留整块空白）。
            item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

            item(key = "total") {
                TotalAssetsCard(accounts = state.accounts, hidden = balanceHidden)
            }
            items(state.accounts, key = { it.id }) { account ->
                AccountCard(
                    account = account,
                    hidden = balanceHidden,
                    onClick = { onEditBalance(account.id) },
                    onMenu = { menuForAccount = account }
                )
            }
            // 底部余量：留给 FAB + 安全区。
            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(120.dp)) }
        }

        // 顶栏固定在页面顶部，压在背景/列表内容之上（对齐首页 TopBar）。
        AssetsTopBar(
            scrimAlpha = topBarScrimAlpha,
            hasBackground = backgroundUri != null,
            listScrolled = listScrolled,
            canReconcileAll = state.accounts.isNotEmpty(),
            onReconcileAll = { reconcilingAll = true },
            onAddAccount = onAddAccount
        )

        // FAB：新建账户（与首页加账单 FAB 同款：rinkShadow + 毛玻璃 thin() + 56dp 触摸目标）。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .rinkShadow(CircleShape)
                .size(56.dp)
                .clip(CircleShape)
                .hazeEffect(hazeState, HazeMaterials.thin())
                .clickable(onClick = onAddAccount),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add_bill),
                contentDescription = "新建账户",
                tint = Color.Unspecified
            )
        }

        // ⋮ 菜单用 ModalBottomSheet：明确浮层分层（scrim 渐显 + 底部 sheet 上滑），不挡任何卡。
        menuForAccount?.let { account ->
            AccountActionsSheet(
                account = account,
                onDismiss = { menuForAccount = null },
                onReconcile = {
                    menuForAccount = null
                    reconcilingAccount = account
                },
                onRename = {
                    menuForAccount = null
                    renamingAccount = account
                },
                onDelete = {
                    menuForAccount = null
                    deletingAccount = account
                }
            )
        }

        renamingAccount?.let { account ->
            RenameAccountDialog(
                account = account,
                accounts = state.accounts,
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

/** 资产页悬浮顶栏：左「对账」（全部对账）+ 居中「资产管理」标题 + 右「新建」入口。触摸目标均 ≥ 44dp。 */
@Composable
private fun AssetsTopBar(
    scrimAlpha: Float,
    hasBackground: Boolean,
    listScrolled: Boolean,
    canReconcileAll: Boolean,
    onReconcileAll: () -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 文字色三态：有背景→白；无背景→自定义主题「顶栏标题色」（默认=字体色），滚动后略淡
    val textColor = when {
        hasBackground -> Color.White
        !listScrolled -> LocalRinklColors.current.topBarTitleColor
        else -> LocalRinklColors.current.topBarTitleColorScrolled
    }
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
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            // 左侧：全部对账（账户为空时禁用：半透明 + 不可点 + contentDescription 说明）。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "对账",
                    fontSize = 16.sp,
                    color = textColor.copy(alpha = if (canReconcileAll) 1f else 0.4f),
                    modifier = Modifier.clickable(enabled = canReconcileAll) { onReconcileAll() }
                )
            }
            // 居中：标题。
            Text(
                text = "资产管理",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                modifier = Modifier.align(Alignment.Center)
            )
            // 右侧：新建账户入口（IconButton 默认 48dp 触摸区）。
            IconButton(
                onClick = { onAddAccount() },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_bill),
                    contentDescription = "新建账户",
                    tint = textColor
                )
            }
        }
    }
}

/**
 * 总资产卡（Total Assets）：毛玻璃主卡，左「总资产/金额」右「显隐切换」图标。
 *
 * - 金额 30sp Bold（字号梯度最大档），标签 14sp，层次分明。
 * - 金额由 [BalancePrivacy.hidden] 控制显隐：隐藏时显示 `****`，图标为「显示」态。
 * - 点击图标切换全局显隐态（不持久化，仅会话内）；IconButton 触摸目标 48dp。
 */
@Composable
private fun TotalAssetsCard(
    accounts: List<Account>,
    hidden: Boolean
) {
    val total = remember(accounts) { accounts.sumOf { it.balanceMinor } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(applyCardGlass(RoundedCornerShape(16.dp)))
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "总资产",
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (hidden) "****" else Money.formatPlain(total),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(onClick = { BalancePrivacy.toggle() }) {
            Icon(
                painter = painterResource(if (hidden) R.drawable.ic_eye_show else R.drawable.ic_eye_hide),
                contentDescription = if (hidden) "显示余额" else "隐藏余额",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 单账户卡（Account Card）：毛玻璃卡，左图标 + 账户名 + 右金额 + 「⋮」菜单触发器。
 *
 * - 图标 24dp 置于透明 Box（无额外底色，靠毛玻璃采样）。
 * - 「⋮」用 IconButton（48dp 触摸目标）；点击 ⋮ 触发 [onMenu]，菜单改用 [AccountActionsSheet]（ModalBottomSheet）显示，避免 DropdownMenu 遮盖下方卡。
 * - 卡片本身 clickable 进编辑余额。
 * - 用 [rememberUpdatedState] 持最新回调引用，避免父级重组时产生 stale lambda 或整卡不必要重组。
 *
 * @param onClick 点卡片 → 编辑余额
 * @param onMenu 点 ⋮ → 弹出账户操作 sheet（对账/重命名/删除）
 */
@Composable
private fun AccountCard(
    account: Account,
    hidden: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnMenu by rememberUpdatedState(onMenu)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .then(applyCardGlass(RoundedCornerShape(12.dp)))
            .clickable { currentOnClick() }
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccountIcon(
            iconKey = account.iconKey,
            colorHex = account.iconColor,
            size = 32.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = account.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (hidden) "***" else Money.formatPlain(account.balanceMinor),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        IconButton(onClick = { currentOnMenu() }) {
            Text("⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 账户操作底部 sheet（Account Actions Sheet）：替代 DropdownMenu 避免遮挡下方卡。
 *
 * - 用 `ModalBottomSheet`：底部上滑进入（带 scrim 渐显），明确浮层语义，不挡任何卡。
 * - 「无账户」([Account.isBucket]) 桶为保留账户：不显示重命名/删除项（仅显示对账 + 改余额说明）。
 * - 触摸目标 56dp 列表项（Material 3 规范）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountActionsSheet(
    account: Account,
    onDismiss: () -> Unit,
    onReconcile: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            // 头部：账户名 + 余额（仅展示，不操作）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccountIcon(
                    iconKey = account.iconKey,
                    colorHex = account.iconColor,
                    size = 40.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = Money.formatPlain(account.balanceMinor),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 操作项
            ActionSheetItem(
                label = "对账",
                description = "按账单收支合计重算余额",
                onClick = { onReconcile(); dismiss() }
            )
            if (!account.isBucket()) {
                ActionSheetItem(
                    label = "重命名",
                    description = "修改账户名（保留名「无账户」不可用）",
                    onClick = { onRename(); dismiss() }
                )
                ActionSheetItem(
                    label = "删除账户",
                    description = "历史账单保留，新建时不再可选",
                    onClick = { onDelete(); dismiss() },
                    isDestructive = true
                )
            } else {
                Text(
                    text = "「无账户」为保留桶账户：不可重命名/删除。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/** 底部 sheet 的统一列表项（触摸目标 56dp，破坏性操作色用 error）。 */
@Composable
private fun ActionSheetItem(
    label: String,
    description: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    val labelColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = labelColor
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = description,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 重命名账户弹窗（Rename Account Dialog）：单字段账户名。
 *
 * 约束：禁止重命名为保留名「无账户」([ACCOUNT_BUCKET_NAME])；空名禁用。
 */
@Composable
private fun RenameAccountDialog(
    account: Account,
    accounts: List<Account>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(account.id) { mutableStateOf(account.name) }
    val error = validateAccountName(name, accounts, editingAccountId = account.id)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名账户") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("账户名") },
                singleLine = true,
                isError = error != null,
                supportingText = {
                    if (error != null) Text(error)
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val n = name.trim()
                    if (n.isBlank()) return@TextButton
                    onConfirm(n)
                },
                enabled = error == null
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
 * - 账单收支合计（net）由 [loadNet] 挂起查询，`produceState` 异步加载；**加载中**显示「加载中…」（非 0.00）。
 * - 重算后余额 = 期初偏移 + 账单合计；确认后覆盖当前余额并同步云端。
 *
 * @param account 待对账账户
 * @param onConfirm `(offset)` 回调，传入用户填的期初偏移
 * @param loadNet 挂起函数，按账户 id 返回账单收支合计
 */
@Composable
private fun ReconcileDialog(
    account: Account,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    loadNet: suspend (Long) -> Long
) {
    val net by produceState<Long?>(initialValue = null, account.id) {
        value = loadNet(account.id)
    }
    var offset by remember(account.id) { mutableStateOf("") }

    val netVal = net ?: 0L
    val offsetMinor = Money.parseMinor(offset) ?: 0L
    val result = offsetMinor + netVal

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("对账 · ${account.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewRow("当前余额", Money.formatPlain(account.balanceMinor))
                // 加载态：net 为 null 时显示「加载中…」而非误导性的 0.00。
                PreviewRow("账单收支合计", if (net == null) "加载中…" else Money.formatPlain(netVal))
                OutlinedTextField(
                    value = offset,
                    onValueChange = { offset = it },
                    label = { Text("期初偏移（可选，现实里有、账里没的资金）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                PreviewRow("重算后余额", if (net == null) "—" else Money.formatPlain(result))
                Text(
                    text = "确认后将以「重算后余额」覆盖当前余额，并同步到云端。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(offsetMinor) }) { Text("覆盖") }
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
 * - 加载中（nets 未到位）显示「加载中…」占位。
 * - 与单账户对账不同：不支持单独填期初偏移，统一按「账单合计」覆盖。
 */
@Composable
private fun ReconcileAllDialog(
    accounts: List<Account>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    loadNet: suspend (Long) -> Long
) {
    val nets by produceState<Map<Long, Long>?>(initialValue = null, accounts) {
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
                Spacer(modifier = Modifier.height(12.dp))
                if (nets == null) {
                    Text(
                        text = "加载中…",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    accounts.forEach { account ->
                        val net = nets!![account.id] ?: 0L
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(account.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                text = "${Money.formatPlain(account.balanceMinor)} → ${Money.formatPlain(net)}",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
