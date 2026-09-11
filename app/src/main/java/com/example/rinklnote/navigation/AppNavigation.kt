package com.example.rinklnote.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.rinklnote.ui.component.rinkShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rinklnote.R
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.data.network.RetrofitClient
import com.example.rinklnote.ui.component.AppBackground
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.component.RemarkInputSheet
import com.example.rinklnote.ui.component.VoiceInputBar
import com.example.rinklnote.ui.screen.ai.AiScreen
import com.example.rinklnote.ui.screen.assets.AssetsScreen
import com.example.rinklnote.ui.screen.bookkeeping.BillEditOverlay
import com.example.rinklnote.ui.screen.bookkeeping.BookkeepingScreen
import com.example.rinklnote.ui.screen.bookkeeping.MonthDetailOverlay
import com.example.rinklnote.ui.screen.login.LoginPage
import com.example.rinklnote.ui.screen.plan.PlanScreen
import com.example.rinklnote.ui.screen.profile.BindQQPage
import com.example.rinklnote.ui.screen.profile.BackgroundCropScreen
import com.example.rinklnote.ui.screen.profile.CustomThemeScreen
import com.example.rinklnote.ui.screen.profile.ProfileScreen
import com.example.rinklnote.ui.screen.quickadd.QuickAddDrawer
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.viewmodel.AiViewModel
import com.example.rinklnote.ui.viewmodel.AiTokenViewModel
import com.example.rinklnote.ui.viewmodel.AssetsViewModel
import com.example.rinklnote.ui.viewmodel.BookkeepingEvent
import com.example.rinklnote.ui.viewmodel.BudgetViewModel
import com.example.rinklnote.ui.viewmodel.BookkeepingViewModel
import com.example.rinklnote.ui.viewmodel.QuickAddEffect
import com.example.rinklnote.ui.viewmodel.QuickAddEvent
import com.example.rinklnote.ui.viewmodel.QuickAddViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate


data class Tabs(
    val string: String,
    val iconId: Int
)

private val tabs= listOf<Tabs>(
    Tabs("计划",R.drawable.ic_register),
    Tabs("记账",R.drawable.ic_wallet),
    Tabs("资产",R.drawable.ic_finance),
    Tabs("我的",R.drawable.ic_more_menu)
    )

// NavHost routes for the 4 bottom tabs, index-aligned with `tabs` (计划/记账/资产/我的).
// 记账(index 1) 是 start destination；AI 不在 tab 列表里，单独有 route。
private val tabRoutes = listOf("plan", "bookkeeping", "assets", "profile")

/** 过渡方向用的页面序号：tab 按 计划0/记账1/资产2/我的3；AI 是记账页里的子页，排在最右。 */
internal fun routeIndex(route: String?): Int {
    val index = tabRoutes.indexOfFirst { it == route }
    return if (index >= 0) index else tabRoutes.size
}

/**
 * +1 = 目标页在右侧（前进，新页从右滑入）；-1 = 目标页在左侧（后退，新页从左滑入）。
 * 必须按 tab 顺序判定，不能按 push/pop：`popUpTo(start)` 下「计划→记账」「我的→资产」
 * 这类切换会走 pop 分支，动画方向会和手指滑动方向相反。
 */
internal fun transitionDirection(initialRoute: String?, targetRoute: String?): Int =
    if (routeIndex(targetRoute) >= routeIndex(initialRoute)) 1 else -1

private enum class VoiceTarget { QUICK_ADD, AI }

private const val AUTO_SYNC_INTERVAL_MS = 5 * 60 * 1000L  // sync every 5 minutes while on screen

@Composable
fun AppNavigation(app: RinklNoteApp) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var showDrawer by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var showBindQQ by remember { mutableStateOf(false) }
    var voiceActive by remember { mutableStateOf(false) }
    var voiceTarget by remember { mutableStateOf(VoiceTarget.QUICK_ADD) }
    var showQqBotGuide by remember { mutableStateOf(false) }
    var showRemarkSheet by remember { mutableStateOf(false) }
    // 语音连续多笔：累计笔数 + 最近一笔的短暂确认提示。
    var voiceBookedCount by remember { mutableStateOf(0) }
    var voiceConfirm by remember { mutableStateOf<String?>(null) }

    // 浮层确认提示 2.2s 后自动消失，连续说话时每次更新都会重新计时。
    LaunchedEffect(voiceConfirm) {
        if (voiceConfirm != null) {
            delay(2200)
            voiceConfirm = null
        }
    }

    val openDrawer: () -> Unit = {
        showDrawer = true
    }
    val context = LocalContext.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val tabWidth = screenWidth / tabs.size

    val bookkeepingVM: BookkeepingViewModel = viewModel(
        factory = BookkeepingViewModel.Factory(app.repository, app.accountRepository, app.syncManager, app.apiService)
    )
    val quickAddVM: QuickAddViewModel = viewModel(
        factory = QuickAddViewModel.Factory(app.repository, app.accountRepository, app.syncManager, app.apiService)
    )
    val assetsVM: AssetsViewModel = viewModel(
        factory = AssetsViewModel.Factory(app.accountRepository, app.syncManager)
    )
    val budgetVM: BudgetViewModel = viewModel(
        factory = BudgetViewModel.Factory(app.repository, app.budgetRepository, app.syncManager)
    )
    val bookingOrchestrator = remember { BookingOrchestrator() }
    val aiVM: AiViewModel = viewModel(
        factory = AiViewModel.Factory(app.apiService, app.chatRepository, { cmd -> if (cmd is BookingCommand.ParseAndBook) bookingOrchestrator.requestBooking(cmd.text) })
    )
    val authVM: com.example.rinklnote.ui.viewmodel.AuthViewModel = viewModel(
        factory = com.example.rinklnote.ui.viewmodel.AuthViewModel.Factory(app.apiService, app.tokenManager)
    )
    val authState by authVM.state.collectAsStateWithLifecycle()
    val autoSync by app.settingsManager.autoSync.collectAsStateWithLifecycle(initialValue = true)
    // 用户自选背景图：铺在整个 nav 层之下（含底部 tab 栏），并作为全局毛玻璃采样源。
    val appBackgroundUri by app.settingsManager.backgroundUri.collectAsStateWithLifecycle(initialValue = null)
    val hazeState = remember { HazeState() }
    // 主屏小组件点分类 / 深链 rinklnote://add → 待预填参数（MainActivity 从 Intent 装入）。
    val pendingQuickAdd by app.pendingQuickAdd.collectAsStateWithLifecycle()

    val aiTokenVM: AiTokenViewModel = viewModel(
        factory = AiTokenViewModel.Factory(app.apiService)
    )

    // Periodic auto-sync while logged in (respects the auto-sync setting). The
    // QQ-bound check was removed — syncing works whether or not QQ is bound. The
    // loop restarts whenever login state or the setting changes.
    LaunchedEffect(authState.isLoggedIn, autoSync) {
        while (authState.isLoggedIn && autoSync) {
            app.syncManager.sync()
            delay(AUTO_SYNC_INTERVAL_MS)
        }
    }

    // Dismiss the QuickAdd drawer / keypad whenever the user leaves the bookkeeping
    // tab — otherwise the overlay stays on top of other tabs. (NavHost 无预组合，
    // currentRoute 只在真实导航后变化，不必像 pager 那样再靠 currentPage 补齐预组合副作用。)
    LaunchedEffect(currentRoute) {
        if (currentRoute != "bookkeeping") {
            showDrawer = false
            showKeypad = false
            voiceActive = false
            voiceBookedCount = 0
            voiceConfirm = null
            quickAddVM.reset()
            quickAddVM.resetConfirming()
        }
        // 离开 AI 页时丢弃未消费的记账在途标记，避免后续抽屉/语音记账被误判为聊天发起
        if (currentRoute != "ai") {
            aiVM.consumeBookingPending()
        }
    }

    // 主屏小组件点分类 / 深链 rinklnote://add：落到记账页并打开 QuickAdd 抽屉应用预填。
    // 起点即 "bookkeeping"，导航到同 start 路由不会触发上面「离开记账页才关抽屉」的效果，故时序安全。
    LaunchedEffect(pendingQuickAdd) {
        val pending = pendingQuickAdd ?: return@LaunchedEffect
        navController.navigate("bookkeeping") { launchSingleTop = true }
        showDrawer = true
        if (pending.categoryId != null) {
            // 小组件：仅带分类 id → 复用现有预选通路（行为不变）。
            quickAddVM.preselectCategory(pending.categoryId)
        } else {
            // 深链：金额/分类名/备注/类型 → 应用完整预填。
            quickAddVM.applyQuickAddPrefill(pending)
        }
        app.setPendingQuickAdd(null)
    }

    // AI 页进入时注入欢迎语；已登录且未关闭 AI 主动推送时按需注入月总结/异常/习惯提醒。
    // NavHost 下不存在预组合误触发问题，用 currentRoute=="ai" 门控即可。
    LaunchedEffect(currentRoute, authState.isLoggedIn, authState.aiDisabled) {
        if (currentRoute == "ai") {
            aiVM.onEnter(authState.isLoggedIn, authState.aiDisabled)
        }
    }

    // 标准 bottom-nav 切换：弹到 start(记账) 并保存/恢复各 tab 状态，确保
    // 从任意 tab 按返回都先回记账，记账页再返回才交给系统（与原来的「回首页」语义一致）。
    val navigateTo: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val onTabClick: (Int) -> Unit = { index -> navigateTo(tabRoutes[index]) }

    // Collect one-shot effects from QuickAddViewModel
    LaunchedEffect(quickAddVM) {
        quickAddVM.effects.collect { effect ->
            when (effect) {
                is QuickAddEffect.FinalConfirmCompleted -> {
                    if (aiVM.consumeBookingPending()) {
                        // 必须先读 state 再 reset——确认文本来自记账成功时的 amount/分类
                        val s = quickAddVM.state.value
                        val cat = s.selectedCategory?.name ?: ""
                        aiVM.appendBookingConfirmed("记好嘞！${s.amount}元（$cat）已入账～")
                    } else {
                        Toast.makeText(context, "已记账", Toast.LENGTH_SHORT).show()
                    }
                    bookkeepingVM.onEvent(BookkeepingEvent.Refresh)
                    quickAddVM.reset()
                    showDrawer = false
                    showKeypad = false
                }
                is QuickAddEffect.FinalConfirmFailed -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is QuickAddEffect.VoiceBillBooked -> {
                    voiceBookedCount += 1
                    voiceConfirm = "已记：${effect.summary} · 第${voiceBookedCount}笔"
                }
                is QuickAddEffect.VoiceNoAmount -> {
                    voiceConfirm = "没抓到金额，再说一次～"
                }
            }
        }
    }

    // Back handler: dismiss drawer or keypad first
    BackHandler(enabled = showDrawer) { showDrawer = false }
    BackHandler(enabled = showKeypad) { showKeypad = false }

    // 返回交给 NavHost 的返回栈处理：在 AI 或非首页 tab 按返回会 pop 回记账(start)，
    // 在记账页按返回交给系统默认。页面内的弹窗（预算键盘/月明细/余额弹窗等）
    // 有自己的 BackHandler，组合优先级更高，会先于导航返回被消费。

    // Voice input: bottom floating mini bar (device real-time recognition, server
    // Whisper fallback). RECORD_AUDIO runtime permission is required before recording.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showKeypad = false
            voiceActive = true
        } else {
            Toast.makeText(context, "需要录音权限才能使用语音记账", Toast.LENGTH_SHORT).show()
        }
    }

    val startVoice: (VoiceTarget) -> Unit = { target ->
        voiceTarget = target
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showKeypad = false
            voiceActive = true
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 语音会话结束（✕/完成或返回键）：关语音条 + 抽屉，清空多笔计数并重置抽屉状态。
    val endVoiceSession: () -> Unit = {
        voiceActive = false
        voiceTarget = VoiceTarget.QUICK_ADD
        showDrawer = false
        voiceBookedCount = 0
        voiceConfirm = null
        quickAddVM.reset()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 自定义背景铺满整窗：底部 tab 栏、状态栏、导航栏都在同一张图上，避免底部露出纯色条。
        AppBackground(backgroundUri = appBackgroundUri, hazeState = hazeState)

        Column(modifier = Modifier.fillMaxSize()) {
            // Page content — 5 个 flat destination 由 NavHost 切换。
            // 水平滑动过渡保留原 pager 的左右平移手感；NavHost 无预组合，
            // 只有当前 destination 会被组合（AI 注入因此不再需要 currentPage 门控补齐）。
            NavHost(
                navController = navController,
                startDestination = "bookkeeping",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                // 前进/后退一律按 tab 顺序给方向，push 与 pop 用同一套，保证滑动方向与页面顺序一致。
                enterTransition = {
                    val dir = transitionDirection(initialState.destination.route, targetState.destination.route)
                    slideInHorizontally(initialOffsetX = { dir * it }) + fadeIn()
                },
                exitTransition = {
                    val dir = transitionDirection(initialState.destination.route, targetState.destination.route)
                    slideOutHorizontally(targetOffsetX = { -dir * it }) + fadeOut()
                },
                popEnterTransition = {
                    val dir = transitionDirection(initialState.destination.route, targetState.destination.route)
                    slideInHorizontally(initialOffsetX = { dir * it }) + fadeIn()
                },
                popExitTransition = {
                    val dir = transitionDirection(initialState.destination.route, targetState.destination.route)
                    slideOutHorizontally(targetOffsetX = { -dir * it }) + fadeOut()
                }
            ) {
                composable("plan") {
                    PlanScreen(
                        viewModel = budgetVM,
                        isActive = currentRoute == "plan",
                        backgroundUri = appBackgroundUri,
                        hazeState = hazeState
                    )
                }
                composable("bookkeeping") {
                    BookkeepingScreen(
                        onOpenDrawer = openDrawer,
                        onFinanceClick = { navigateTo("assets") },
                        onMoreClick = { navigateTo("profile") },
                        onAiClick = { navigateTo("ai") },
                        onMonthDetailClick = { navController.navigate("month-detail") },
                        onEditBill = { navController.navigate("bill-edit") },
                        backgroundUri = appBackgroundUri,
                        hazeState = hazeState,
                        viewModel = bookkeepingVM
                    )

                }
                composable("bill-edit") {
                    val state by bookkeepingVM.editState.collectAsStateWithLifecycle()
                    state.editingBill?.let { bill ->
                        BillEditOverlay(
                            bill = bill,
                            expenseCategories = state.expenseCategories,
                            incomeCategories = state.incomeCategories,
                            accounts = state.accounts,
                            onCancel = {
                                bookkeepingVM.onEvent(BookkeepingEvent.CancelEdit)
                                navController.popBackStack()
                            },
                            onConfirm = { newBill ->
                                bookkeepingVM.onEvent(BookkeepingEvent.ConfirmEdit(newBill))
                                navController.popBackStack()
                            },
                            onLoadSubCategories = bookkeepingVM::subCategories
                        )
                    }
                }
                composable("month-detail") {
                    val state by bookkeepingVM.monthState.collectAsStateWithLifecycle()
                    val monthLabel = remember(state.selectedMonthOffset) {
                        val d = LocalDate.now().plusMonths(state.selectedMonthOffset.toLong())
                        "${d.year}年${d.monthValue}月"
                    }
                    MonthDetailOverlay(
                        monthLabel = monthLabel,
                        bills = state.bills,
                        month = LocalDate.now().plusMonths(state.selectedMonthOffset.toLong()).withDayOfMonth(1),
                        expenseTotal = state.totalExpense,
                        incomeTotal = state.totalIncome,
                        monthDetail = state.monthDetail,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("assets") {
                    AssetsScreen(
                        viewModel = assetsVM,
                        backgroundUri = appBackgroundUri,
                        hazeState = hazeState
                    )
                }
                composable("profile") {
                    ProfileScreen(
                        authViewModel = authVM,
                        settingsManager = app.settingsManager,
                        tokenManager = app.tokenManager,
                        syncManager = app.syncManager,
                        repository = app.repository,
                        aiTokenViewModel = aiTokenVM,
                        onLoginClick = { showLogin = true },
                        onBindQQClick = { showBindQQ = true },
                        onQqBotGuideClick = { showQqBotGuide = true },
                        onCustomThemeClick = { navController.navigate("custom-theme") },
                        onCropBackground = { uri ->
                            // 图库选完 → 取景框裁剪路由（content:// 仅会话内可读，须当场裁剪落盘）
                            navController.navigate("background-crop/" + Uri.encode(uri.toString()))
                        },
                        backgroundUri = appBackgroundUri,
                        hazeState = hazeState
                    )
                }
                composable("ai") {
                    AiScreen(
                        viewModel = aiVM,
                        isLoggedIn = authState.isLoggedIn,
                        onVoiceInput = { startVoice(VoiceTarget.AI) }
                    )
                }
                // 自定义主题：非 tab 路由 → 底栏自动隐藏、内容区占满全屏。
                composable("custom-theme") {
                    CustomThemeScreen(
                        settingsManager = app.settingsManager,
                        backgroundUri = appBackgroundUri,
                        hazeState = hazeState,
                        onBack = { navController.popBackStack() }
                    )
                }
                // 背景取景框裁剪：图库选图后的中间路由，非 tab 路由 → 底栏自动隐藏、内容区占满全屏。
                composable(
                    route = "background-crop/{uri}",
                    arguments = listOf(navArgument("uri") { type = NavType.StringType })
                ) { entry ->
                    val uriStr = entry.arguments?.getString("uri").orEmpty()
                    if (uriStr.isNotBlank()) {
                        val scope = rememberCoroutineScope()
                        BackgroundCropScreen(
                            imageUri = Uri.parse(uriStr),
                            onConfirm = { path ->
                                scope.launch {
                                    app.settingsManager.setBackgroundUri(path)
                                    navController.popBackStack()
                                }
                            },
                            onCancel = { navController.popBackStack() }
                        )
                    }
                }
            }

            // Custom bottom navigation — 仅 4 个 tab route (plan/bookkeeping/assets/profile) 显示；
            // AI(ai) 整条隐藏。
            val currentTabIndex = tabRoutes.indexOf(currentRoute)
            if (currentTabIndex >= 0) {
                CustomBottomBar(
                    currentIndex = currentTabIndex,
                    tabWidth = tabWidth,
                    // 只有铺了自选背景才玻璃化：没有背景图时毛玻璃无从采样，会露出灰调兜底色。
                    hazeState = hazeState.takeIf { appBackgroundUri != null },
                    onTabClick = onTabClick
                )
            }
        }

        // QuickAdd Drawer overlay
        QuickAddDrawer(
            isVisible = showDrawer,
            viewModel = quickAddVM,
            // 有自选背景才给毛玻璃采样源：无背景时抽屉保持纯白实心（同 CustomBottomBar 的开关逻辑）。
            backgroundUri = appBackgroundUri,
            hazeState = hazeState.takeIf { appBackgroundUri != null },
            onDismiss = {
                showDrawer = false
                voiceActive = false
                quickAddVM.reset()
            },
            onBillAdded = { bookkeepingVM.onEvent(BookkeepingEvent.Refresh) },
            onVoiceInput = { startVoice(VoiceTarget.QUICK_ADD) },
            onAmountTap = {
                showKeypad = true
                quickAddVM.resetConfirming()
            }
        )

        // Numeric keypad overlay — slides up from bottom
        val quickAddState by quickAddVM.state.collectAsStateWithLifecycle()
        AnimatedVisibility(
            visible = showKeypad,
            enter = slideInVertically(
                animationSpec = Motion.SheetEnter,
                initialOffsetY = { it }
            ),
            exit = slideOutVertically(
                animationSpec = Motion.SheetExit,
                targetOffsetY = { it }
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showKeypad = false },
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                        .padding(top = 20.dp, bottom = 16.dp)
                        .clickable(enabled = false) {}
                ) {
                    NumericKeypad(
                        amount = quickAddState.amount,
                        billType = quickAddState.billType.value,
                        remark = quickAddState.remark,
                        onDigit = { quickAddVM.onEvent(QuickAddEvent.Digit(it)) },
                        onClear = { quickAddVM.onEvent(QuickAddEvent.Clear) },
                        onBackspace = { quickAddVM.onEvent(QuickAddEvent.Backspace) },
                        onToggleType = { quickAddVM.onEvent(QuickAddEvent.ToggleType) },
                        onRemarkClick = { showRemarkSheet = true },
                        onConfirm = { quickAddVM.onEvent(QuickAddEvent.Confirm) }
                    )
                }
            }
        }

        // Remark bottom sheet — overlaid on top of the keypad so users can type a
        // remark instead of (or in addition to) voice input.
        if (showRemarkSheet) {
            RemarkInputSheet(
                initialText = quickAddState.remark,
                onConfirm = { text ->
                    showRemarkSheet = false
                    quickAddVM.onEvent(QuickAddEvent.RemarkChanged(text))
                },
                onDismiss = { showRemarkSheet = false }
            )
        }

        // Full-screen login / bind-QQ pages — top-most so they cover the bottom nav
        if (showLogin) {
            LoginPage(viewModel = authVM, onDismiss = { showLogin = false })
        }
        if (showBindQQ) {
            BindQQPage(viewModel = authVM, onDismiss = { showBindQQ = false })
        }

        // Bottom floating voice bar — no full-screen page. Device recognizer streams
        // live text while speaking; on success the transcript fills the bill via NLP.
        AnimatedVisibility(
            visible = voiceActive,
            enter = slideInVertically(animationSpec = Motion.SheetEnter, initialOffsetY = { it }),
            exit = slideOutVertically(animationSpec = Motion.SheetExit, targetOffsetY = { it })
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 连续多笔的计数 + 最近一笔的短暂确认，浮在语音条上方。
                    AnimatedVisibility(visible = voiceConfirm != null) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .rinkShadow(RoundedCornerShape(18.dp))
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = voiceConfirm ?: "",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    if (voiceBookedCount > 0 && voiceConfirm == null) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "已记 $voiceBookedCount 笔",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    VoiceInputBar(
                        api = app.apiService,
                        onResult = { text ->
                            val target = voiceTarget
                            if (target == VoiceTarget.AI) {
                                // 语音在 AI 页发起 → 走聊天路由；单笔，语音条关闭。
                                voiceActive = false
                                voiceTarget = VoiceTarget.QUICK_ADD
                                aiVM.send(text)
                            } else {
                                // 抽屉语音记账：连续多笔，逐笔入库，语音条保持聆听、不动抽屉。
                                quickAddVM.onEvent(QuickAddEvent.VoiceUtterance(text))
                            }
                        },
                        onDismiss = endVoiceSession,
                        continuous = voiceTarget == VoiceTarget.QUICK_ADD
                    )
                }
            }
        }

        // QQ 机器人绑定引导 — deep-links to the web binding page
        if (showQqBotGuide) {
            QqBotGuideDialog(
                botBound = authState.botBound,
                onOpenWeb = {
                    showQqBotGuide = false
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(RetrofitClient.BASE_URL))
                        )
                    } catch (e: Exception) {
                        Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { showQqBotGuide = false }
            )
        }
    }
}

@Composable
private fun QqBotGuideDialog(
    botBound: Boolean,
    onOpenWeb: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("QQ 机器人绑定") },
        text = {
            Column {
                Text(
                    text = "状态：" + if (botBound) "已绑定（可在 QQ 里直接发消息记账）" else "未绑定",
                    fontSize = 14.sp,
                    color = if (botBound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("绑定步骤：", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("1. 在 QQ 中给机器人发送任意消息，机器人会回复 6 位绑定码；", fontSize = 13.sp)
                Text("2. 打开网页并登录同一账号；", fontSize = 13.sp)
                Text("3. 在网页「账号绑定」中输入绑定码完成绑定。", fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "绑定后即可通过 QQ 给机器人发消息记账，账单会自动同步。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onOpenWeb) { Text("前往网页完成绑定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

//@Composable
//private fun CustomBottomBar(
//    currentIndex: Int ,
//    tabWidth: Dp,
//    onTabClick: (Int) -> Unit
//) {
//    val indicatorOffset by animateDpAsState(
//        targetValue = tabWidth * currentIndex + (tabWidth - 60.dp) / 2,
//        animationSpec = Motion.Indicator,
//        label = "indicator"
//    )
//
//    Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp, start = 10.dp, end = 10.dp)) {
//        Box(
//            modifier = Modifier
//                .fillMaxWidth()
//                .height(32.dp)
//                .shadow(4.dp, RoundedCornerShape(30.dp))
//                .clip(RoundedCornerShape(30.dp))
//                .background(MaterialTheme.colorScheme.surface)
//        ) {
//        Row(modifier = Modifier.fillMaxSize()) {
//            tabs.forEachIndexed { index, label ->
//                Box(
//                    modifier = Modifier
//                        .width(tabWidth)
//                        .fillMaxSize()
//                        .clickable { onTabClick(index) },
//                    contentAlignment = Alignment.Center
//                ) {
//                    Text(
//                        text = label,
//                        fontSize = 14.sp,
//                        fontWeight = FontWeight.Medium,
//                        color = MaterialTheme.colorScheme.onSurface
//                    )
//                }
//            }
//        }
//
//        // Blue indicator — align to bottom-start then offset. Hidden on the 我的 page
//        // (index 3) which has no bottom tab.
//        if (currentIndex < tabs.size) {
//            Box(
//                modifier = Modifier
//                    .align(Alignment.BottomStart)
//                    .offset(x = indicatorOffset)
//                    .width(60.dp)
//                    .height(5.dp)
//                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
//            )
//        }
//        }
//    }
//}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun CustomBottomBar(
    currentIndex: Int ,
    tabWidth: Dp,
    hazeState: HazeState?,
    onTabClick: (Int) -> Unit
) {
    val indicatorOffset by animateDpAsState(
        targetValue = tabWidth * currentIndex + (tabWidth - 40.dp) / 2,
        animationSpec = Motion.Indicator,
        label = "indicator"
    )

    val barSurface = if (hazeState != null) {
        // 毛玻璃（重点区）：采样 nav 层的自定义背景，底栏不再是一块挡住壁纸的实心白。
        // 统一用 RinklCardFrostedStyle 白雾玻璃，与首页首支总览/总资产/本月预算一致。
        Modifier.hazeEffect(hazeState, com.example.rinklnote.ui.component.RinklCardFrostedStyle)
    } else {
        Modifier.background(MaterialTheme.colorScheme.surface)
    }

    // 图标/按钮色：自定义主题第 4 项；未自定义时跟随字体色（深色模式自动反白）。
    val iconTint = LocalRinklColors.current.iconButtonColor ?: MaterialTheme.colorScheme.onSurface

    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp, start = 10.dp, end = 10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(20.dp))
                .then(barSurface)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, label ->
                    Column(
                        modifier = Modifier
                            .width(tabWidth)
                            .fillMaxSize()
                            .clickable { onTabClick(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(label.iconId),
                            contentDescription = label.string,
                            tint = iconTint,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = label.string,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = iconTint
                        )
                    }
                }
            }

            // Blue indicator — align to bottom-start then offset. Hidden on the 我的 page
            // (index 3) which has no bottom tab.
            if (currentIndex < tabs.size) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = indicatorOffset)
                        .width(40.dp)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                )
            }
        }
    }
}

