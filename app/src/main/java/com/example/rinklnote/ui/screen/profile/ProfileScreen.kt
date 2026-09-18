package com.example.rinklnote.ui.screen.profile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.rinklnote.R
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.RetrofitClient
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.notification.DailyReportReceiver
import com.example.rinklnote.notification.PayNotifyListenerService
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.sync.SyncResult
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.achievementBadgeName
import com.example.rinklnote.ui.component.achievementBadgeRes
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthState
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import com.example.rinklnote.util.BillImageExporter
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.aggregateAnnualStats
import com.example.rinklnote.util.exportBillsToCsv
import com.example.rinklnote.util.today
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的页（Profile）—— 个人形象入口 + 数据、通知、个性化等设置。
 *
 * 2026-09-17 改版：个人卡片重做为「头像 + 昵称 + 签名 + 展示徽章行」，整卡点击进
 * `personal-profile` 资料页；QQ/飞书/企微绑定状态、修改密码、AI 开关等账号事务
 * **彻底迁出**本页（原 AccountCard 移入资料页）。本页只留：数据与同步、通知、
 * 个性化（主题/背景/显示偏好）、通用（导出/关于）。
 *
 * 风格对齐首页（Bookkeeping）：`DefaultHazeBackground` 作毛玻璃 blur 源（无自选照片时铺纯白，
 * 有照片时由 nav 层整窗铺满）；极简悬浮顶栏；卡片统一 [applyCardGlass] 透明框 + `rinkShadow`。
 *
 * 文件职责（2026-09-11 重构后）：本文件只保留「页面骨架」——
 * 收集状态 → 组装 LazyColumn → 分发弹窗，外加顶栏/头部两个私有组件。
 * - 设置卡 → `ProfileCards.kt`，编排自上而下按使用逻辑分组
 * - 留存弹窗 → `ProfileDialogs.kt`，状态由本文件的 [ProfileDialog] 单状态机统一管理
 *   （改密码 / 解绑 / AI Token 三个弹窗已随账号事务迁到个人资料页）
 * - 纯格式化函数 → `ProfileFormat.kt`
 * - CSV 导出 → `util/BillCsvExporter.kt`
 *
 * @param authViewModel 登录态 VM
 * @param settingsManager 主题/自动同步/背景/日报/头像/昵称/卡片蒙版等本地设置
 * @param tokenManager 上次同步时间
 * @param syncManager 同步入口
 * @param repository 账单仓库（导出 / 登出清数据）
 * @param onLoginClick 跳登录页
 * @param onEditProfileClick 跳「个人资料」页（personal-profile 路由）
 * @param onCustomThemeClick 跳「自定义主题」页（字体色/主题色/顶栏色/图标色/边框色）
 * @param onCropBackground 选好背景图后进入取景框裁剪
 * @param backgroundUri nav 层透传的自选背景照片 URI；`null` 时本页自铺纯白
 * @param hazeState nav 层透传的毛玻璃状态
 */
@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    settingsManager: SettingsManager,
    tokenManager: TokenManager,
    syncManager: SyncManager,
    repository: BillRepository,
    onLoginClick: () -> Unit,
    onEditProfileClick: () -> Unit,
    onCustomThemeClick: () -> Unit,
    onCropBackground: (Uri) -> Unit,
    backgroundUri: String?,
    hazeState: HazeState
) {
    // ---------- 状态 ----------
    val state by authViewModel.state.collectAsStateWithLifecycle()
    val themeMode by settingsManager.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val autoSync by settingsManager.autoSync.collectAsStateWithLifecycle(initialValue = true)
    val lastSync by tokenManager.lastSyncTime.collectAsStateWithLifecycle(initialValue = 0L)
    val dailyReportEnabled by settingsManager.dailyReportEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dailyReportHour by settingsManager.dailyReportHour.collectAsStateWithLifecycle(initialValue = 9)
    val dailyReportMinute by settingsManager.dailyReportMinute.collectAsStateWithLifecycle(initialValue = 0)
    val dailyReportQqBot by settingsManager.dailyReportQqBot.collectAsStateWithLifecycle(initialValue = false)
    // 支付通知一键记账：默认关闭；开启需再授予系统「通知使用权」（双闸门，见 PayNotifyListenerService）。
    val payNotifyEnabled by settingsManager.payNotifyEnabled.collectAsStateWithLifecycle(initialValue = false)
    var showPayNotifyGuide by remember { mutableStateOf(false) }
    // 个性化：本地头像缓存 / 昵称缓存（登录后卡片优先服务端值，离线时回落这里）/ 卡片白色蒙版。
    val avatarUri by settingsManager.avatarUri.collectAsStateWithLifecycle(initialValue = null)
    val nickname by settingsManager.nickname.collectAsStateWithLifecycle(initialValue = null)
    val cardOverlay by settingsManager.cardOverlay.collectAsStateWithLifecycle(initialValue = false)
    // 小组件预设金额快捷 chip（整数分，默认 ¥10/¥50）。
    val quickAmounts by settingsManager.quickAmounts
        .collectAsStateWithLifecycle(initialValue = SettingsManager.DEFAULT_QUICK_AMOUNTS)
    var showQuickAmountsEditor by remember { mutableStateOf(false) }
    // 年度账单分享图：年份选择 → 聚合 → Canvas 绘图 → 系统分享面板。
    var showAnnualYearPicker by remember { mutableStateOf(false) }
    // 展示徽章：服务端勾选 ∩ 实时解锁态（删账单回退后自动隐藏，零存储特性的展示端兜底）。
    val achievements by rememberAchievementStates()
    val displayBadges = displayableShowcaseBadges(state.showcaseBadges, achievements)

    // 弹窗：全页共用一个状态（同时最多一个弹窗）。
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    val dismissDialog: () -> Unit = { dialog = null }

    var syncStatus by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // ---------- 回调 ----------
    // R4：Android 13+ 通知是运行时权限，开「日报通知」时当场申请。
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            coroutineScope.launch {
                settingsManager.setDailyReportEnabled(true)
                DailyReportReceiver.schedule(context, dailyReportHour, dailyReportMinute)
            }
        } else {
            Toast.makeText(context, "通知权限被拒绝，请在系统设置中开启通知后再试", Toast.LENGTH_LONG).show()
        }
    }

    val onDailyReportEnabledChange: (Boolean) -> Unit = { turnOn ->
        if (turnOn) {
            val needPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needPermission) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                coroutineScope.launch {
                    settingsManager.setDailyReportEnabled(true)
                    DailyReportReceiver.schedule(context, dailyReportHour, dailyReportMinute)
                }
            }
        } else {
            coroutineScope.launch {
                settingsManager.setDailyReportEnabled(false)
                DailyReportReceiver.cancel(context)
            }
        }
    }

    val onDailyReportQqBotChange: (Boolean) -> Unit = { on ->
        coroutineScope.launch { settingsManager.setDailyReportQqBot(on) }
        if (state.isLoggedIn) {
            authViewModel.onEvent(AuthEvent.SetDailyReportQq(on, dailyReportHour, dailyReportMinute))
        } else {
            Toast.makeText(context, "登录后 QQ 日报推送才会生效", Toast.LENGTH_SHORT).show()
        }
    }

    // 支付通知一键记账：打开时若尚未授予「通知使用权」，弹权限说明卡引导去系统设置。
    // 开关先落 true（意图明确），服务侧仍要求使用权实际授予后才会被系统绑定生效。
    val onPayNotifyChange: (Boolean) -> Unit = { on ->
        coroutineScope.launch { settingsManager.setPayNotifyEnabled(on) }
        if (on) {
            val granted = PayNotifyListenerService.listenerGranted(context)
            if (granted) {
                Toast.makeText(context, "已开启，检测到支付通知会提醒记一笔", Toast.LENGTH_SHORT).show()
            } else {
                showPayNotifyGuide = true
            }
        }
    }

    // 图库选背景：选完先进入取景框裁剪路由，确认后才落盘写回设置。
    val pickBackgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onCropBackground(uri)
    }

    // 登出「先推后清」：尽力同步未同步项 → 成功则清本地 per-user 数据。
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
            syncManager.sync()
            val remaining = repository.countUnsynced()
            if (remaining == 0L) {
                finishLogout()
            } else {
                dialog = ProfileDialog.Unsynced(remaining.toInt())
            }
        }
    }

    val versionName = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    // 年度账单分享图导出（Task 2.7）：全量账单聚合 → Canvas 年度版式 → ACTION_SEND 分享面板。
    val exportAnnualShare: (Int) -> Unit = { year ->
        coroutineScope.launch {
            val bills = repository.observeAllBills().first()
            val stats = aggregateAnnualStats(bills, year)
            val uri = withContext(Dispatchers.IO) {
                BillImageExporter.exportAnnual(context, year, stats)
            }
            if (uri == null) {
                Toast.makeText(context, "生成年度账单失败，请重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(send, "分享年度账单"))
            } catch (_: Exception) {
                Toast.makeText(context, "未找到可分享的应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 登录态变化时刷新 profile。
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) authViewModel.onEvent(AuthEvent.FetchProfile)
    }

    // ---------- 悬浮顶栏状态 ----------
    val listState = rememberLazyListState()
    // 顶栏悬浮：列表首项垫到它下面。
    val topBarHeight = rememberRinklTopBarHeight()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    // 有背景照片或滚动后顶栏 scrim 渐显；无照片且未滚动时 scrim 透明（标题直接压在纯白背景上）。
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (backgroundUri != null || listScrolled) 1f else 0f,
        label = "profileTopBarScrim"
    )
    // ---------- 视图 ----------
    Box(modifier = Modifier.fillMaxSize()) {
        // 毛玻璃源：有自选照片时由 nav 层整窗铺满；无照片时本页铺纯白（公共 DefaultHazeBackground）。
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = hazeState)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 顶栏是浮层：列表首项垫到它下面（不留整块空白）。
            item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

            item(key = "header") {
                ProfileHeader(
                    state = state,
                    nickname = nickname,
                    avatarUri = avatarUri,
                    displayBadges = displayBadges,
                    onLogin = onLoginClick,
                    onEditProfile = onEditProfileClick
                )
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
            item(key = "dailyReport") {
                DailyReportCard(
                    enabled = dailyReportEnabled,
                    hour = dailyReportHour,
                    minute = dailyReportMinute,
                    qqBot = dailyReportQqBot,
                    payNotify = payNotifyEnabled,
                    onEnabledChange = onDailyReportEnabledChange,
                    onTimeClick = { dialog = ProfileDialog.TimePicker },
                    onQqBotChange = onDailyReportQqBotChange,
                    onPayNotifyChange = onPayNotifyChange
                )
            }
            item(key = "personalization") {
                PersonalizationCard(
                    themeMode = themeMode,
                    backgroundUri = backgroundUri,
                    cardOverlay = cardOverlay,
                    quickAmountsMinor = quickAmounts,
                    onThemeClick = { dialog = ProfileDialog.Theme },
                    onCustomThemeClick = onCustomThemeClick,
                    onBackgroundClick = {
                        pickBackgroundLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemoveBackground = { coroutineScope.launch { settingsManager.setBackgroundUri(null) } },
                    onCardOverlayChange = { on -> coroutineScope.launch { settingsManager.setCardOverlay(on) } },
                    onQuickAmountsClick = { showQuickAmountsEditor = true }
                )
            }
            item(key = "about") {
                AboutCard(
                    isLoggedIn = state.isLoggedIn,
                    versionName = versionName,
                    onExportClick = {
                        coroutineScope.launch {
                            val bills = repository.observeAllBills().first()
                            exportBillsToCsv(context, bills)
                        }
                    },
                    onAnnualReportClick = { showAnnualYearPicker = true },
                    onLogoutClick = { dialog = ProfileDialog.Logout }
                )
            }
            // 底部余量：留给安全区。
            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(48.dp)) }
        }

        // 顶栏固定在页面顶部，压在背景/列表内容之上（对齐其他页 TopBar）。
        ProfileTopBar(
            scrimAlpha = topBarScrimAlpha,
            hasBackground = backgroundUri != null,
            listScrolled = listScrolled
        )
    }

    // ---------- 弹窗分发（单一状态机） ----------
    // 修改密码 / 解绑通道 / AI Token 三类弹窗已随账号事务迁到个人资料页（PersonalProfileScreen）。
    when (val current = dialog) {
        null -> Unit

        ProfileDialog.Theme ->
            ThemeModeDialog(
                themeMode = themeMode,
                onSelect = { mode ->
                    dismissDialog()
                    coroutineScope.launch { settingsManager.setThemeMode(mode) }
                },
                onDismiss = dismissDialog
            )

        ProfileDialog.Logout ->
            LogoutDialog(
                onConfirm = {
                    dismissDialog()
                    attemptLogout()
                },
                onDismiss = dismissDialog
            )

        ProfileDialog.TimePicker ->
            TimePickerDialog(
                hour = dailyReportHour,
                minute = dailyReportMinute,
                onConfirm = { h, m ->
                    dismissDialog()
                    coroutineScope.launch {
                        settingsManager.setDailyReportTime(h, m)
                        if (dailyReportEnabled) DailyReportReceiver.schedule(context, h, m)
                    }
                },
                onDismiss = dismissDialog
            )

        is ProfileDialog.Unsynced ->
            UnsyncedDataDialog(
                count = current.count,
                onDiscard = {
                    dismissDialog()
                    finishLogout()
                },
                onRetry = {
                    dismissDialog()
                    attemptLogout()
                },
                onDismiss = dismissDialog
            )
    }

    // 支付通知监听权限说明（独立于上面的 ProfileDialog 状态机，仅此一处使用）：
    // 明示用途 + 数据边界（纯本地解析、不上传），引导去系统设置授予「通知使用权」。
    if (showPayNotifyGuide) {
        AlertDialog(
            onDismissRequest = { showPayNotifyGuide = false },
            title = { Text("开启支付通知记账") },
            text = {
                Text(
                    text = "开启后，检测到微信 / 支付宝 / 银行 App 的支付或收款通知时，" +
                        "会发一条「记一笔」提醒，点按即可带金额快速记账。\n\n" +
                        "· 需要授予本应用系统「通知使用权」，仅用于读取上述白名单应用的支付通知；\n" +
                        "· 通知内容只在本机解析，不上传、不保存；\n" +
                        "· 随时可以在这里关闭。",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPayNotifyGuide = false
                    PayNotifyListenerService.openListenerSettings(context)
                }) { Text("去开启使用权") }
            },
            dismissButton = {
                TextButton(onClick = { showPayNotifyGuide = false }) { Text("知道了") }
            }
        )
    }

    // 年度账单年份选择：当年与之前 4 年（无账单的年份导出为「空年贺词」版式，不拦截）。
    if (showAnnualYearPicker) {
        val currentYear = remember { today().year }
        AlertDialog(
            onDismissRequest = { showAnnualYearPicker = false },
            title = { Text("选择年份") },
            text = {
                Column {
                    (currentYear downTo currentYear - 4).forEach { year ->
                        TextButton(onClick = {
                            showAnnualYearPicker = false
                            exportAnnualShare(year)
                        }) { Text("${year} 年") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAnnualYearPicker = false }) { Text("取消") }
            }
        )
    }

    // 小组件快捷金额编辑：两个「元」输入框，合法才落库（整数分存储）。
    if (showQuickAmountsEditor) {
        QuickAmountsEditorDialog(
            current = quickAmounts,
            onConfirm = { minors ->
                showQuickAmountsEditor = false
                coroutineScope.launch { settingsManager.setQuickAmounts(minors) }
                Toast.makeText(context, "小组件快捷金额已更新", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showQuickAmountsEditor = false }
        )
    }
}

/**
 * 小组件快捷金额编辑弹窗：两组「元」输入（最多 2 个 chip），格式非法时提示且不关闭。
 * 金额入参「元字符串」→ [Money.parseMinor] 转整数分落库，与全 App 金额口径一致。
 */
@Composable
private fun QuickAmountsEditorDialog(
    current: List<Long>,
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val first = remember(current) {
        mutableStateOf(current.getOrNull(0)?.let { Money.toYuanInputString(it) } ?: "")
    }
    val second = remember(current) {
        mutableStateOf(current.getOrNull(1)?.let { Money.toYuanInputString(it) } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("小组件快捷金额") },
        text = {
            Column {
                Text(
                    text = "桌面小组件上会显示两个金额 chip，点按直接预填记账（最多 2 个）。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = first.value,
                    onValueChange = { first.value = it },
                    label = { Text("金额一（元）") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = second.value,
                    onValueChange = { second.value = it },
                    label = { Text("金额二（元，可留空）") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val minors = listOf(first.value, second.value)
                    .filter { it.isNotBlank() }
                    .map { Money.parseMinor(it) }
                if (minors.any { it == null }) {
                    Toast.makeText(context, "金额格式不正确，请输入如 12.50", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                onConfirm(minors.filterNotNull())
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 我的页悬浮顶栏：极简，仅居中「我的」标题 + scrim 渐隐（滚动后渐显）。 */
@Composable
private fun ProfileTopBar(scrimAlpha: Float, hasBackground: Boolean, listScrolled: Boolean) {
    // 文字色三态：有背景→白；无背景→自定义主题「顶栏标题色」（默认=字体色），滚动后略淡
    val textColor = when {
        hasBackground -> Color.White
        !listScrolled -> LocalRinklColors.current.topBarTitleColor
        else -> LocalRinklColors.current.topBarTitleColorScrolled
    }
    RinklTopBar(
        scrimAlpha = scrimAlpha,
        horizontalPadding = 8.dp
    ) {
        Text(
            text = "我的",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * 通栏宽头（2026-09-17 重做）：整卡可点进「个人资料」页。
 * 内容 = 头像 + 昵称/签名 + 展示徽章行，不再展示 QQ/微信等绑定状态（已迁入资料页）。
 *
 * 头像显示优先级：服务端头像 URL（authState.avatarUrl）> 本地缓存（DataStore `avatarUri`）>
 * 「首字符徽章」（登录态优先取自定义昵称首字，其次手机号首字，兜底「账」）。
 * 名称显示优先级：服务端昵称 > 本地缓存昵称 > 掩码手机号；未登录固定「未登录」。
 */
@Composable
private fun ProfileHeader(
    state: AuthState,
    nickname: String?,
    avatarUri: String?,
    displayBadges: List<String>,
    onLogin: () -> Unit,
    onEditProfile: () -> Unit
) {
    val effectiveNickname = state.nickname ?: nickname
    val avatarSource = resolveAvatarFullUrl(RetrofitClient.BASE_URL, state.avatarUrl) ?: avatarUri
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            .clickable(onClick = onEditProfile)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (avatarSource != null) {
                // 自定义头像：按 56dp 圆形裁切显示（Coil 加载服务端 URL 或本地缓存）。
                AsyncImage(
                    model = avatarSource,
                    contentDescription = "头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                )
            } else {
                AvatarBadge(
                    character = if (state.isLoggedIn) {
                        effectiveNickname?.trim()?.firstOrNull()?.toString()
                            ?: state.accountPhone.firstOrNull()?.toString()
                            ?: "账"
                    } else {
                        "账"
                    },
                    size = 56.dp
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        !state.isLoggedIn -> "未登录"
                        else -> effectiveNickname?.takeIf { it.isNotBlank() } ?: maskPhone(state.accountPhone)
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        !state.isLoggedIn -> "登录后即可云端同步账单"
                        else -> state.signature?.takeIf { it.isNotBlank() } ?: "点击编辑资料，写下个性签名"
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            if (!state.isLoggedIn) {
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = onLogin) { Text("登录 / 注册") }
            } else {
                // 「点击编辑资料」affordance：铅笔角标
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = "编辑资料",
                    tint = LocalRinklColors.current.iconButtonColor
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        // 展示徽章行：勾选 ∩ 实时解锁态；未勾选或未解锁时不显示。
        if (displayBadges.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                displayBadges.take(3).forEach { badgeId ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(achievementBadgeRes(badgeId)),
                            contentDescription = achievementBadgeName(badgeId),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = achievementBadgeName(badgeId),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 圆形头像徽标：底色取主题色 12% 的 primaryContainer，字色用配对的 onPrimaryContainer。 */
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
