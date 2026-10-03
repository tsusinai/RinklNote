package com.example.rinklnote.ui.screen.quickadd

import java.util.Locale


import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.ui.component.AccountIcon
import com.example.rinklnote.ui.component.BillFeedback
import com.example.rinklnote.ui.component.LocalRinklFeedback
import com.example.rinklnote.ui.component.RinklCardFrostedStyle
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.pressScale
import com.example.rinklnote.util.LocationGrabber
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import com.example.rinklnote.ui.theme.AxisLabelGray
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.DefaultCardBorder
import com.example.rinklnote.ui.theme.BackgroundLight
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.ui.util.categoryIconRes
import com.example.rinklnote.ui.util.rememberPressHaptics
import com.example.rinklnote.util.Money
import com.example.rinklnote.ui.viewmodel.QuickAddEvent
import com.example.rinklnote.ui.viewmodel.QuickAddState
import com.example.rinklnote.ui.viewmodel.QuickAddViewModel
import com.example.rinklnote.ui.viewmodel.SuggestionSource
import kotlinx.coroutines.launch

/**
 * 抽屉自适应宽度（体验审查 P1）：固定 200dp 在内容变多后过窄，改为可读上限 320dp、
 * 小屏按屏宽 0.88 占比收缩（避免贴满遮住主页面）。快捷记账与「更多」抽屉同款。
 */
@Composable
private fun adaptiveDrawerWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return minOf(320.dp, screenWidth * 0.88f)
}

/** 跳系统设置的本应用详情页（定位权限/定位服务关闭时「去设置」用）。 */
private fun openAppSettings(context: android.content.Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun QuickAddDrawer(
    isVisible: Boolean,
    viewModel: QuickAddViewModel,
    onDismiss: () -> Unit,
    onBillAdded: () -> Unit,
    onVoiceInput: () -> Unit = {},
    onAmountTap: () -> Unit = {},
    // 有自选背景时面板切毛玻璃所需的两件套；nav 层按「有背景」条件传入（同 CustomBottomBar）。
    backgroundUri: String? = null,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = isVisible) { onDismiss() }

    // 面板材质：有自选背景 → 底部导航同款白雾毛玻璃（采样 AppBackground 的照片）；
    // 无背景 → 纯白实心（毛玻璃无从采样，会露灰调兜底色）。
    val panelShape = RoundedCornerShape(18.dp)
    val panelSurface = if (hazeState != null) {
        Modifier.hazeEffect(hazeState, RinklCardFrostedStyle)
    } else {
        Modifier.background(MaterialTheme.colorScheme.surface)
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(
            animationSpec = Motion.DrawerEnter,
            initialOffsetX = { it }
        ),
        exit = slideOutHorizontally(
            animationSpec = Motion.DrawerExit,
            targetOffsetX = { it }
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        ) {
            // Drawer panel
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(0.88f)
                    .width(adaptiveDrawerWidth())
                    .padding(vertical = 8.dp)
                    .clip(panelShape)
                    .then(panelSurface)
                    .then(applyCardGlass(panelShape))
                    .clickable(enabled = false) {} // consume click
                    .pointerInput(Unit) {
                        // 关闭阈值先按 dp 换算成像素：原实现直接写死 60 像素，
                        // 不同屏幕密度下手感不一致（高密度屏约 20dp 就触发，低密度屏要拖满 60dp）。
                        val closeThresholdPx = 60.dp.toPx()
                        var dragOffset = 0f
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragOffset > closeThresholdPx) onDismiss()
                                dragOffset = 0f
                            },
                            onDragCancel = { dragOffset = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                dragOffset += dragAmount
                            },
                            onDragStart = { dragOffset = 0f }
                        )
                    }
            ) {
                DrawerContent(
                    viewModel = viewModel,
                    onDismiss = onDismiss,
                    onBillAdded = onBillAdded,
                    onVoiceInput = onVoiceInput,
                    onAmountTap = onAmountTap,
                    onToggleType = {
                        viewModel.onEvent(QuickAddEvent.ToggleType)
                    }
                )
            }
        }
    }
}

@Composable
private fun DrawerContent(
    viewModel: QuickAddViewModel,
    onDismiss: () -> Unit,
    onBillAdded: () -> Unit,
    onVoiceInput: () -> Unit,
    onAmountTap: () -> Unit,
    onToggleType: () -> Unit
) {
    // Collect state once — children read from snapshot, no duplicate subscriptions
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balanceHidden by BalancePrivacy.hidden.collectAsStateWithLifecycle()

    // Fetch smart suggestion and trigger smart prefill once when the drawer opens
    LaunchedEffect(Unit) {
        viewModel.onEvent(QuickAddEvent.DrawerOpened)
        val s = viewModel.state.value
        if (s.suggestion == null && !s.suggestionDismissed) {
            viewModel.loadSuggestion()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // 可滚动内容区
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("快捷记账", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Icon(
                    painter = painterResource(R.drawable.ic_register),
                    contentDescription = "登记",
                    modifier = Modifier.size(26.dp),
                    tint = Color.Unspecified
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 智能建议卡（体验优化 Task 3）：依据 / 模糊金额 / 固定与关闭均为可见入口；
            // 关闭或自动收起后出现「重新显示」入口，不依赖用户猜测手势。
            val aiSuggestion = state.suggestion
            if (aiSuggestion != null) {
                AiSuggestionCard(
                    suggestion = aiSuggestion,
                    pinned = state.suggestionPinned,
                    onApply = { viewModel.onEvent(QuickAddEvent.SuggestionClick) },
                    onDismiss = { viewModel.onEvent(QuickAddEvent.DismissSuggestion) },
                    onTogglePin = { viewModel.onEvent(QuickAddEvent.ToggleSuggestionPin) },
                    onEditAmount = onAmountTap
                )
                Spacer(modifier = Modifier.height(16.dp))
            } else if (state.suggestionRestorable) {
                SuggestionReshowChip(onShow = { viewModel.onEvent(QuickAddEvent.ShowSuggestion) })
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 常去地点建议：打点位置接近已知地点时出现（「用」= 选中该地点的常用分类）。
            state.placeSuggestion?.let { place ->
                SuggestionSection(
                    label = "上次在「${place.placeName}」附近记过" +
                        (place.categoryName?.let { " · 常用「$it」" } ?: ""),
                    onUse = { viewModel.onEvent(QuickAddEvent.PlaceSuggestionClick) },
                    onDismiss = { viewModel.onEvent(QuickAddEvent.DismissPlaceSuggestion) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (state.templates.isNotEmpty()) {
                TemplatesSection(
                    templates = state.templates,
                    onTemplateClick = { viewModel.onEvent(QuickAddEvent.TemplateClick(it)) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            CategorySection(state.categories, state.selectedCategory, state.showSubCategories, state.expandedParentId, state.subCategories, state.selectedSubCategory, state.parentIdsWithSubs, viewModel)

            Spacer(modifier = Modifier.height(16.dp))

            AccountSection(state.accounts, state.selectedAccount, balanceHidden, viewModel)

            Spacer(modifier = Modifier.height(12.dp))

            // 位置 + 小票 OCR：一行等宽双 chip（与账户卡片同宽对齐）
            var showOcrFlow by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LocationChipRow(
                    location = state.location,
                    onResolved = { lat, lng ->
                        viewModel.onEvent(QuickAddEvent.LocationResolved(lat, lng))
                    },
                    onCleared = { viewModel.onEvent(QuickAddEvent.ClearLocation) },
                    modifier = Modifier.weight(1f)
                )
                OcrChipRow(
                    onClick = { showOcrFlow = true },
                    modifier = Modifier.weight(1f)
                )
            }
            if (showOcrFlow) {
                ReceiptOcrDialog(
                    onDismiss = { showOcrFlow = false },
                    onPickAmount = { viewModel.onEvent(QuickAddEvent.OcrAmountPicked(it)) },
                    onPickDate = { viewModel.onEvent(QuickAddEvent.OcrDatePicked(it)) },
                    onPickMerchant = { viewModel.onEvent(QuickAddEvent.OcrMerchantPicked(it)) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Count area — single-step amount box
            CountBefore(state, onAmountTap, onToggleType)
        }

        // AI Voice button — 固定在抽屉底部上方，不随内容滚动，避开底部手势区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            // 抽屉内的主按钮：按压缩放反馈（显式传 LocalIndication 保留原有水波纹，二者叠加不冲突）
            val voiceInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .pressScale(voiceInteraction)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .then(applyCardGlass(CircleShape))
                    .clickable(
                        interactionSource = voiceInteraction,
                        indication = LocalIndication.current
                    ) { onVoiceInput() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_voice_bill),
                    contentDescription = "语音记账",
                    modifier = Modifier.size(26.dp),
                    tint = Color.Unspecified
                )
            }
        }
    }
}

/**
 * 智能建议卡（体验优化 Task 3）：
 * - 高置信度（needsConfirmation=false）：点卡片「一键记入」，保留一步落库通路；
 * - 需确认（needsConfirmation=true）：点卡片只回填表单（检查后记入），核对后手动点「确认」；
 * - 模糊金额：明确展示原始区间与系统采用的中值，并提供「改金额」入口，不把中值当精确金额；
 * - 固定（📌）后 5 秒自动消失不再生效；✕ 关闭后可从「重新显示」入口找回。
 */
@Composable
private fun AiSuggestionCard(
    suggestion: QuickAddState.SuggestionData,
    pinned: Boolean,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onEditAmount: () -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .then(applyCardGlass(shape))
            .clickable(onClick = onApply)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = suggestion.label,
                fontSize = 14.sp,
                color = onContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 固定：可见入口（不依赖长按猜测手势）；固定后 5 秒自动消失不再生效
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onTogglePin),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📌",
                    fontSize = 13.sp,
                    color = if (pinned) onContainer else onContainer.copy(alpha = 0.45f)
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text("✕", fontSize = 14.sp, color = onContainer)
            }
        }
        // 依据行：优先展示服务端解释，否则按推荐来源生成简短依据
        val basis = suggestion.explanation
            ?: suggestion.categorySource?.let { source ->
                "依据：" + when (source) {
                    SuggestionSource.HISTORY -> "历史记账"
                    SuggestionSource.RULE -> "规则匹配"
                    SuggestionSource.LLM -> "AI 归类"
                }
            }
        basis?.let {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = it,
                fontSize = 12.sp,
                color = onContainer.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 模糊金额行：原始区间 + 系统采用的中值；「改金额」直达金额输入，不以中值冒充精确金额
        val rangeMin = suggestion.amountMinMinor
        val rangeMax = suggestion.amountMaxMinor
        if (suggestion.fuzzyAmount && rangeMin != null && rangeMax != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "金额 ${Money.toYuanInputString(rangeMin)}~" +
                        "${Money.toYuanInputString(rangeMax)} 元" +
                        " · 按 ${Money.format(suggestion.amount)} 记",
                    fontSize = 12.sp,
                    color = onContainer.copy(alpha = 0.78f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "改金额",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = onContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onEditAmount)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        // 动作提示：一键记入 vs 检查后记入，让确认层语义可见
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = when {
                suggestion.needsConfirmation && suggestion.filled -> "已填入 · 核对后点「确认」记入"
                suggestion.needsConfirmation -> "点击填入表单 · 核对后再记入"
                else -> "点击一键记入"
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = onContainer
        )
    }
}

/** 建议卡被关闭/自动收起后的「重新显示」入口（体验优化 Task 3：可发现性，不靠猜测手势）。 */
@Composable
private fun SuggestionReshowChip(onShow: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .then(applyCardGlass(shape))
            .clickable(onClick = onShow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "记账建议已收起",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "重新显示",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SuggestionSection(
    label: String,
    onUse: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .then(applyCardGlass(shape))
            .clickable { onUse() }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun TemplatesSection(
    templates: List<BillTemplate>,
    onTemplateClick: (BillTemplate) -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(applyCardGlass(shape))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("快捷模板", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text("一键记账", fontSize = 12.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(8.dp))
        templates.forEach { template ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTemplateClick(template) }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(template.label, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    Money.format(template.amountMinor),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun CategorySection(
    categories: List<Category>,
    selectedCategory: Category?,
    showSubCategories: Boolean,
    expandedParentId: Long?,
    subCategories: List<SubCategory>,
    selectedSubCategory: SubCategory?,
    parentIdsWithSubs: Set<Long>,
    viewModel: QuickAddViewModel
) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 280.dp)
            .clip(shape)
            .then(applyCardGlass(shape))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("标签", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text("长按或点「更多」呼出二级标签", fontSize = 12.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(4.dp))
        RinklDivider(endInset = 6.dp)
        Spacer(modifier = Modifier.height(4.dp))

        // 当前展开的母标签：显式记录于状态（不再从已加载列表反推），关闭时按 showSubCategories 归零
        val visibleParentId = if (showSubCategories) expandedParentId else null
        val listState = rememberLazyListState()
        LaunchedEffect(selectedCategory?.id, categories.size) {
            val selectedIndex = categories.indexOfFirst { it.id == selectedCategory?.id }
            if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(categories, key = { it.id }, contentType = { "category_quick_add" }) { category ->
                Column {
                    CategoryRow(
                        category = category,
                        isSelected = selectedCategory?.id == category.id,
                        hasSubCategories = category.id in parentIdsWithSubs,
                        onClick = { viewModel.onEvent(QuickAddEvent.SelectCategory(category)) },
                        onLongPress = { viewModel.onEvent(QuickAddEvent.LongPressCategory(category)) }
                    )
                    // 二级分类紧跟母标签正下方展示，左缘与标签文字对齐。
                    // AnimatedVisibility 始终在组合中（不能靠 if 守卫，否则进入组合即 visible=true，
                    // 不会触发 enter 动画），由 visible 的 false→true 翻转驱动展开动画。
                    AnimatedVisibility(
                        visible = visibleParentId == category.id && subCategories.isNotEmpty(),
                        enter = expandVertically(animationSpec = Motion.Expand) + fadeIn(Motion.Fade),
                        exit = shrinkVertically(animationSpec = Motion.Expand) + fadeOut(Motion.Fade)
                    ) {
                        // 二级内容淡替：以二级分类列表为 key 交叉淡化（令牌 Motion.Fade）。
                        // 切换母标签时 VM 会先清空 subCategories 再异步加载新列表——旧内容
                        // 按切换瞬间的快照冻结，收起时旧二级淡出而非瞬空；新父级列表就绪后
                        // 展开时直接呈现自己的内容，不再出现「内容瞬换」的生硬感。
                        AnimatedContent(
                            targetState = subCategories,
                            transitionSpec = { fadeIn(Motion.Fade) togetherWith fadeOut(Motion.Fade) },
                            label = "二级标签淡替"
                        ) { subs ->
                            SubCategoryPopup(
                                subCategories = subs,
                                selected = selectedSubCategory,
                                onSelect = { viewModel.onEvent(QuickAddEvent.SelectSubCategory(it)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryRow(
    category: Category,
    isSelected: Boolean,
    hasSubCategories: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                painter = painterResource(categoryIconRes(category.name)),
                contentDescription = category.name,
                modifier = Modifier.size(24.dp),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                category.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 有二级分类的行尾显式入口（体验审查 P1：长按是隐藏手势，点「更多」等效呼出二级标签）
            if (hasSubCategories) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    "更多",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "展开二级标签", role = Role.Button, onClick = onLongPress)
                        .padding(horizontal = 6.dp, vertical = 10.dp)
                )
            }
        }
        // Radio dot
        // 圆点填充随选中交叉淡化（令牌 Motion.SelectColor）；描边兜底逻辑保持不变
        val dotColor by animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            animationSpec = Motion.SelectColor,
            label = "分类选中圆点"
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
                .then(
                    if (!isSelected) Modifier.border(1.5.dp, LocalRinklColors.current.borderColor.takeIf { it.alpha > 0f } ?: DefaultCardBorder, CircleShape)
                    else Modifier
                )
        )
    }
}

@Composable
private fun SubCategoryPopup(
    subCategories: List<SubCategory>,
    selected: SubCategory?,
    onSelect: (SubCategory?) -> Unit
) {
    Column(
        modifier = Modifier
            // 32.dp 缩进：与母标签行内图标(24dp)+间距(8dp)对齐，弹层左缘正对标签文字
            .padding(start = 32.dp, top = 2.dp, end = 10.dp)
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        subCategories.forEach { sub ->
            val isSelected = selected?.id == sub.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(sub) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sub.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // 与父分类/账户行同款单选圆点；未选中给空心圆点做右锚，保证可选中观感
                // 填充随选中交叉淡化（令牌 Motion.SelectColor）
                val dotColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    animationSpec = Motion.SelectColor,
                    label = "二级标签选中圆点"
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .then(
                            if (!isSelected) Modifier.border(1.5.dp, LocalRinklColors.current.borderColor.takeIf { it.alpha > 0f } ?: DefaultCardBorder, CircleShape)
                            else Modifier
                        )
                )
            }
        }
    }
}

@Composable
private fun AccountSection(
    accounts: List<Account>,
    selectedAccount: Account?,
    hidden: Boolean,
    viewModel: QuickAddViewModel
) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(applyCardGlass(shape))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("账户选择", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { BalancePrivacy.toggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(if (hidden) R.drawable.ic_eye_show else R.drawable.ic_eye_hide),
                    contentDescription = if (hidden) "显示余额" else "隐藏余额",
                    modifier = Modifier.size(16.dp),
                    tint = Color.Unspecified
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        RinklDivider(endInset = 6.dp)
        Spacer(modifier = Modifier.height(4.dp))
        accounts.forEach { account ->
            AccountRow(
                account = account,
                isSelected = selectedAccount?.id == account.id,
                hidden = hidden,
                onClick = { viewModel.onEvent(QuickAddEvent.SelectAccount(account)) }
            )
        }
    }
}

@SuppressLint("DefaultLocale")
@Composable
private fun AccountRow(account: Account, isSelected: Boolean, hidden: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountIcon(
                iconKey = account.iconKey,
                colorHex = account.iconColor,
                size = 30.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(account.name, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (hidden) "***" else Money.format(account.balanceMinor), fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.width(8.dp))
            // 圆点填充随选中交叉淡化（令牌 Motion.SelectColor）；描边兜底逻辑保持不变
            val dotColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                animationSpec = Motion.SelectColor,
                label = "账户选中圆点"
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .then(
                        if (!isSelected) Modifier.border(1.5.dp, LocalRinklColors.current.borderColor.takeIf { it.alpha > 0f } ?: DefaultCardBorder, CircleShape)
                        else Modifier
                    )
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CountBefore(
    state: QuickAddState,
    onAmountTap: () -> Unit,
    onToggleType: () -> Unit
) {
    val isExpense = state.billType == BillType.EXPENSE
    val haptics = rememberPressHaptics()
    // 金额框是抽屉里最大的可点元素之一：加按压缩放反馈（复用点击手势的 InteractionSource）
    val amountInteraction = remember { MutableInteractionSource() }
    // 一次性提示（体验审查 P1）：长按切换收支属于隐藏手势，首次展示抽屉时用反馈条提示一次。
    // 会话内只出现一次（rememberSaveable，配置变更不重弹）；跨会话持久化留待后续。
    // 控制器在组合期捕获（CompositionLocal.current 只能在组合上下文读取）。
    val feedbackHost = LocalRinklFeedback.current
    var typeHintShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!typeHintShown) {
            typeHintShown = true
            feedbackHost.show(BillFeedback.Plain("点「收/支」或长按金额可切换收支方向"))
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ){
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(36.dp)
                .pressScale(amountInteraction)
                .clip(RoundedCornerShape(15.dp))
                .then(applyCardGlass(RoundedCornerShape(15.dp)))
                .combinedClickable(
                    interactionSource = amountInteraction,
                    indication = null,
                    onClick = onAmountTap,
                    onLongClick = {
                        haptics.longPress()
                        onToggleType()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            // 固定宽度 + 居中：金额位数增减时文本占位不变，数字不会横向跳动。
            // 140dp 在 20sp Bold 下约容纳 9 位（"123456.78"），超出会被截断——
            // 快捷记账金额上限远低于此，够用。
            Text(
                text = state.amount.ifEmpty { "0.00" },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                // 收入色走主题令牌（第 10 槽）：未自定义时 rinklColorsOf 已按明暗给默认（亮 IncomeGreen/暗 DarkIncomeGreen）。
                color = if (isExpense) MaterialTheme.colorScheme.tertiary else LocalRinklColors.current.incomeColor,
                modifier = Modifier.width(140.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        // 显式收支切换（体验审查 P1）：点击与长按等效，长按保留为快捷方式。
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TypeToggleChip(
                label = "支出",
                selected = isExpense,
                selectedColor = MaterialTheme.colorScheme.tertiary,
                onSelect = { if (!isExpense) onToggleType() }
            )
            Spacer(modifier = Modifier.width(6.dp))
            TypeToggleChip(
                label = "收入",
                selected = !isExpense,
                selectedColor = LocalRinklColors.current.incomeColor,
                onSelect = { if (isExpense) onToggleType() }
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text("点击输入金额", fontSize = 12.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 收/支切换小 chip：选中态按语义色填充（支出=tertiary、收入=收入色令牌），未选灰底。 */
@Composable
private fun TypeToggleChip(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    onSelect: () -> Unit
) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) selectedColor.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
            )
            .clickable(onClickLabel = if (label == "支出") "切换为支出" else "切换为收入", role = Role.Button, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * 小票 OCR chip（快捷记账）：点开拍照 / 相册选图的二级识别流（见 [ReceiptOcrDialog]）。
 * 识别全程端上（ML Kit 中文模型），图片不上传；候选点选经事件回填抽屉状态。
 */
@Composable
private fun OcrChipRow(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val rinkl = LocalRinklColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            // mergeDescendants：图标为装饰（旁有文字），读屏合并为一条「拍小票识别」
            .semantics(mergeDescendants = true) {}
            .clickable(onClickLabel = "拍小票识别", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_camera),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = rinkl.iconButtonColor ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "拍小票",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 位置打点 chip（快捷记账）：默认「位置」不采集；点一下采集当前位置一次，
 * 成功显示「已定位 · 可再点取消」（再点取消），失败按原因给「去设置 / 重试」结构化反馈。
 * 采集动作在 UI 层执行（VM 不持有 Context），经纬度经事件回填 VM 状态，
 * 键盘「确认」落库时才带上。
 */
@Composable
private fun LocationChipRow(
    location: QuickAddState.LocationTag?,
    onResolved: (Double, Double) -> Unit,
    onCleared: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    val rinkl = LocalRinklColors.current
    val located = location != null
    // 控制器在组合期捕获（CompositionLocal.current 只能在组合上下文读取）。
    val feedbackHost = LocalRinklFeedback.current

    // 定位流程（体验审查 P1）：失败按原因给可行动反馈——永久拒绝/服务关「去设置」，
    // 可重试的给「重试」（重试动作重新执行本流程），不再用一次性 Toast。
    fun locate() {
        if (locating) return
        locating = true
        scope.launch {
            val feedback: BillFeedback = when (val result = LocationGrabber.grabWithReason(context)) {
                is LocationGrabber.GrabResult.Coords -> {
                    locating = false
                    onResolved(result.latitude, result.longitude)
                    return@launch
                }
                is LocationGrabber.GrabResult.Failure -> when (result.reason) {
                    LocationGrabber.LocationFailureReason.PERMISSION_PERMANENTLY_DENIED ->
                        BillFeedback.Actionable("定位权限已被拒绝，请在系统设置中开启", "去设置")
                    LocationGrabber.LocationFailureReason.LOCATION_SERVICE_OFF ->
                        BillFeedback.Actionable("系统定位服务未开启，请在设置中开启", "去设置")
                    LocationGrabber.LocationFailureReason.PERMISSION_DENIED ->
                        BillFeedback.Actionable("未授予定位权限，无法获取当前位置", "重试")
                    LocationGrabber.LocationFailureReason.TIMEOUT_OR_UNAVAILABLE ->
                        BillFeedback.Actionable("定位超时，请稍后重试", "重试")
                }
            }
            locating = false
            feedbackHost.show(feedback) {
                if (feedback.actionLabel == "重试") locate() else openAppSettings(context)
            }
        }
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (located) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                }
            )
            .then(if (located) Modifier else applyCardGlass(RoundedCornerShape(15.dp)))
            // mergeDescendants：读屏把图标+文字合并为一条「记录当前位置」语义
            .semantics(mergeDescendants = true) {}
            .clickable(onClickLabel = if (located) "清除位置" else "记录当前位置") {
                if (located) {
                    onCleared()
                } else {
                    locate()
                }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            // 已定位 → 主题色令牌（PRIMARY 槽）；未定位 → 图标令牌（ICON 槽，未自定义回落灰）
            tint = if (located) rinkl.themeColor else rinkl.iconButtonColor ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = when {
                locating -> "定位中…"
                located -> "已定位 · 可再点取消"
                else -> "位置"
            },
            fontSize = 12.sp,
            color = if (located) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

