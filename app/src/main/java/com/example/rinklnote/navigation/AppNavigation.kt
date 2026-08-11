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
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.rinklnote.R
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.data.network.RetrofitClient
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.component.VoiceInputBar
import com.example.rinklnote.ui.screen.assets.AssetsScreen
import com.example.rinklnote.ui.screen.bookkeeping.BookkeepingScreen
import com.example.rinklnote.ui.screen.login.LoginPage
import com.example.rinklnote.ui.screen.plan.PlanScreen
import com.example.rinklnote.ui.screen.profile.BindQQPage
import com.example.rinklnote.ui.screen.profile.ProfileScreen
import com.example.rinklnote.ui.screen.quickadd.QuickAddDrawer
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.viewmodel.AssetsViewModel
import com.example.rinklnote.ui.viewmodel.BookkeepingEvent
import com.example.rinklnote.ui.viewmodel.BudgetViewModel
import com.example.rinklnote.ui.viewmodel.BookkeepingViewModel
import com.example.rinklnote.ui.viewmodel.QuickAddEffect
import com.example.rinklnote.ui.viewmodel.QuickAddEvent
import com.example.rinklnote.ui.viewmodel.QuickAddViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val tabs = listOf("计划", "记账", "资产")

private const val AUTO_SYNC_INTERVAL_MS = 5 * 60 * 1000L  // sync every 5 minutes while on screen

@Composable
fun AppNavigation(app: RinklNoteApp) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()
    var showDrawer by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }
    var showConfirmed by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var showBindQQ by remember { mutableStateOf(false) }
    var voiceActive by remember { mutableStateOf(false) }
    var showQqBotGuide by remember { mutableStateOf(false) }

    val openDrawer: () -> Unit = {
        showDrawer = true
        showConfirmed = false
    }
    val context = LocalContext.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val tabWidth = screenWidth / tabs.size

    val bookkeepingVM: BookkeepingViewModel = viewModel(
        factory = BookkeepingViewModel.Factory(app.repository, app.syncManager)
    )
    val quickAddVM: QuickAddViewModel = viewModel(
        factory = QuickAddViewModel.Factory(app.repository, app.syncManager, app.apiService)
    )
    val assetsVM: AssetsViewModel = viewModel(
        factory = AssetsViewModel.Factory(app.repository)
    )
    val budgetVM: BudgetViewModel = viewModel(
        factory = BudgetViewModel.Factory(app.repository, app.syncManager)
    )
    val authVM: com.example.rinklnote.ui.viewmodel.AuthViewModel = viewModel(
        factory = com.example.rinklnote.ui.viewmodel.AuthViewModel.Factory(app.apiService, app.tokenManager)
    )
    val authState by authVM.state.collectAsStateWithLifecycle()
    val autoSync by app.settingsManager.autoSync.collectAsStateWithLifecycle(initialValue = true)

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
    // tab (tap or swipe) — otherwise the overlay stays on top of other tabs.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 1) {
            showDrawer = false
            showKeypad = false
            showConfirmed = false
            voiceActive = false
            quickAddVM.reset()
            quickAddVM.resetConfirming()
        }
    }

    val onTabClick: (Int) -> Unit = { index ->
        coroutineScope.launch {
            pagerState.animateScrollToPage(index)
        }
    }

    // Collect one-shot effects from QuickAddViewModel
    LaunchedEffect(quickAddVM) {
        quickAddVM.effects.collect { effect ->
            when (effect) {
                is QuickAddEffect.ConfirmRequested -> {
                    showKeypad = false
                    showConfirmed = true
                }
                is QuickAddEffect.FinalConfirmCompleted -> {
                    bookkeepingVM.onEvent(BookkeepingEvent.Refresh)
                    quickAddVM.reset()
                    showDrawer = false
                    showConfirmed = false
                }
            }
        }
    }

    // Back handler: dismiss drawer or keypad first
    BackHandler(enabled = showDrawer) { showDrawer = false }
    BackHandler(enabled = showKeypad) { showKeypad = false }

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

    val onFinalConfirm: () -> Unit = {
        quickAddVM.finalConfirm()
    }

    val onVoiceInput: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showKeypad = false
            voiceActive = true
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Page content — swipe left/right to switch tabs
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) { page ->
                when (page) {
                    0 -> PlanScreen(viewModel = budgetVM)
                    1 -> BookkeepingScreen(
                        onOpenDrawer = openDrawer,
                        onFinanceClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(2) }
                        },
                        onMoreClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(3) }
                        },
                        viewModel = bookkeepingVM
                    )
                    2 -> AssetsScreen(viewModel = assetsVM)
                    3 -> ProfileScreen(
                        authViewModel = authVM,
                        settingsManager = app.settingsManager,
                        tokenManager = app.tokenManager,
                        syncManager = app.syncManager,
                        repository = app.repository,
                        onLoginClick = { showLogin = true },
                        onBindQQClick = { showBindQQ = true },
                        onQqBotGuideClick = { showQqBotGuide = true }
                    )
                }
            }

            // Custom bottom navigation
            CustomBottomBar(
                currentIndex = pagerState.currentPage,
                tabWidth = tabWidth,
                onTabClick = onTabClick
            )
        }

        // QuickAdd Drawer overlay
        QuickAddDrawer(
            isVisible = showDrawer,
            confirmed = showConfirmed,
            viewModel = quickAddVM,
            onDismiss = {
                showDrawer = false
                showConfirmed = false
                voiceActive = false
                quickAddVM.reset()
            },
            onFinalConfirm = onFinalConfirm,
            onBillAdded = { bookkeepingVM.onEvent(BookkeepingEvent.Refresh) },
            onVoiceInput = onVoiceInput,
            onAmountTap = {
                showKeypad = true
                showConfirmed = false
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
                        billType = quickAddState.billType,
                        remark = quickAddState.remark,
                        onDigit = { quickAddVM.onEvent(QuickAddEvent.Digit(it)) },
                        onClear = { quickAddVM.onEvent(QuickAddEvent.Clear) },
                        onBackspace = { quickAddVM.onEvent(QuickAddEvent.Backspace) },
                        onToggleType = { quickAddVM.onEvent(QuickAddEvent.ToggleType) },
                        onRemarkClick = { onVoiceInput() },
                        onConfirm = { quickAddVM.onEvent(QuickAddEvent.Confirm) }
                    )
                }
            }
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
                VoiceInputBar(
                    api = app.apiService,
                    onResult = { text ->
                        voiceActive = false
                        // Voice is the NLP entry: server parse (with local fallback in the VM)
                        quickAddVM.onEvent(QuickAddEvent.NlpInput(text))
                        quickAddVM.onEvent(QuickAddEvent.NlpSubmit)
                    },
                    onDismiss = { voiceActive = false }
                )
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

@Composable
private fun CustomBottomBar(
    currentIndex: Int,
    tabWidth: Dp,
    onTabClick: (Int) -> Unit
) {
    val indicatorOffset by animateDpAsState(
        targetValue = tabWidth * currentIndex + (tabWidth - 60.dp) / 2,
        animationSpec = Motion.Indicator,
        label = "indicator"
    )

    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .shadow(4.dp, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 0.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .width(tabWidth)
                        .fillMaxSize()
                        .clickable { onTabClick(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
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
                    .width(60.dp)
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
            )
        }
        }
    }
}

