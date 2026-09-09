package com.example.rinklnote.ui.screen.bookkeeping

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.rinklnote.ui.component.rinkShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.component.RemarkInputSheet

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

/** 编辑账单：全屏单页，风格对齐快捷记账抽屉。一级分类为行，点击展开其下方二级分类；二级分类单个圆点可选。
 *  两张独立卡（分类 / 账户）+ 底部数字键盘。切换收支类型时重置分类并收起二级。 */
@Composable
fun BillEditOverlay(
    bill: Bill,
    expenseCategories: List<Category>,
    incomeCategories: List<Category>,
    accounts: List<Account>,
    onCancel: () -> Unit,
    onConfirm: (Bill) -> Unit,
    onLoadSubCategories: suspend (Long) -> List<SubCategory>
) {
    BackHandler { onCancel() }

    val initialCategories = if (bill.billType == BillType.EXPENSE) expenseCategories else incomeCategories

    var amount by remember(bill.id) { mutableStateOf(bill.amount.toBigDecimal().stripTrailingZeros().toPlainString()) }
    var billType by remember(bill.id) { mutableStateOf(bill.billType.value) }
    var selectedCategory by remember(bill.id) {
        mutableStateOf(initialCategories.firstOrNull { it.id == bill.categoryId } ?: initialCategories.firstOrNull())
    }
    var selectedAccount by remember(bill.id) {
        mutableStateOf(accounts.firstOrNull { it.id == bill.accountId } ?: accounts.firstOrNull())
    }
    var remark by remember(bill.id) { mutableStateOf(bill.remark ?: "") }
    var showRemark by remember { mutableStateOf(false) }

    // 二级分类：当前展开的一级分类 id、其下的列表、用户选中的二级分类。
    // 编辑已有带二级分类的账单时，预展开其所属一级分类，并在加载后按名字预选中对应二级。
    var expandedCategoryId by remember(bill.id) { mutableStateOf(if (bill.subCategoryName != null) bill.categoryId else null) }
    var subCategories by remember(bill.id) { mutableStateOf(emptyList<SubCategory>()) }
    var selectedSubCategory by remember(bill.id) { mutableStateOf<SubCategory?>(null) }
    var pendingSubName by remember(bill.id) { mutableStateOf(bill.subCategoryName) }

    LaunchedEffect(expandedCategoryId) {
        val id = expandedCategoryId
        if (id != null) {
            val subs = onLoadSubCategories(id)
            subCategories = subs
            pendingSubName?.let { name ->
                selectedSubCategory = subs.find { it.name == name }
                pendingSubName = null
            }
        } else {
            subCategories = emptyList()
        }
    }

    val visibleCategories = if (billType == BillType.EXPENSE.value) expenseCategories else incomeCategories

    fun updateType(newType: String) {
        billType = newType
        selectedCategory = (if (newType == "EXPENSE") expenseCategories else incomeCategories).firstOrNull()
        expandedCategoryId = null
        subCategories = emptyList()
        selectedSubCategory = null
    }

    fun onCategoryClick(cat: Category) {
        selectedCategory = cat
        selectedSubCategory = null
        expandedCategoryId = if (expandedCategoryId == cat.id) null else cat.id
    }

    fun confirmEdit() {
        val amountVal = amount.toDoubleOrNull() ?: return
        val cat = selectedCategory ?: return
        val acct = selectedAccount ?: return
        onConfirm(
            bill.copy(
                amount = amountVal,
                billType = BillType.fromValue(billType),
                categoryId = cat.id,
                categoryName = cat.name,
                subCategoryName = selectedSubCategory?.name,
                accountId = acct.id,
                remark = remark.ifBlank { null }
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
        ) {
            // Title bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("编辑账单", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = onCancel) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Scrollable pickers
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                CategoryCard(
                    categories = visibleCategories,
                    selectedCategory = selectedCategory,
                    expandedCategoryId = expandedCategoryId,
                    subCategories = subCategories,
                    selectedSubCategory = selectedSubCategory,
                    onCategoryClick = ::onCategoryClick,
                    onSubCategoryClick = { selectedSubCategory = it }
                )
                Spacer(modifier = Modifier.height(16.dp))
                AccountCard(
                    accounts = accounts,
                    selectedAccount = selectedAccount,
                    onAccountClick = { selectedAccount = it }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Numeric keypad (amount / type toggle / remark / confirm)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                    .padding(top = 12.dp, bottom = 16.dp)
            ) {
                NumericKeypad(
                    amount = amount,
                    billType = billType,
                    remark = remark,
                    onDigit = { digit ->
                        val newAmount = if (digit == "." && amount.contains(".")) amount else amount + digit
                        amount = newAmount
                    },
                    onClear = { amount = "" },
                    onBackspace = { amount = amount.dropLast(1) },
                    onToggleType = { updateType(if (billType == BillType.EXPENSE.value) "INCOME" else "EXPENSE") },
                    onRemarkClick = { showRemark = true },
                    onConfirm = ::confirmEdit
                )
            }
        }

        if (showRemark) {
            RemarkInputSheet(
                initialText = remark,
                onConfirm = { text ->
                    showRemark = false
                    remark = text
                },
                onDismiss = { showRemark = false }
            )
        }
    }
}

/** 分类卡：一级分类为行（图标+名+圆点），点击选中并展开其下方二级分类（32dp 缩进、圆点单选）。 */
@Composable
private fun CategoryCard(
    categories: List<Category>,
    selectedCategory: Category?,
    expandedCategoryId: Long?,
    subCategories: List<SubCategory>,
    selectedSubCategory: SubCategory?,
    onCategoryClick: (Category) -> Unit,
    onSubCategoryClick: (SubCategory) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("分类", fontSize = 20.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            Text("点击展开二级标签", fontSize = 10.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(8.dp))
        categories.forEach { category ->
            SelectRow(
                icon = { Icon(categoryIconRes(category.name), category.name, size = 24.dp) },
                label = category.name,
                isSelected = selectedCategory?.id == category.id,
                onClick = { onCategoryClick(category) }
            )
            AnimatedVisibility(
                visible = expandedCategoryId == category.id && subCategories.isNotEmpty(),
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
                ) + fadeIn(tween(200)),
                exit = shrinkVertically(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
                ) + fadeOut(tween(150))
            ) {
                Column(modifier = Modifier.padding(start = 32.dp, top = 2.dp, end = 10.dp).fillMaxWidth().padding(vertical = 4.dp)) {
                    subCategories.forEach { sub ->
                        SubCategoryRow(
                            name = sub.name,
                            isSelected = selectedSubCategory?.id == sub.id,
                            onClick = { onSubCategoryClick(sub) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubCategoryRow(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
        SelectDot(isSelected)
    }
}

/** 账户卡：图标+名+余额+圆点。 */
@Composable
private fun AccountCard(
    accounts: List<Account>,
    selectedAccount: Account?,
    onAccountClick: (Account) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("账户", fontSize = 20.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(modifier = Modifier.height(8.dp))
        accounts.forEach { account ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAccountClick(account) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(accountIconRes(account.name), account.name, size = 23.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(account.name, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format("%.2f", account.balance), fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.width(8.dp))
                    SelectDot(selectedAccount?.id == account.id)
                }
            }
        }
    }
}

/** 一级分类行：图标 + 名 + 单选圆点。 */
@Composable
private fun SelectRow(
    icon: @Composable () -> Unit,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
        }
        SelectDot(isSelected)
    }
}

@Composable
private fun SelectDot(isSelected: Boolean) {
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

@Composable
private fun Icon(res: Int, desc: String, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.material3.Icon(
        painter = painterResource(res),
        contentDescription = desc,
        modifier = Modifier.size(size),
        tint = Color.Unspecified
    )
}
