package com.example.rinklnote.ui.screen.profile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.example.rinklnote.ui.component.RinklCardFrostedStyle
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthState
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import com.example.rinklnote.ui.viewmodel.AiTokenViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import com.example.rinklnote.util.bookkeepingZone
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 我的页（Profile）—— 账户、同步、日报、AI 等设置入口。
 *
 * 风格深度对齐首页（Bookkeeping）毛玻璃气质，按 UI 设计规范执行：
 * - `Box` 根 + `DefaultHazeBackground` 渐变 + 3 个装饰光斑作毛玻璃 blur 源；自选照片时由 nav 层整窗铺满。
 * - 悬浮顶栏（floating top bar）：极简，仅居中「我的」标题 + scrim 渐隐（滚动后渐显）。
 * - 卡片 `rinkShadow` + `hazeEffect(RinklCardFrostedStyle)` 毛玻璃 + 圆角 16dp 梯度；`hazeEffect` 后只接 padding，绝不加 background（否则遮盖毛玻璃）。
 * - 间距 4px 梯度（4/8/12/16/20/24/32dp），字号 12/14/16/18/20sp 梯度，触摸目标 ≥ 44dp（IconButton 48dp / defaultMinSize 44dp）。
 *
 * 业务不变量：所有事件回调、AuthState/SettingsManager/TokenManager 读写、dialog 状态全保留不动。
 * ProfileScreen 签名只 +`backgroundUri` +`hazeState` 两个透传参数（nav 层提供），不动业务。
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
 * @param backgroundUri nav 层透传的自选背景照片 URI；`null` 时本页自铺渐变
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
    backgroundUri: String?,
    hazeState: HazeState
) {
    val state by authViewModel.state.collectAsStateWithLifecycle()
    val themeMode by settingsManager.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val autoSync by settingsManager.autoSync.collectAsStateWithLifecycle(initialValue = true)
    val background by settingsManager.backgroundUri.collectAsStateWithLifecycle(initialValue = null)
    val lastSync by tokenManager.lastSyncTime.collectAsStateWithLifecycle(initialValue = 0L)
    val dailyReportEnabled by settingsManager.dailyReportEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dailyReportHour by settingsManager.dailyReportHour.collectAsStateWithLifecycle(initialValue = 9)
    val dailyReportMinute by settingsManager.dailyReportMinute.collectAsStateWithLifecycle(initialValue = 0)
    val dailyReportQqBot by settingsManager.dailyReportQqBot.collectAsStateWithLifecycle(initialValue = false)
    var showTimePicker by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

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

    // 图库选背景：拷到应用内部存储，写回设置。
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

    // 登录态变化时刷新 profile。
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) authViewModel.onEvent(AuthEvent.FetchProfile)
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 顶栏悬浮：列表首项垫到它下面。高度 = 状态栏避让 + 标题行（20sp + 上下各 8dp = 36dp + 余量）。
    val topBarHeight = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + 48.dp
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    // 配图背景或滚动后顶栏 scrim 渐显；无照片且未滚动时 scrim 透明（标题直接压在渐变背景上）。
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (backgroundUri != null || listScrolled) 1f else 0f,
        label = "profileTopBarScrim"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 毛玻璃源：有自选照片时由 nav 层整窗铺满；无照片时本页铺渐变+光斑（公共 DefaultHazeBackground）。
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

            item(key = "header") {
                ProfileHeader(state = state, onLogin = onLoginClick, hazeState = hazeState, backgroundUri = backgroundUri)
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
                    },
                    hazeState = hazeState
                )
            }
            item(key = "dailyReport") {
                DailyReportCard(
                    enabled = dailyReportEnabled,
                    hour = dailyReportHour,
                    minute = dailyReportMinute,
                    qqBot = dailyReportQqBot,
                    onEnabledChange = onDailyReportEnabledChange,
                    onTimeClick = { showTimePicker = true },
                    onQqBotChange = onDailyReportQqBotChange,
                    hazeState = hazeState
                )
            }
            item(key = "account") {
                AccountCard(
                    state = state,
                    themeMode = themeMode,
                    backgroundUri = background,
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
                    onAiTokenClick = { showAiToken = true },
                    hazeState = hazeState
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
                    onLogoutClick = { showLogout = true },
                    hazeState = hazeState,
                    backgroundUri = backgroundUri
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

    if (showTimePicker) {
        TimePickerDialog(
            hour = dailyReportHour,
            minute = dailyReportMinute,
            onConfirm = { h, m ->
                showTimePicker = false
                coroutineScope.launch {
                    settingsManager.setDailyReportTime(h, m)
                    if (dailyReportEnabled) DailyReportReceiver.schedule(context, h, m)
                }
            },
            onDismiss = { showTimePicker = false }
        )
    }
    if (showAiToken) {
        AiTokenDialog(
            viewModel = aiTokenViewModel,
            onDismiss = { showAiToken = false }
        )
    }
}

/** 我的页悬浮顶栏：极简，仅居中「我的」标题 + scrim 渐隐（滚动后渐显）。 */
@Composable
private fun ProfileTopBar(scrimAlpha: Float, hasBackground: Boolean, listScrolled: Boolean) {
    // 文字色三态：有背景→白；无背景+顶部→深色（onSurface）；无背景+滚动→浅灰（onSurfaceVariant）
    val textColor = when {
        hasBackground -> Color.White
        !listScrolled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
private fun ProfileHeader(state: AuthState, onLogin: () -> Unit, hazeState: HazeState, backgroundUri: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (backgroundUri != null) {
                    // 有自选背景：透明 + 白色描边
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                } else {
                    // 无自选背景：白色实心卡片（我的页首个组件）
                    Modifier.background(MaterialTheme.colorScheme.surface)
                }
            )
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!state.isLoggedIn) {
            AvatarBadge(character = "账", size = 56.dp)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "未登录",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "登录后即可云端同步账单",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onLogin) { Text("登录 / 注册") }
        } else {
            AvatarBadge(character = state.accountPhone.firstOrNull()?.toString() ?: "账", size = 56.dp)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = maskPhone(state.accountPhone),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "QQ ${if (state.isQQBound) state.qqNumber else "未绑定"} · 机器人 ${if (state.botBound) "已绑定" else "未绑定"}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.createdAt.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "注册于 ${state.createdAt.take(10)}",
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

/**
 * 分组卡片：浅色小标题（可选）+ 多行设置；毛玻璃材质（[RinklCardFrostedStyle]）。
 * `hazeEffect` 后只接 padding，绝不加 background（否则遮盖毛玻璃）。
 */
@Composable
private fun GroupCard(
    title: String? = null,
    hazeState: HazeState,
    backgroundUri: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .then(applyCardGlass(hazeState, backgroundUri, RoundedCornerShape(16.dp)))
            .padding(vertical = 4.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            )
        }
        content()
    }
}

/** 单行设置：前置图标 + 标签 + 右侧值/「>」/开关。触摸目标 ≥ 44dp（defaultMinSize）。 */
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
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = label,
            fontSize = 16.sp,
            color = labelColor,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
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
    onSyncNow: () -> Unit,
    hazeState: HazeState
) {
    GroupCard(title = "同步", hazeState = hazeState) {
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
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        }
    }
}

/** «日报通知» 组：本地通知开关 + 通知时间 + QQ 日报推送。 */
@Composable
private fun DailyReportCard(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    qqBot: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTimeClick: () -> Unit,
    onQqBotChange: (Boolean) -> Unit,
    hazeState: HazeState
) {
    GroupCard(title = "日报通知", hazeState = hazeState) {
        SettingsRow(
            icon = R.drawable.ic_notification,
            label = "每日日报通知",
            trailing = { Switch(checked = enabled, onCheckedChange = onEnabledChange) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow(
            icon = R.drawable.ic_notification,
            label = "通知时间",
            value = "%02d:%02d".format(hour, minute),
            onClick = onTimeClick
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow(
            icon = R.drawable.ic_link,
            label = "QQ 日报推送",
            trailing = { Switch(checked = qqBot, onCheckedChange = onQqBotChange) }
        )
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
    onAiTokenClick: () -> Unit,
    hazeState: HazeState
) {
    GroupCard(title = "账户与个性化", hazeState = hazeState) {
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
    onLogoutClick: () -> Unit,
    hazeState: HazeState,
    backgroundUri: String? = null
) {
    GroupCard(title = "关于", hazeState = hazeState, backgroundUri = backgroundUri) {
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
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable { onSelect(mode) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = { onSelect(mode) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(themeLabel(mode), fontSize = 16.sp)
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
