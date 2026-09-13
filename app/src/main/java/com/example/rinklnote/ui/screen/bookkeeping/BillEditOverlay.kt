package com.example.rinklnote.ui.screen.bookkeeping

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import com.example.rinklnote.ui.util.categoryIconRes
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
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

    fun buildEditedBill(): Bill = bill.copy(
        amountMinor = Money.parseMinor(amount) ?: bill.amountMinor,
        billType = BillType.fromValue(billType),
        categoryId = selectedCategory?.id ?: bill.categoryId,
        categoryName = selectedCategory?.name ?: bill.categoryName,
        subCategoryName = selectedSubCategory?.name,
        accountId = selectedAccount?.id ?: bill.accountId,
        remark = remark.trim().ifBlank { null },
        date = selectedDate.toBillTimestamp()
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
            BillDatePickerDialog(
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
            subCategories.forEach { subCategory ->
                SecondaryCategoryChip(
                    label = subCategory.name,
                    selected = selectedSubCategory?.id == subCategory.id,
                    onClick = { onSubCategoryClick(subCategory) }
                )
            }
        }

        if (subCategories.isEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "该标签暂无二级分类，可直接保存",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    Row(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                shape
            )
            .then(if (selected) Modifier else applyCardGlass(shape))
            .clickable(onClick = onClick)
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
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                shape
            )
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
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                            shape
                        )
                        .then(if (selected) Modifier else applyCardGlass(shape))
                        .clickable { onAccountClick(account) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillDatePickerDialog(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.toPickerMillis()
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onConfirm(it.toPickerLocalDate()) }
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    ) {
        DatePicker(
            state = state,
            showModeToggle = false
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

private fun LocalDate.toBillTimestamp(): Long =
    atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()

private fun LocalDate.toPickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toPickerLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
