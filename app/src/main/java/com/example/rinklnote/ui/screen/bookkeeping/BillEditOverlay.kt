package com.example.rinklnote.ui.screen.bookkeeping

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocationOn
import com.example.rinklnote.ui.component.AlertDialog
import com.example.rinklnote.ui.component.RinklDatePickerDialog
import com.example.rinklnote.ui.component.RinklTopBar
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import com.example.rinklnote.ui.component.RinklIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import com.example.rinklnote.ui.component.RinklTextButton as TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.component.AccountIcon
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.component.pressScale
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.util.LocationGrabber
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val editDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINESE)

/**
 * 编辑账单：与快捷记账共享视觉和输入方式，但用完整页面承载更多字段。
 *
 * 页面结构：顶部操作栏 → 金额 → 分类选择 → 账户 / 日期 / 位置分组 → 含收支选择的固定数字键盘。
 * 金额只展示一次；备注输入时让出数字键盘空间给系统输入法。
 * 分类与字段平铺，保留自选背景透出；账户、日期、位置通过细线分组。
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

    var amount by remember(bill.id) {
        mutableStateOf(if (bill.amountMinor > 0) Money.toYuanInputString(bill.amountMinor) else "")
    }
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
    var showDiscardConfirm by remember { mutableStateOf(false) }
    // 位置打点：编辑既有账单可补打/清除；初值来自账单本身（保存时按当前值全量覆盖）
    var location by remember(bill.id) { mutableStateOf(bill.toLocationTag()) }
    var locating by remember { mutableStateOf(false) }
    var formVisible by remember(bill.id) { mutableStateOf(false) }
    LaunchedEffect(bill.id) { formVisible = true }

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
        if (selectedCategory?.id == category.id) return
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

    val topBarTextColor = LocalRinklColors.current.topBarTitleColor

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(
        alpha = if (hasCustomBackground) 0.94f else 1f
    ))) {
        Column(modifier = Modifier.fillMaxSize()) {
            RinklTopBar(
                scrimAlpha = 0f,
                horizontalPadding = 8.dp
            ) {
                IconButton(
                    onClick = ::requestCancel,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .semantics { contentDescription = "返回" }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = topBarTextColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    text = if (bill.id == 0L) "记一笔" else "编辑账单",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = topBarTextColor,
                    modifier = Modifier.align(Alignment.Center)
                )

                // 保存走底部键盘操作按钮，放弃走返回键：顶栏只保留编辑态的删除入口，
                // 左右两侧同为单图标，标题居中不再被文字按钮拉偏
                if (bill.id != 0L) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .semantics { contentDescription = "删除账单" }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "删除账单",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            BillAmountHeader(amount, BillType.fromValue(billType),
                Modifier.padding(horizontal = BillEditorDesign.PagePadding, vertical = 4.dp))

            AnimatedVisibility(
                visible = formVisible,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                enter = fadeIn(Motion.EditorFade) + slideInVertically(Motion.EditorSlide) { it / 24 }
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = BillEditorDesign.PagePadding, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(BillEditorDesign.SectionGap)
                ) {
                    BillCategoryEditor(
                        categories = visibleCategories,
                        selectedCategory = selectedCategory,
                        subCategories = subCategories,
                        selectedSubCategory = selectedSubCategory,
                        onCategoryClick = ::selectCategory,
                        onSubCategoryClick = { selectedSubCategory = it }
                    )
                    Column(Modifier.fillMaxWidth()) {
                        EditorDivider()
                        AccountEditor(
                            accounts = accounts,
                            selectedAccount = selectedAccount,
                            onAccountClick = { selectedAccount = it }
                        )
                        EditorDivider()
                        DateEditor(
                            selectedDate = selectedDate,
                            onDateClick = { showDatePicker = true }
                        )
                        EditorDivider()
                        LocationEditor(
                            location = location,
                            locating = locating,
                            onToggle = ::toggleLocation
                        )
                        EditorDivider()
                    }
                }
            }

            // 数字键盘固定在页面底部：不随上方表单滚动，随时可确认落库
            NumericKeypad(
                amount = amount,
                billType = billType,
                remark = remark,
                onDigit = { digit ->
                    if (digit == ".") {
                        if (!amount.contains(".")) {
                            amount = if (amount.isEmpty() || amount == "0") "0." else amount + "."
                        }
                    } else {
                        if (amount == "0") {
                            amount = digit
                        } else {
                            val dotIndex = amount.indexOf('.')
                            if (dotIndex == -1 || amount.length - dotIndex <= 2) {
                                amount += digit
                            }
                        }
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
                compact = true,
                editorMode = true,
                confirmLabel = if (bill.id == 0L) "记一笔" else "保存",
                bottomPadding = 8.dp,
                modifier = Modifier.navigationBarsPadding()
            )
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
private fun DateEditor(
    selectedDate: LocalDate,
    onDateClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = BillEditorDesign.TouchHeight)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onDateClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("日期", style = BillEditorDesign.SectionTitle, modifier = Modifier.width(44.dp))
        AnimatedContent(
            targetState = selectedDate,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(Motion.EditorFade) + slideInVertically(Motion.EditorSlide) { it / 3 })
                    .togetherWith(fadeOut(Motion.EditorFade) + slideOutVertically(Motion.EditorSlide) { -it / 3 })
            },
            label = "日期切换"
        ) { date ->
            Text(
                date.format(editDateFormatter), fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "修改日期",
            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LocationEditor(
    location: Pair<Double, Double>?,
    locating: Boolean,
    onToggle: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val located = location != null
    val tint by animateColorAsState(
        if (located) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        Motion.SelectColor, label = "位置状态颜色"
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = BillEditorDesign.TouchHeight)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = !locating,
                role = Role.Button, onClick = onToggle),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("位置", style = BillEditorDesign.SectionTitle, modifier = Modifier.width(44.dp))
        AnimatedContent(
            targetState = when { locating -> "正在定位…"; located -> "已记录 · 点击清除"; else -> "添加位置 · 可选" },
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(Motion.EditorFade) togetherWith fadeOut(Motion.EditorFade) },
            label = "位置状态切换"
        ) { label ->
            Text(label, fontSize = 14.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (locating) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.ic_location), contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
        }
    }
}

@Composable
private fun AccountEditor(
    accounts: List<Account>,
    selectedAccount: Account?,
    onAccountClick: (Account) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(accounts, selectedAccount?.id) {
        val index = accounts.indexOfFirst { it.id == selectedAccount?.id }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem(index)
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = BillEditorDesign.TouchHeight), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("账户", style = BillEditorDesign.SectionTitle, modifier = Modifier.width(44.dp))
        LazyRow(
            modifier = Modifier.weight(1f), state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(accounts, key = { it.id }) { account ->
                val selected = selectedAccount?.id == account.id
                val interaction = remember { MutableInteractionSource() }
                val chipColor by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                    Motion.SelectColor, label = "账户选中底色"
                )
                val dotScale by animateFloatAsState(
                    if (selected) 1f else 0f, Motion.EditorSelection, label = "账户选中标记"
                )
                Row(
                    Modifier.heightIn(min = BillEditorDesign.TouchHeight).pressScale(interaction)
                        .clip(RoundedCornerShape(BillEditorDesign.ChipRadius)).background(chipColor)
                        // 合并子节点语义，让读屏和自动化点击把整张账户卡识别成一个单选项。
                        .semantics(mergeDescendants = true) { this.selected = selected }
                        .clickable(interactionSource = interaction, indication = null, role = Role.RadioButton) { onAccountClick(account) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccountIcon(iconKey = account.iconKey, colorHex = account.iconColor, size = 28.dp)
                    Text(account.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                    // 始终保留标记占位，切换账户时相邻选项不会跳位。
                    Box(Modifier.graphicsLayer { alpha = dotScale; scaleX = dotScale; scaleY = dotScale }) {
                        EditorSelectionMark()
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorDivider() {
    HorizontalDivider(color = LocalRinklColors.current.dividerColor.copy(alpha = 0.55f))
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
