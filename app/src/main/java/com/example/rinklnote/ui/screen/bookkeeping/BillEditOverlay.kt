package com.example.rinklnote.ui.screen.bookkeeping

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import com.example.rinklnote.ui.component.AlertDialog
import com.example.rinklnote.ui.component.RinklDatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.component.AccountIcon
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.util.categoryIconRes
import com.example.rinklnote.util.LocationGrabber
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val editDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINESE)

/**
 * 编辑账单：与快捷记账共享视觉和输入方式，但用完整页面承载更多字段。
 *
 * 页面结构：顶部操作栏 → 一级标签横向滚动 → 二级标签流式布局 → 日期 / 账户 →
 * 底部内联备注数字键盘。自定义背景直接透传，卡片只在有背景时保持透明描边。
 */
@Composable
fun BillEditOverlay(
    bill: Bill,
    expenseCategories: List<Category>,
    incomeCategories: List<Category>,
    accounts: List<Account>,
    backgroundUri: String?,
    hazeState: HazeState?,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: (Bill) -> Unit,
    onLoadSubCategories: suspend (Long) -> List<SubCategory>
) {
    val hasCustomBackground = backgroundUri != null
    val initialCategories = if (bill.billType == BillType.EXPENSE) expenseCategories else incomeCategories
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var amount by remember(bill.id) { mutableStateOf(Money.toYuanInputString(bill.amountMinor)) }
    var billType by remember(bill.id) { mutableStateOf(bill.billType.value) }
    var selectedCategory by remember(bill.id) {
        mutableStateOf(initialCategories.firstOrNull { it.id == bill.categoryId } ?: initialCategories.firstOrNull())
    }
    var selectedAccount by remember(bill.id) {
        mutableStateOf(accounts.firstOrNull { it.id == bill.accountId } ?: accounts.firstOrNull())
    }
    var remark by remember(bill.id) { mutableStateOf(bill.remark.orEmpty()) }
    var selectedDate by remember(bill.id) { mutableStateOf(bill.date.toBillLocalDate()) }
    var subCategories by remember(bill.id) { mutableStateOf(emptyList<SubCategory>()) }
    var selectedSubCategory by remember(bill.id) { mutableStateOf<SubCategory?>(null) }
    var pendingSubName by remember(bill.id) { mutableStateOf(bill.subCategoryName) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    // 位置打点：编辑既有账单可补打/清除；初值来自账单本身（保存时按当前值全量覆盖）
    var location by remember(bill.id) { mutableStateOf(bill.toLocationTag()) }
    var locating by remember { mutableStateOf(false) }

    val visibleCategories = if (billType == BillType.EXPENSE.value) expenseCategories else incomeCategories

    LaunchedEffect(visibleCategories, bill.categoryId) {
        if (selectedCategory == null || visibleCategories.none { it.id == selectedCategory?.id }) {
            selectedCategory = visibleCategories.firstOrNull { it.id == bill.categoryId }
                ?: visibleCategories.firstOrNull()
        }
    }

    LaunchedEffect(accounts, bill.accountId) {
        if (selectedAccount == null || accounts.none { it.id == selectedAccount?.id }) {
            selectedAccount = accounts.firstOrNull { it.id == bill.accountId } ?: accounts.firstOrNull()
        }
    }

    LaunchedEffect(selectedCategory?.id) {
        val categoryId = selectedCategory?.id
        if (categoryId == null) {
            subCategories = emptyList()
            selectedSubCategory = null
            return@LaunchedEffect
        }

        val loaded = onLoadSubCategories(categoryId)
        subCategories = loaded
        selectedSubCategory = pendingSubName?.let { name -> loaded.firstOrNull { it.name == name } }
        pendingSubName = null
    }

    fun updateType(newType: BillType) {
        if (newType.value == billType) return
        billType = newType.value
        val nextCategories = if (newType == BillType.EXPENSE) expenseCategories else incomeCategories
        selectedCategory = nextCategories.firstOrNull()
        selectedSubCategory = null
        pendingSubName = null
        subCategories = emptyList()
    }

    fun selectCategory(category: Category) {
        selectedCategory = category
        selectedSubCategory = null
        pendingSubName = null
    }

    /**
     * 位置 chip 点击：已定位 → 清除（可再点补回）；未定位 → 采集一次当前位置。
     * 采集在 UI 层做（本页为无 VM 的状态组件），失败 Toast 提示检查权限。
     */
    fun toggleLocation() {
        if (location != null) {
            location = null
            return
        }
        if (locating) return
        locating = true
        scope.launch {
            val got = LocationGrabber.grab(context)
            locating = false
            if (got != null) {
                location = got
            } else {
                Toast.makeText(context, "定位失败，请检查定位权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun buildEditedBill(): Bill = bill.copy(
        amountMinor = Money.parseMinor(amount) ?: bill.amountMinor,
        billType = BillType.fromValue(billType),
        categoryId = selectedCategory?.id ?: bill.categoryId,
        categoryName = selectedCategory?.name ?: bill.categoryName,
        subCategoryName = selectedSubCategory?.name,
        accountId = selectedAccount?.id ?: bill.accountId,
        remark = remark.trim().ifBlank { null },
        date = selectedDate.toBillTimestamp(),
        // 位置：chip 当前值（补打/清除都会体现为变更并置脏同步）
        latitude = location?.first,
        longitude = location?.second
    )

    val amountMinor = Money.parseMinor(amount)
    val canSave = amountMinor != null &&
        amountMinor > 0 &&
        selectedCategory != null &&
        selectedAccount != null
    val editedBill = buildEditedBill()
    val hasChanges = editedBill != bill

    fun requestCancel() {
        if (hasChanges) showDiscardConfirm = true else onCancel()
    }

    BackHandler(onBack = ::requestCancel)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            EditTopBar(
                canSave = canSave,
                onBack = ::requestCancel,
                onDelete = { showDeleteConfirm = true },
                onSave = { if (canSave) onConfirm(editedBill) }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp)
            ) {
                CategoryEditor(
                    categories = visibleCategories,
                    selectedCategory = selectedCategory,
                    subCategories = subCategories,
                    selectedSubCategory = selectedSubCategory,
                    hasCustomBackground = hasCustomBackground,
                    onCategoryClick = ::selectCategory,
                    onSubCategoryClick = { selectedSubCategory = it }
                )
                Spacer(modifier = Modifier.height(6.dp))
                DateEditor(
                    selectedDate = selectedDate,
                    hasCustomBackground = hasCustomBackground,
                    onDateClick = { showDatePicker = true },
                    onQuickDateClick = { selectedDate = it }
                )
                Spacer(modifier = Modifier.height(6.dp))
                LocationEditor(
                    location = location,
                    locating = locating,
                    hasCustomBackground = hasCustomBackground,
                    onToggle = ::toggleLocation
                )
                Spacer(modifier = Modifier.height(6.dp))
                AccountEditor(
                    accounts = accounts,
                    selectedAccount = selectedAccount,
                    hasCustomBackground = hasCustomBackground,
                    onAccountClick = { selectedAccount = it }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                NumericKeypad(
                    amount = amount,
                    billType = billType,
                    remark = remark,
                    onDigit = { digit ->
                        if (digit != "." || !amount.contains(".")) {
                            amount += digit
                        }
                    },
                    onClear = { amount = "" },
                    onBackspace = { amount = amount.dropLast(1) },
                    onToggleType = {
                        updateType(
                            if (billType == BillType.EXPENSE.value) BillType.INCOME else BillType.EXPENSE
                        )
                    },
                    onRemarkChange = { remark = it },
                    confirmEnabled = canSave,
                    onConfirm = { if (canSave) onConfirm(editedBill) },
                    hazeState = hazeState,
                    bottomPadding = 16.dp
                )
            }
        }

        if (showDatePicker) {
            RinklDatePickerDialog(
                initialDate = selectedDate,
                onConfirm = {
                    selectedDate = it
                    showDatePicker = false
                },
                onDismiss = { showDatePicker = false }
            )
        }

        if (showDeleteConfirm) {
            DeleteBillDialog(
                onConfirm = {
                    showDeleteConfirm = false
                    onDelete()
                },
                onDismiss = { showDeleteConfirm = false }
            )
        }

        if (showDiscardConfirm) {
            DiscardChangesDialog(
                onConfirm = {
                    showDiscardConfirm = false
                    onCancel()
                },
                onDismiss = { showDiscardConfirm = false }
            )
        }
    }
}

@Composable
private fun EditTopBar(
    canSave: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = "返回" }
        ) {
            Text("返回", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text(
            text = "编辑账单",
            modifier = Modifier.weight(1f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        TextButton(onClick = onDelete) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        }
        TextButton(enabled = canSave, onClick = onSave) {
            Text("保存")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(
    categories: List<Category>,
    selectedCategory: Category?,
    subCategories: List<SubCategory>,
    selectedSubCategory: SubCategory?,
    hasCustomBackground: Boolean,
    onCategoryClick: (Category) -> Unit,
    onSubCategoryClick: (SubCategory?) -> Unit
) {
    SectionCard(hasCustomBackground = hasCustomBackground) {
        SectionHeader(title = "一级标签", hint = "左右滑动")
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories, key = { it.id }) { category ->
                PrimaryCategoryChip(
                    category = category,
                    selected = selectedCategory?.id == category.id,
                    onClick = { onCategoryClick(category) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        SectionHeader(
            title = "二级标签",
            hint = selectedCategory?.name ?: "先选择一级标签"
        )
        Spacer(modifier = Modifier.height(4.dp))

        // 二级标签区以二级分类列表为 key 交叉淡替（令牌 Motion.Fade）：切换一级标签时
        // 旧内容按切换瞬间的快照淡出、新内容淡入，避免整块内容瞬间替换的生硬感
        // （与快捷记账抽屉的二级标签同款处理）。
        AnimatedContent(
            targetState = subCategories,
            transitionSpec = { fadeIn(Motion.Fade) togetherWith fadeOut(Motion.Fade) },
            label = "编辑页二级标签淡替"
        ) { subs ->
            Column {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SecondaryCategoryChip(
                        label = "不限",
                        selected = selectedSubCategory == null,
                        onClick = { onSubCategoryClick(null) }
                    )
                    subs.forEach { subCategory ->
                        SecondaryCategoryChip(
                            label = subCategory.name,
                            selected = selectedSubCategory?.id == subCategory.id,
                            onClick = { onSubCategoryClick(subCategory) }
                        )
                    }
                }

                if (subs.isEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "该标签暂无二级分类，可直接保存",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryCategoryChip(
    category: Category,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    // 选中底色交叉淡化（令牌 Motion.SelectColor），避免色块突变
    val chipColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
        animationSpec = Motion.SelectColor,
        label = "一级标签选中底色"
    )
    Row(
        modifier = Modifier
            .clip(shape)
            .background(chipColor, shape)
            .then(if (selected) Modifier else applyCardGlass(shape))
            .clickable(onClick = onClick)
            // 选中字重加粗引起微宽度变化：平滑伸缩，相邻 chip 不再瞬移
            .animateContentSize(animationSpec = Motion.ContentResize)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(categoryIconRes(category.name)),
            contentDescription = category.name,
            modifier = Modifier.size(22.dp),
            tint = Color.Unspecified
        )
        Text(
            text = category.name,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun SecondaryCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    // 选中底色交叉淡化（令牌 Motion.SelectColor），避免色块突变
    val chipColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        },
        animationSpec = Motion.SelectColor,
        label = "二级标签选中底色"
    )
    Box(
        modifier = Modifier
            .clip(shape)
            .background(chipColor, shape)
            .then(if (selected) Modifier else applyCardGlass(shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateEditor(
    selectedDate: LocalDate,
    hasCustomBackground: Boolean,
    onDateClick: () -> Unit,
    onQuickDateClick: (LocalDate) -> Unit
) {
    val today = LocalDate.now(bookkeepingZone())
    val quickDates = listOf(
        "今天" to today,
        "昨天" to today.minusDays(1),
        "前天" to today.minusDays(2)
    )

    SectionCard(hasCustomBackground = hasCustomBackground) {
        SectionHeader(title = "日期", hint = "业务时区 Asia/Shanghai")
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
                .clickable(onClick = onDateClick)
                .padding(horizontal = 11.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = selectedDate.format(editDateFormatter),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (selectedDate == today) "今天" else "点击打开日历",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("选择日期", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickDates.forEach { (label, date) ->
                SecondaryCategoryChip(
                    label = label,
                    selected = selectedDate == date,
                    onClick = { onQuickDateClick(date) }
                )
            }
        }
    }
}

@Composable
private fun LocationEditor(
    location: Pair<Double, Double>?,
    locating: Boolean,
    hasCustomBackground: Boolean,
    onToggle: () -> Unit
) {
    val rinkl = LocalRinklColors.current
    val located = location != null
    SectionCard(hasCustomBackground = hasCustomBackground) {
        SectionHeader(title = "位置", hint = "仅主动打点的账单会上账单地图")
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (located) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                    }
                )
                .then(if (located) Modifier else applyCardGlass(RoundedCornerShape(10.dp)))
                .clickable(onClick = onToggle)
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                // 已定位 → 主题色令牌（PRIMARY 槽）；未定位 → 图标令牌（ICON 槽，未自定义回落灰）
                tint = if (located) rinkl.themeColor else rinkl.iconButtonColor ?: MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(7.dp))
            Column {
                Text(
                    text = when {
                        locating -> "定位中…"
                        located -> "已定位 · 可再点取消"
                        else -> "位置"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (located) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = if (located) {
                        String.format(Locale.CHINESE, "%.5f, %.5f", location!!.first, location.second)
                    } else {
                        "点一下记录当前位置，可再次点击清除"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountEditor(
    accounts: List<Account>,
    selectedAccount: Account?,
    hasCustomBackground: Boolean,
    onAccountClick: (Account) -> Unit
) {
    SectionCard(hasCustomBackground = hasCustomBackground) {
        SectionHeader(title = "账户", hint = "选择资金账户")
        Spacer(modifier = Modifier.height(4.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            accounts.forEach { account ->
                val selected = selectedAccount?.id == account.id
                val shape = RoundedCornerShape(10.dp)
                // 选中底色交叉淡化（令牌 Motion.SelectColor），避免色块突变
                val chipColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                    },
                    animationSpec = Motion.SelectColor,
                    label = "账户选中底色"
                )
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(chipColor, shape)
                        .then(if (selected) Modifier else applyCardGlass(shape))
                        .clickable { onAccountClick(account) }
                        // 选中圆点出现/消失引起宽度跳变：以 ContentResize 平滑伸缩，
                        // FlowRow 内相邻账户的位置随动不再瞬移
                        .animateContentSize(animationSpec = Motion.ContentResize)
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccountIcon(
                        iconKey = account.iconKey,
                        colorHex = account.iconColor,
                        size = 26.dp
                    )
                    Text(
                        text = account.name,
                        fontSize = 14.sp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    hasCustomBackground: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(sectionSurface(hasCustomBackground, shape))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
private fun sectionSurface(hasCustomBackground: Boolean, shape: Shape): Modifier =
    if (hasCustomBackground) {
        applyCardGlass(shape)
    } else {
        Modifier
            .background(MaterialTheme.colorScheme.surface, shape)
            .then(applyCardGlass(shape))
    }

@Composable
private fun SectionHeader(title: String, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = hint,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeleteBillDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这笔账单？") },
        text = { Text("删除后会从本地和云端同步移除，此操作不可撤销。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DiscardChangesDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("放弃未保存的修改？") },
        text = { Text("返回后本次修改不会保存。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("放弃修改", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("继续编辑")
            }
        }
    )
}

private fun Long.toBillLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(bookkeepingZone()).toLocalDate()

/** 账单自带的经纬度 → chip 状态（任一为 null 视为未打点）。 */
private fun Bill.toLocationTag(): Pair<Double, Double>? =
    latitude?.let { lat -> longitude?.let { lng -> lat to lng } }

private fun LocalDate.toBillTimestamp(): Long =
    atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()
