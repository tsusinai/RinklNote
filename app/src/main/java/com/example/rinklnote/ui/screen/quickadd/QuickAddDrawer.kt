package com.example.rinklnote.ui.screen.quickadd

import java.util.Locale
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.rotate
import com.example.rinklnote.ui.component.accountIconKeyRes
import com.example.rinklnote.ui.component.accountColor
import com.example.rinklnote.ui.component.MotionContent
import com.example.rinklnote.ui.theme.BillWheelColors
import kotlinx.coroutines.Job
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt


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
import com.example.rinklnote.ui.component.motionClickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
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
import com.example.rinklnote.ui.theme.BackgroundLight
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.theme.billCategoryIconColor
import androidx.compose.ui.graphics.luminance
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.ui.util.categoryIconRes
import com.example.rinklnote.ui.util.subcategoryIconRes
import com.example.rinklnote.ui.util.rememberPressHaptics
import com.example.rinklnote.util.Money
import com.example.rinklnote.ui.viewmodel.QuickAddEvent
import com.example.rinklnote.ui.viewmodel.QuickAddState
import com.example.rinklnote.ui.viewmodel.QuickAddViewModel
import com.example.rinklnote.ui.viewmodel.SuggestionSource
import kotlinx.coroutines.launch

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
    onCategoryInput: (Category) -> Unit = {},
    backgroundUri: String? = null,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = isVisible) { onDismiss() }
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(Motion.Reveal) + slideInHorizontally(Motion.DrawerEnter) { it / 4 },
        exit = fadeOut(Motion.Dismiss) + slideOutHorizontally(Motion.DrawerExit) { it / 4 },
        modifier = modifier
    ) {
        // 空白处只接收关闭动作；没有整块底板、模糊层、标题或遮罩。
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClickLabel = "关闭快捷记账", onClick = onDismiss
        )) {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { viewModel.onEvent(QuickAddEvent.DrawerOpened) }
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val wheelWidth = minOf(maxWidth * 0.72f, 270.dp)
                val wheelHeight = minOf(maxHeight * 0.82f, wheelWidth * 2f)
                Box(Modifier.align(Alignment.CenterEnd).width(wheelWidth).height(wheelHeight)) {
                    ArcCategoryWheel(
                        categories = state.categories,
                        selectedCategory = state.selectedCategory,
                        accounts = state.accounts,
                        selectedAccount = state.selectedAccount,
                        onCategoryClick = onCategoryInput,
                        onAccountClick = { viewModel.onEvent(QuickAddEvent.SelectAccount(it)) },
                        onToggleType = { viewModel.onEvent(QuickAddEvent.ToggleType) },
                        billType = state.billType
                    )
                }
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
            .motionClickable(onClick = onApply)
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
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(onContainer.copy(alpha = if (pinned) 0.14f else 0.06f))
                    .motionClickable(onClickLabel = if (pinned) "取消固定建议" else "固定建议", onClick = onTogglePin),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "固",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (pinned) onContainer else onContainer.copy(alpha = 0.52f)
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .motionClickable(onClickLabel = "关闭建议", onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text("×", fontSize = 18.sp, color = onContainer.copy(alpha = 0.78f))
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
                        .motionClickable(onClick = onEditAmount)
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
            .motionClickable(onClick = onShow)
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
            .motionClickable { onUse() }
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
                .motionClickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** 透明半圆轮盘：手指按圆心角实时拖动，松手后吸附到分类位置。 */
@Composable
private fun ArcCategoryWheel(
    categories: List<Category>,
    selectedCategory: Category?,
    accounts: List<Account>,
    selectedAccount: Account?,
    onCategoryClick: (Category) -> Unit,
    onAccountClick: (Account) -> Unit,
    onToggleType: () -> Unit,
    billType: BillType
) {
    val ordered = remember(categories) {
        val names = listOf("三餐", "日用", "交通", "网购", "学习", "运动", "娱乐")
        categories.sortedBy { names.indexOf(it.name).let { index -> if (index < 0) Int.MAX_VALUE else index } }
    }
    val count = minOf(ordered.size, 7)
    val spacing = if (count > 1) 150f / (count - 1) else 25f
    val rotation = remember(ordered) { Animatable(0f) }
    var dragRotation by remember(ordered) { mutableFloatStateOf(0f) }
    var dragging by remember(ordered) { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rinkl = LocalRinklColors.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackColor = rinkl.themeColor.copy(alpha = 0.26f)
    fun currentRotation() = if (dragging) dragRotation else rotation.value
    fun angleFor(index: Int) = if (count == 1) 180f else 255f - index * spacing + currentRotation()
    // 只在标签跨越半圆边缘时更新节点；连续位移留在布局与绘制阶段，避免逐帧重组文字。
    val entries by remember(ordered, spacing) {
        derivedStateOf {
            if (count == 0) emptyList() else if (count == 1) listOf(0) else {
                val first = floor(currentRotation() / spacing).toInt()
                (first - 1..first + count).filter { angleFor(it) in 120f..240f }
            }
        }
    }
    fun category(index: Int) = ordered[Math.floorMod(index, ordered.size)]
    fun edgeAlpha(angle: Float) = minOf((angle - 120f) / 10f, (240f - angle) / 10f).coerceIn(0f, 1f)
    fun settle() {
        if (!dragging) return
        val released = dragRotation
        settleJob = scope.launch {
            rotation.snapTo(released)
            dragging = false
            rotation.animateTo((released / spacing).roundToInt() * spacing, Motion.SliderSettle)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // 只露出轮盘左侧弧段；账户另行贴屏幕右缘，避免随圆心一起移出屏幕。
        val center = Offset(widthPx + with(density) { 72.dp.toPx() }, heightPx / 2f)
        val radius = minOf(widthPx - with(density) { 14.dp.toPx() },
            heightPx / 2f - with(density) { 24.dp.toPx() })
        val labelRadius = radius - with(density) { 38.dp.toPx() }
        Box(Modifier.fillMaxSize().pointerInput(ordered, center) {
            detectDragGestures(
                onDragStart = {
                    settleJob?.cancel()
                    dragRotation = rotation.value
                    dragging = true
                },
                onDrag = { change, movement ->
                    if (count > 1) {
                        val before = change.position - movement - center
                        val after = change.position - center
                        val degrees = ((atan2(after.y, after.x) - atan2(before.y, before.x)) * 180f / PI).toFloat()
                        val delta = when { degrees > 180f -> degrees - 360f; degrees < -180f -> degrees + 360f; else -> degrees }
                        dragRotation += delta
                    }
                    change.consume()
                },
                onDragEnd = { settle() },
                onDragCancel = { settle() }
            )
        }) {
            Canvas(Modifier.matchParentSize()) {
                val outer = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
                entries.forEach { index ->
                    val angle = angleFor(index)
                    val cat = category(index)
                    if (cat.id == selectedCategory?.id) {
                        val innerRadius = 74.dp.toPx()
                        val inner = Rect(center.x - innerRadius, center.y - innerRadius,
                            center.x + innerRadius, center.y + innerRadius)
                        val sector = Path().apply {
                            arcTo(outer, angle - 12f, 24f, true)
                            arcTo(inner, angle + 12f, -24f, false)
                            close()
                        }
                        drawPath(sector, rinkl.billCategoryIconColor(cat.name, dark).copy(alpha = 0.08f * edgeAlpha(angle)))
                    }
                }
                drawArc(trackColor, 105f, 150f, false, outer.topLeft,
                    androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
                entries.forEach { index ->
                    val angle = angleFor(index)
                    val radians = angle * PI / 180f
                    val point = center + Offset(radius * cos(radians).toFloat(), radius * sin(radians).toFloat())
                    val tangent = Offset(-sin(radians).toFloat(), cos(radians).toFloat()) * 8.dp.toPx()
                    drawLine(rinkl.billCategoryIconColor(category(index).name, dark).copy(alpha = 0.7f * edgeAlpha(angle)),
                        point - tangent, point + tangent, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            entries.forEach { index ->
                key(index) {
                    val cat = category(index)
                    val enabled by remember(index, ordered) { derivedStateOf { edgeAlpha(angleFor(index)) > 0.2f } }
                    ArcCategoryOption(cat, selectedCategory?.id == cat.id,
                        modifier = Modifier.offset {
                            val radians = angleFor(index) * PI / 180f
                            val x = center.x + labelRadius * cos(radians).toFloat()
                            val y = center.y + labelRadius * sin(radians).toFloat()
                            IntOffset((x - 32.dp.toPx()).roundToInt(), (y - 32.dp.toPx()).roundToInt())
                        }.size(64.dp).graphicsLayer { alpha = edgeAlpha(angleFor(index)) },
                        enabled = enabled,
                        onClick = { onCategoryClick(cat) })
                }
            }
            AccountWheelHub(accounts, selectedAccount,
                Modifier.align(Alignment.CenterEnd).offset(x = 50.dp), onAccountClick, onToggleType, billType)
        }
    }
}

/** 标签仅使用白色圆底；图标和文字不随轮盘旋转，保持易读与可点击。 */
@Composable
private fun ArcCategoryOption(category: Category, selected: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val rinkl = LocalRinklColors.current
    val iconTint = rinkl.billCategoryIconColor(category.name, false)
    val font = rinkl.fontColor.takeIf { it.luminance() < 0.6f } ?: BillWheelColors.LabelContent
    Column(modifier.clip(CircleShape).background(BillWheelColors.LabelBackground)
        .border(1.dp, if (selected) rinkl.themeColor.copy(alpha = 0.65f)
            else rinkl.borderColor.takeIf { it.alpha > 0f } ?: BillWheelColors.LabelBorder, CircleShape)
        .semantics(mergeDescendants = true) { this.selected = selected }
        .motionClickable(enabled = enabled, role = Role.RadioButton,
            onClickLabel = "选择${category.name}", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(categoryIconRes(category.name)), contentDescription = null,
            modifier = Modifier.size(24.dp), tint = iconTint)
        Text(category.name, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = font, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AccountWheelHub(
    accounts: List<Account>, selectedAccount: Account?, modifier: Modifier,
    onAccountClick: (Account) -> Unit, onToggleType: () -> Unit, billType: BillType
) {
    val account = selectedAccount ?: accounts.firstOrNull()
    var dragDistance by remember { mutableStateOf(0f) }
    Box(modifier.size(128.dp).clip(CircleShape).background(BillWheelColors.LabelBackground)
        .border(1.dp, LocalRinklColors.current.borderColor.takeIf { it.alpha > 0f }
            ?: BillWheelColors.LabelBorder, CircleShape)
        .motionClickable(onClickLabel = if (billType == BillType.EXPENSE) "切换为收入" else "切换为支出", onClick = onToggleType)
        .pointerInput(accounts, account?.id) {
            detectVerticalDragGestures(
                onDragStart = { dragDistance = 0f },
                onVerticalDrag = { change, amount -> change.consume(); dragDistance += amount },
                onDragEnd = {
                    val current = accounts.indexOfFirst { it.id == account?.id }.coerceAtLeast(0)
                    val next = if (dragDistance < -24.dp.toPx()) current + 1 else if (dragDistance > 24.dp.toPx()) current - 1 else current
                    accounts.getOrNull(next.coerceIn(0, accounts.lastIndex.coerceAtLeast(0)))?.let(onAccountClick)
                    dragDistance = 0f
                }, onDragCancel = { dragDistance = 0f }
            )
        }, contentAlignment = Alignment.Center) {
        Column(Modifier.offset(x = (-20).dp).width(76.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MotionContent(account) { shown ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(accountIconKeyRes(if (shown?.name == "无账户") "WALLET" else shown?.iconKey ?: "WALLET")),
                        contentDescription = null, modifier = Modifier.size(27.dp),
                        tint = if (shown?.name == "无账户" || shown == null) {
                            if (billType == BillType.EXPENSE) LocalRinklColors.current.expenseColor else LocalRinklColors.current.incomeColor
                        } else accountColor(shown.iconColor))
                    Text(shown?.name ?: "无账户", fontSize = 13.sp, color = BillWheelColors.LabelContent,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    modifier = Modifier.size(16.dp).rotate(-90f), tint = BillWheelColors.LabelContent.copy(alpha = 0.5f))
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    modifier = Modifier.size(16.dp).rotate(90f), tint = BillWheelColors.LabelContent.copy(alpha = 0.5f))
            }
        }
    }
}

/** 参考图顶部金额：金额始终可见，点击后进入精确编辑键盘。 */
@Composable
private fun SidebarAmountHeader(state: QuickAddState, onClick: () -> Unit) {
    val isExpense = state.billType == BillType.EXPENSE
    val amountColor = if (isExpense) MaterialTheme.colorScheme.tertiary else LocalRinklColors.current.incomeColor
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .motionClickable(onClickLabel = "输入金额", onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text("¥", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = amountColor)
        Spacer(Modifier.width(4.dp))
        Text(
            state.amount.ifEmpty { "0.00" },
            fontSize = 24.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold,
            color = amountColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    RinklDivider(endInset = 0.dp)
}

/** 参考图的母分类网格：默认只露出常用项，「全部」展开完整分类集合。 */
@Composable
private fun CompactCategorySection(
    categories: List<Category>,
    selectedCategory: Category?,
    showSubCategories: Boolean,
    subCategories: List<SubCategory>,
    selectedSubCategory: SubCategory?,
    parentIdsWithSubs: Set<Long>,
    viewModel: QuickAddViewModel
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selectedCategory?.id) {
        selectedCategory?.let { viewModel.onEvent(QuickAddEvent.LongPressCategory(it)) }
    }
    val displayCategories = remember(categories, selectedCategory?.id, showAll) {
        if (showAll) categories else buildList {
            selectedCategory?.let(::add)
            categories.filter { it.id != selectedCategory?.id }.take(3).forEach(::add)
        }.distinctBy { it.id }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("分类", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (showAll) "收起 ‹" else "全部 ›",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.motionClickable(onClickLabel = if (showAll) "收起分类" else "查看全部分类") { showAll = !showAll }
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp, max = if (showAll) 220.dp else 92.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(displayCategories, key = { it.id }) { category ->
                CompactCategoryChip(category, selectedCategory?.id == category.id) {
                    if (selectedCategory?.id != category.id) viewModel.onEvent(QuickAddEvent.SelectCategory(category))
                }
            }
        }
        if (showSubCategories && selectedCategory != null && subCategories.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${selectedCategory.name}细分",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(3.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    CompactSubcategoryChip(selectedCategory.name, null, selectedSubCategory == null) {
                        viewModel.onEvent(QuickAddEvent.SelectSubCategory(null))
                    }
                }
                items(subCategories, key = { it.id }) { sub ->
                    CompactSubcategoryChip(
                        categoryName = selectedCategory.name,
                        subCategory = sub,
                        selected = selectedSubCategory?.id == sub.id,
                        onClick = { viewModel.onEvent(QuickAddEvent.SelectSubCategory(sub)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactCategoryChip(category: Category, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f) else Color.Transparent, shape)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .clickable(role = Role.RadioButton, onClickLabel = "选择${category.name}", onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(painterResource(categoryIconRes(category.name)), contentDescription = null, modifier = Modifier.size(20.dp), tint = LocalRinklColors.current.billCategoryIconColor(category.name, MaterialTheme.colorScheme.background.luminance() < 0.5f))
        Text(category.name, fontSize = 13.sp, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CompactSubcategoryChip(categoryName: String, subCategory: SubCategory?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
            .semantics(mergeDescendants = true) { this.selected = selected }
            .clickable(role = Role.RadioButton, onClickLabel = "选择${subCategory?.name ?: "不细分"}", onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(painterResource(subcategoryIconRes(categoryName, subCategory?.name)), contentDescription = null, modifier = Modifier.size(16.dp), tint = LocalRinklColors.current.billCategoryIconColor(categoryName, MaterialTheme.colorScheme.background.luminance() < 0.5f))
        Text(subCategory?.name ?: "不细分", fontSize = 12.sp, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 账户只保留一行摘要，点击账户行仍进入原有账户选择逻辑。 */
@Composable
private fun CompactAccountSection(accounts: List<Account>, selectedAccount: Account?, viewModel: QuickAddViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = selectedAccount ?: accounts.firstOrNull()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().motionClickable(onClickLabel = "选择账户") { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(R.drawable.ic_wallet), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("账户", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(selected?.name ?: "无账户", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(3.dp))
            Text(if (expanded) "‹" else "›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Spacer(Modifier.height(5.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(accounts, key = { it.id }) { account ->
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (account.id == selected?.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)).motionClickable(onClickLabel = "选择${account.name}") { viewModel.onEvent(QuickAddEvent.SelectAccount(account)); expanded = false }.padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AccountIcon(iconKey = account.iconKey, colorHex = account.iconColor, size = 18.dp)
                        Text(account.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactTemplatesSection(templates: List<BillTemplate>, onTemplateClick: (BillTemplate) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(R.drawable.ic_sidebar_template), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("快捷模板", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(5.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            items(templates, key = { it.id }) { template ->
                Text(
                    "${template.label} ${Money.format(template.amountMinor)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).motionClickable(onClickLabel = "使用${template.label}模板") { onTemplateClick(template) }.padding(horizontal = 7.dp, vertical = 6.dp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SidebarBottomAction(state: QuickAddState, onAmountTap: () -> Unit, onToggleType: () -> Unit) {
    val isExpense = state.billType == BillType.EXPENSE
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TypeToggleChip("支出", isExpense, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f)) { if (!isExpense) onToggleType() }
            TypeToggleChip("收入", !isExpense, LocalRinklColors.current.incomeColor, Modifier.weight(1f)) { if (isExpense) onToggleType() }
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).motionClickable(onClickLabel = "输入金额") { onAmountTap() }.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(R.drawable.ic_amount_entry), contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(4.dp))
            Text(
            if (state.amount.isBlank()) "点击输入金额" else "继续编辑 ¥${state.amount}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center
            )
        }
    }
}

/** 收/支切换小 chip：选中态按语义色填充（支出=tertiary、收入=收入色令牌），未选灰底。 */
@Composable
private fun TypeToggleChip(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit
) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
            )
            .motionClickable(onClickLabel = if (label == "支出") "切换为支出" else "切换为收入", role = Role.Button, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        textAlign = TextAlign.Center
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
            .motionClickable(onClickLabel = "拍小票识别", onClick = onClick)
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
            .motionClickable(onClickLabel = if (located) "清除位置" else "记录当前位置") {
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

