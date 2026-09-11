package com.example.rinklnote.ui.screen.profile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.notification.DailyReportReceiver
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.sync.SyncResult
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.viewmodel.AiTokenViewModel
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthState
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import com.example.rinklnote.util.exportBillsToCsv
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 我的页（Profile）—— 账户、同步、日报、AI 等设置入口。
 *
 * 风格对齐首页（Bookkeeping）：`DefaultHazeBackground` 作毛玻璃 blur 源（无自选照片时铺纯白，
 * 有照片时由 nav 层整窗铺满）；极简悬浮顶栏；卡片统一 [applyCardGlass] 透明框 + `rinkShadow`。
 *
 * **文件职责（2026-09-11 重构后）**：本文件只保留「页面骨架」——
 * 收集状态 → 组装 LazyColumn → 分发弹窗，外加顶栏/头部两个私有组件。
 * - 四张设置卡 → `ProfileCards.kt`（SyncCard / DailyReportCard / AccountCard / AboutCard）
 * - 七个弹窗 → `ProfileDialogs.kt`，状态由本文件的 [ProfileDialog] 单状态机统一管理
 * - 纯格式化函数 → `ProfileFormat.kt`
 * - CSV 导出 → `util/BillCsvExporter.kt`
 *
 * 业务不变量：所有事件回调、AuthState/SettingsManager/TokenManager 读写保持原样；
 * 本页签名只 +`backgroundUri` +`hazeState` 两个透传参数（nav 层提供）。
 *
 * @param authViewModel 登录态 VM
 * @param settingsManager 主题/自动同步/背景/日报本地设置
 * @param tokenManager 上次同步时间
 * @param syncManager 同步入口
 * @param repository 账单仓库（导出 / 登出清数据）
 * @param aiTokenViewModel AI Token VM
 * @param onLoginClick 跳登录页
 * @param onBindQQClick 跳绑定 QQ 页
 * @param onQqBotGuideClick 跳机器人引导
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
    aiTokenViewModel: AiTokenViewModel,
    onLoginClick: () -> Unit,
    onBindQQClick: () -> Unit,
    onQqBotGuideClick: () -> Unit,
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

    // 登录态变化时刷新 profile。
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) authViewModel.onEvent(AuthEvent.FetchProfile)
    }

    // ---------- 悬浮顶栏状态 ----------
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 顶栏悬浮：列表首项垫到它下面。高度 = 状态栏避让 + 标题行（20sp + 上下各 8dp = 36dp + 余量）。
    val topBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + 48.dp
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
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶栏是浮层：列表首项垫到它下面（不留整块空白）。
            item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

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
            item(key = "dailyReport") {
                DailyReportCard(
                    enabled = dailyReportEnabled,
                    hour = dailyReportHour,
                    minute = dailyReportMinute,
                    qqBot = dailyReportQqBot,
                    onEnabledChange = onDailyReportEnabledChange,
                    onTimeClick = { dialog = ProfileDialog.TimePicker },
                    onQqBotChange = onDailyReportQqBotChange
                )
            }
            item(key = "account") {
                AccountCard(
                    state = state,
                    themeMode = themeMode,
                    backgroundUri = backgroundUri,
                    onThemeClick = { dialog = ProfileDialog.Theme },
                    onCustomThemeClick = onCustomThemeClick,
                    onBackgroundClick = {
                        pickBackgroundLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemoveBackground = { coroutineScope.launch { settingsManager.setBackgroundUri(null) } },
                    onPasswordClick = { dialog = ProfileDialog.Password },
                    onBindQQClick = onBindQQClick,
                    onUnbindQQ = { dialog = ProfileDialog.UnbindQQ },
                    onQqBotGuideClick = onQqBotGuideClick,
                    onSetAiDisabled = { authViewModel.onEvent(AuthEvent.SetAiDisabled(it)) },
                    onAiTokenClick = { dialog = ProfileDialog.AiToken }
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
    when (val current = dialog) {
        null -> Unit

        ProfileDialog.Password ->
            PasswordDialog(viewModel = authViewModel, onDismiss = dismissDialog)

        ProfileDialog.UnbindQQ -> if (state.isQQBound) {
            UnbindQQDialog(
                onConfirm = {
                    dismissDialog()
                    authViewModel.onEvent(AuthEvent.UnbindQQ)
                },
                onDismiss = dismissDialog
            )
        }

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

        ProfileDialog.AiToken ->
            AiTokenDialog(viewModel = aiTokenViewModel, onDismiss = dismissDialog)

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
    Box(modifier = Modifier.fillMaxWidth()) {
        // 顶部渐隐遮罩：白色标题下的内容被它压暗，保证可读性。
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
        // 内容层：状态栏避让 + 内边距，悬浮于背景/列表之上。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
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
}

/** 通栏宽头：左大号头像 + 右昵称/副行；未登录给「登录/注册」胶囊按钮。 */
@Composable
private fun ProfileHeader(state: AuthState, onLogin: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(applyCardGlass(RoundedCornerShape(16.dp)))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarBadge(
            character = if (state.isLoggedIn) {
                state.accountPhone.firstOrNull()?.toString() ?: "账"
            } else {
                "账"
            },
            size = 56.dp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (state.isLoggedIn) maskPhone(state.accountPhone) else "未登录",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (state.isLoggedIn) {
                    "QQ ${if (state.isQQBound) state.qqNumber else "未绑定"} · " +
                        "机器人 ${if (state.botBound) "已绑定" else "未绑定"}"
                } else {
                    "登录后即可云端同步账单"
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 注册时间只有登录态才显示。
            if (state.isLoggedIn && state.createdAt.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "注册于 ${state.createdAt.take(10)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!state.isLoggedIn) {
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onLogin) { Text("登录 / 注册") }
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
