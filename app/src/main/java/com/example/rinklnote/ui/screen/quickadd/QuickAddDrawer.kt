package com.example.rinklnote.ui.screen.quickadd

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.ui.viewmodel.QuickAddEvent
import com.example.rinklnote.ui.viewmodel.QuickAddState
import com.example.rinklnote.ui.viewmodel.QuickAddViewModel

private fun categoryIconRes(name: String): Int = when (name) {
    "三餐" -> R.drawable.ic_category_meals
    "日用" -> R.drawable.ic_category_daily
    "交通" -> R.drawable.ic_category_transport
    "学习" -> R.drawable.ic_category_study
    "运动" -> R.drawable.ic_category_sports
    "娱乐" -> R.drawable.ic_category_entertainment
    "网购" -> R.drawable.ic_category_shopping
    else -> R.drawable.ic_category_meals
}

private fun accountIconRes(name: String): Int = when (name) {
    "微信" -> R.drawable.ic_wechat
    "支付宝" -> R.drawable.ic_alipay
    "默认" -> R.drawable.ic_default_account
    else -> R.drawable.ic_default_account
}

@Composable
fun QuickAddDrawer(
    isVisible: Boolean,
    viewModel: QuickAddViewModel,
    onDismiss: () -> Unit,
    onBillAdded: () -> Unit,
    onVoiceInput: () -> Unit = {},
    onAmountTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = isVisible) { onDismiss() }

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
                    .fillMaxHeight()
                    .width(236.dp)
                    .shadow(4.dp)
                    .clip(RoundedCornerShape(topStart = 15.dp, bottomStart = 15.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(enabled = false) {} // consume click
                    .pointerInput(Unit) {
                        var dragOffset = 0f
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragOffset > 60) onDismiss()
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
                    onAmountTap = onAmountTap
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
    onAmountTap: () -> Unit
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
            .padding(12.dp)
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
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("快捷记账", fontSize = 20.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
                Icon(
                    painter = painterResource(R.drawable.ic_register),
                    contentDescription = "登记",
                    modifier = Modifier.size(30.dp),
                    tint = Color.Unspecified
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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

            CategorySection(state.categories, state.selectedCategory, state.showSubCategories, state.expandedParentId, state.subCategories, state.selectedSubCategory, viewModel)

            Spacer(modifier = Modifier.height(16.dp))

            AccountSection(state.accounts, state.selectedAccount, balanceHidden, viewModel)

            Spacer(modifier = Modifier.height(17.dp))

            // Count area — single-step amount box
            CountBefore(state, onAmountTap)
        }

        // AI Voice button — 固定在抽屉底部上方，不随内容滚动，避开底部手势区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onVoiceInput() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ai),
                    contentDescription = "语音记账",
                    modifier = Modifier.size(28.dp),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("快捷模板", fontSize = 20.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            Text("一键记账", fontSize = 10.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(8.dp))
        templates.forEach { template ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTemplateClick(template) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(template.label, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "¥${template.amount.toBigDecimal().stripTrailingZeros().toPlainString()}",
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
    viewModel: QuickAddViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("标签栏", fontSize = 20.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            Text("长按呼出二级标签", fontSize = 10.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 当前展开的母标签：显式记录于状态（不再从已加载列表反推），关闭时按 showSubCategories 归零
        val visibleParentId = if (showSubCategories) expandedParentId else null
        categories.forEach { category ->
            CategoryRow(
                category = category,
                isSelected = selectedCategory?.id == category.id,
                onClick = { viewModel.onEvent(QuickAddEvent.SelectCategory(category)) },
                onLongPress = { viewModel.onEvent(QuickAddEvent.LongPressCategory(category)) }
            )
            // 二级分类紧跟母标签正下方展示，左缘与标签文字对齐。
            // AnimatedVisibility 始终在组合中（不能靠 if 守卫，否则进入组合即 visible=true，
            // 不会触发 enter 动画），由 visible 的 false→true 翻转驱动展开动画。
            AnimatedVisibility(
                visible = visibleParentId == category.id && subCategories.isNotEmpty(),
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(tween(200)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(tween(150))
            ) {
                SubCategoryPopup(
                    subCategories = subCategories,
                    selected = selectedSubCategory,
                    onSelect = { viewModel.onEvent(QuickAddEvent.SelectSubCategory(it)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryRow(
    category: Category,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 4.dp),
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
        }
        // Radio dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                .then(
                    if (!isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
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
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .then(
                            if (!isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("账户选择", fontSize = 20.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            Icon(
                painter = painterResource(if (hidden) R.drawable.ic_eye_show else R.drawable.ic_eye_hide),
                contentDescription = if (hidden) "显示余额" else "隐藏余额",
                modifier = Modifier
                    .size(15.dp)
                    .clickable { BalancePrivacy.toggle() },
                tint = Color.Unspecified
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
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

@Composable
private fun AccountRow(account: Account, isSelected: Boolean, hidden: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(accountIconRes(account.name)),
                contentDescription = account.name,
                modifier = Modifier.size(23.dp),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(account.name, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (hidden) "***" else String.format("%.2f", account.balance), fontSize = 20.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .then(
                        if (!isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        else Modifier
                    )
            )
        }
    }
}

@Composable
private fun CountBefore(state: QuickAddState, onAmountTap: () -> Unit) {
    val isExpense = state.billType == "EXPENSE"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(36.dp)
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onAmountTap() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = state.amount.ifEmpty { "0.00" },
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            color = if (isExpense) MaterialTheme.colorScheme.tertiary else IncomeGreen
        )
    }
}

