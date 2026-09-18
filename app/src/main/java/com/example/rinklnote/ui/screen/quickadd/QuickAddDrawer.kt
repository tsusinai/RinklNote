package com.example.rinklnote.ui.screen.quickadd

import java.util.Locale


import android.annotation.SuppressLint
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.ui.component.AccountIcon
import com.example.rinklnote.ui.component.RinklCardFrostedStyle
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.pressScale
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
import com.example.rinklnote.util.LocationGrabber
import com.example.rinklnote.util.Money
import com.example.rinklnote.ui.viewmodel.QuickAddEvent
import com.example.rinklnote.ui.viewmodel.QuickAddState
import com.example.rinklnote.ui.viewmodel.QuickAddViewModel
import kotlinx.coroutines.launch

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
                    .width(200.dp)
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

    // Fetch smart suggestion once when the drawer opens
    LaunchedEffect(Unit) {
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

            state.suggestion?.let { suggestion ->
                SuggestionSection(
                    label = suggestion.label,
                    onUse = { viewModel.onEvent(QuickAddEvent.SuggestionClick) },
                    onDismiss = { viewModel.onEvent(QuickAddEvent.DismissSuggestion) }
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

            // 位置打点：默认不采集，点 chip 取当前位置；再点取消（见 LocationChipRow）
            LocationChipRow(
                location = state.location,
                onResolved = { lat, lng ->
                    viewModel.onEvent(QuickAddEvent.LocationResolved(lat, lng))
                },
                onCleared = { viewModel.onEvent(QuickAddEvent.ClearLocation) }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 小票 OCR：拍照 / 相册选图 → 端上识别 → 金额/日期/商家候选 chips 点选预填
            var showOcrFlow by remember { mutableStateOf(false) }
            OcrChipRow(onClick = { showOcrFlow = true })
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
            fontSize = 13.sp,
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
            .height(240.dp)
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
            Text("长按呼出二级标签", fontSize = 12.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            items(categories, key = { it.id }) { category ->
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(categoryIconRes(category.name)),
                contentDescription = category.name,
                modifier = Modifier.size(24.dp),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(category.name, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            // 有二级分类的行尾提示标记：长按呼出二级标签
            if (hasSubCategories) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "···",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
            Icon(
                painter = painterResource(if (hidden) R.drawable.ic_eye_show else R.drawable.ic_eye_hide),
                contentDescription = if (hidden) "显示余额" else "隐藏余额",
                modifier = Modifier
                    .size(15.dp)
                    .clickable { BalancePrivacy.toggle() },
                tint = Color.Unspecified
            )
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
                .clip(RoundedCornerShape(14.dp))
                .then(applyCardGlass(RoundedCornerShape(14.dp)))
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
        Text("点击输入金额", fontSize = 12.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "长按金额切换收支",
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        )
    }
}

/**
 * 小票 OCR chip（快捷记账）：点开拍照 / 相册选图的二级识别流（见 [ReceiptOcrDialog]）。
 * 识别全程端上（ML Kit 中文模型），图片不上传；候选点选经事件回填抽屉状态。
 */
@Composable
private fun OcrChipRow(onClick: () -> Unit) {
    val rinkl = LocalRinklColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .then(applyCardGlass(RoundedCornerShape(14.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
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
            text = "拍小票记一笔",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 位置打点 chip（快捷记账）：默认「位置」不采集；点一下采集当前位置一次，
 * 成功显示「已定位 · 可再点取消」（再点取消），失败 Toast 提示检查定位权限。
 * 采集动作在 UI 层执行（VM 不持有 Context），经纬度经事件回填 VM 状态，
 * 键盘「确认」落库时才带上。
 */
@Composable
private fun LocationChipRow(
    location: QuickAddState.LocationTag?,
    onResolved: (Double, Double) -> Unit,
    onCleared: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    val rinkl = LocalRinklColors.current
    val located = location != null
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (located) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                }
            )
            .then(if (located) Modifier else applyCardGlass(RoundedCornerShape(14.dp)))
            .clickable {
                if (located) {
                    onCleared()
                } else if (!locating) {
                    locating = true
                    scope.launch {
                        val got = LocationGrabber.grab(context)
                        locating = false
                        if (got != null) {
                            onResolved(got.first, got.second)
                        } else {
                            Toast.makeText(context, "定位失败，请检查定位权限", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
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

