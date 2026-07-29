package com.example.rinklnote.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.screen.assets.AssetsScreen
import com.example.rinklnote.ui.screen.bookkeeping.BookkeepingScreen
import com.example.rinklnote.ui.screen.plan.PlanScreen
import com.example.rinklnote.ui.screen.quickadd.QuickAddDrawer
import com.example.rinklnote.ui.viewmodel.AssetsViewModel
import com.example.rinklnote.ui.viewmodel.BookkeepingEvent
import com.example.rinklnote.ui.viewmodel.BookkeepingViewModel
import com.example.rinklnote.ui.viewmodel.QuickAddEffect
import com.example.rinklnote.ui.viewmodel.QuickAddEvent
import com.example.rinklnote.ui.viewmodel.QuickAddViewModel
import com.example.rinklnote.util.VoiceParser
import kotlinx.coroutines.launch

private val tabs = listOf("计划", "记账", "资产")

@Composable
fun AppNavigation(app: RinklNoteApp) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    var showDrawer by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }
    var showConfirmed by remember { mutableStateOf(false) }

    val openDrawer: () -> Unit = {
        showDrawer = true
        showConfirmed = false
    }
    val context = LocalContext.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val tabWidth = screenWidth / 3

    val bookkeepingVM: BookkeepingViewModel = viewModel(
        factory = BookkeepingViewModel.Factory(app.repository)
    )
    val quickAddVM: QuickAddViewModel = viewModel(
        factory = QuickAddViewModel.Factory(app.repository)
    )
    val assetsVM: AssetsViewModel = viewModel(
        factory = AssetsViewModel.Factory(app.repository)
    )
    val authVM: com.example.rinklnote.ui.viewmodel.AuthViewModel = viewModel(
        factory = com.example.rinklnote.ui.viewmodel.AuthViewModel.Factory(app.apiService, app.tokenManager)
    )

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

    // Voice input launchers
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull() ?: ""
            val parsed = VoiceParser.parse(spokenText)
            if (parsed.amount != null) {
                quickAddVM.onEvent(QuickAddEvent.Clear)
                val amountStr = if (parsed.amount == parsed.amount.toLong().toDouble()) {
                    parsed.amount.toLong().toString()
                } else {
                    parsed.amount.toString()
                }
                amountStr.forEach { digit ->
                    quickAddVM.onEvent(QuickAddEvent.Digit(digit.toString()))
                }
            }
            if (parsed.remark.isNotBlank()) {
                quickAddVM.onEvent(QuickAddEvent.RemarkChanged(parsed.remark))
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceRecognition(context, voiceLauncher)
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
            startVoiceRecognition(context, voiceLauncher)
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
                    0 -> PlanScreen()
                    1 -> BookkeepingScreen(
                        onOpenDrawer = openDrawer,
                        viewModel = bookkeepingVM
                    )
                    2 -> AssetsScreen(
                        viewModel = assetsVM,
                        authViewModel = authVM,
                        syncManager = app.syncManager
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
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
                initialOffsetY = { it }
            ),
            exit = slideOutVertically(
                animationSpec = tween(250),
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
    }
}

@Composable
private fun CustomBottomBar(
    currentIndex: Int,
    tabWidth: Dp,
    onTabClick: (Int) -> Unit
) {
    val indicatorOffset by animateDpAsState(
        targetValue = tabWidth * currentIndex + (tabWidth - 60.dp) / 2,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
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

        // Blue indicator — align to bottom-start then offset
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

private fun startVoiceRecognition(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "说出消费内容，如：午餐二十元")
    }
    try {
        launcher.launch(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "语音识别不可用", Toast.LENGTH_SHORT).show()
    }
}
