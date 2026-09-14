package com.example.rinklnote.ui.screen.assets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.domain.ACCOUNT_ICON_KEYS
import com.example.rinklnote.domain.ACCOUNT_ICON_WALLET
import com.example.rinklnote.ui.component.ACCOUNT_ICON_OPTIONS
import com.example.rinklnote.ui.component.AccountIcon
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.KeypadContextItem
import com.example.rinklnote.ui.component.NumericKeypad
import com.example.rinklnote.ui.component.SettingsGroupCard
import com.example.rinklnote.ui.component.accountColor
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.pressScale
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.viewmodel.AssetsEvent
import com.example.rinklnote.ui.viewmodel.AssetsViewModel
import com.example.rinklnote.util.Money
import dev.chrisbanes.haze.HazeState

private const val NEW_ACCOUNT_ID = -1L
private val ACCOUNT_COLORS = listOf(
    "#28C145",
    "#06B4FD",
    "#F97D1D",
    "#8B5CF6",
    "#EF4444",
    "#64748B",
)

private enum class BalanceKeypadMode { CREATE, EDIT }

@Composable
fun AccountEditorScreen(
    viewModel: AssetsViewModel,
    accountId: Long,
    backgroundUri: String?,
    hazeState: HazeState,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isCreate = accountId == NEW_ACCOUNT_ID
    val account = remember(state.accounts, accountId) {
        state.accounts.firstOrNull { it.id == accountId }
    }

    var name by remember { mutableStateOf("") }
    var iconKey by remember { mutableStateOf(ACCOUNT_ICON_WALLET) }
    var iconColor by remember { mutableStateOf(ACCOUNT_COLORS.first()) }
    var balanceInput by remember { mutableStateOf("") }
    var keypadMode by remember { mutableStateOf<BalanceKeypadMode?>(null) }
    var keypadAmount by remember { mutableStateOf("") }

    fun dismissKeypad() {
        keypadMode = null
    }

    fun requestBack() {
        if (keypadMode != null) dismissKeypad() else onBack()
    }

    BackHandler(onBack = ::requestBack)

    Box(modifier = Modifier.fillMaxSize()) {
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = hazeState)
        }

        when {
            !state.loaded -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            !isCreate && account == null -> {
                AccountEditorError(onBack = onBack)
            }
            else -> {
                if (isCreate) {
                    CreateAccountContent(
                        accounts = state.accounts,
                        name = name,
                        onNameChange = { name = it },
                        iconKey = iconKey,
                        onIconKeyChange = { iconKey = it },
                        iconColor = iconColor,
                        onIconColorChange = { iconColor = it },
                        balanceInput = balanceInput,
                        onBalanceClick = {
                            keypadAmount = balanceInput
                            keypadMode = BalanceKeypadMode.CREATE
                        },
                        onBack = onBack,
                        onCreate = {
                            val balanceMinor = parseBalanceInput(balanceInput) ?: return@CreateAccountContent
                            viewModel.onEvent(
                                AssetsEvent.AddAccount(
                                    name = name.trim(),
                                    iconKey = iconKey,
                                    iconColor = iconColor,
                                    balanceMinor = balanceMinor
                                )
                            )
                            onBack()
                        }
                    )
                } else if (account != null) {
                    EditBalanceContent(
                        account = account,
                        onBack = onBack,
                        onBalanceClick = {
                            keypadAmount = Money.toYuanInputString(account.balanceMinor)
                            keypadMode = BalanceKeypadMode.EDIT
                        }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = keypadMode != null,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.SheetEnter),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.SheetExit)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = ::dismissKeypad),
                contentAlignment = Alignment.BottomCenter
            ) {
                val currentAccount = account
                val contextItems = if (currentAccount != null) {
                    listOf(
                        KeypadContextItem(
                            label = currentAccount.name,
                            iconRes = com.example.rinklnote.ui.util.accountIconRes(currentAccount),
                            iconTint = accountColor(currentAccount.iconColor)
                        )
                    )
                } else {
                    emptyList()
                }
                NumericKeypad(
                    amount = keypadAmount,
                    billType = "INCOME",
                    remark = "",
                    onDigit = { digit ->
                        keypadAmount = appendBalanceDigit(keypadAmount, digit)
                    },
                    onClear = { keypadAmount = "" },
                    onBackspace = { keypadAmount = keypadAmount.dropLast(1) },
                    onToggleType = {},
                    onConfirm = {
                        val value = parseBalanceInput(keypadAmount) ?: return@NumericKeypad
                        when (keypadMode) {
                            BalanceKeypadMode.CREATE -> {
                                balanceInput = Money.toYuanInputString(value)
                                dismissKeypad()
                            }
                            BalanceKeypadMode.EDIT -> {
                                currentAccount?.let {
                                    viewModel.onEvent(AssetsEvent.ChangeBalance(it, value))
                                    dismissKeypad()
                                    onBack()
                                }
                            }
                            null -> Unit
                        }
                    },
                    showTypeToggle = false,
                    showRemark = false,
                    confirmEnabled = validateBalanceInput(keypadAmount) == null,
                    contextItems = contextItems,
                    hazeState = hazeState.takeIf { backgroundUri != null }
                )
            }
        }
    }
}

@Composable
private fun CreateAccountContent(
    accounts: List<Account>,
    name: String,
    onNameChange: (String) -> Unit,
    iconKey: String,
    onIconKeyChange: (String) -> Unit,
    iconColor: String,
    onIconColorChange: (String) -> Unit,
    balanceInput: String,
    onBalanceClick: () -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit
) {
    val nameError = validateAccountName(name, accounts)
    val balanceError = validateBalanceInput(balanceInput)

    // 输入法弹出时整列上移：名称输入框与底部「创建账户」按钮都不被键盘遮挡。
    // 根列 imePadding 与按钮上的 navigationBarsPadding 经 insets 消费机制协调——
    // 键盘弹出时 ime 已含导航栏区域，按钮的导航栏内边距会被抵消为 0，不会双重叠加。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {
        AccountEditorTopBar(title = "新建账户", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AccountPreviewCard(
                name = name.trim().ifEmpty { "新账户" },
                iconKey = iconKey,
                iconColor = iconColor
            )

            SettingsGroupCard(title = "账户名称") {
                AccountNameField(
                    value = name,
                    onValueChange = onNameChange,
                    error = nameError,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            SettingsGroupCard(title = "账户图标") {
                IconPicker(
                    selectedKey = iconKey,
                    selectedColor = iconColor,
                    onSelect = onIconKeyChange
                )
            }

            SettingsGroupCard(title = "图标颜色") {
                ColorPicker(
                    selectedColor = iconColor,
                    onSelect = onIconColorChange
                )
            }

            SettingsGroupCard(title = "初始余额（可选）") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onBalanceClick)
                        .semantics { contentDescription = "编辑初始余额" }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "金额",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = Money.formatPlain(parseBalanceInput(balanceInput) ?: 0L),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (balanceError != null) {
                Text(
                    text = balanceError,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
        }

        // 主 CTA 的按压缩放反馈：pressScale 放在链尾，缩放只作用于按钮本体绘制
        val createInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = onCreate,
            enabled = nameError == null && balanceError == null,
            interactionSource = createInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(50.dp)
                .pressScale(createInteraction),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("创建账户", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AccountNameField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (error != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Column(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.32f), shape)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            ),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = "请输入账户名称",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            }
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = error,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun EditBalanceContent(
    account: Account,
    onBack: () -> Unit,
    onBalanceClick: () -> Unit
) {
    var displayedBalance by remember(account.id, account.balanceMinor) {
        mutableStateOf(account.balanceMinor)
    }
    LaunchedEffect(account.balanceMinor) {
        displayedBalance = account.balanceMinor
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        AccountEditorTopBar(title = "编辑余额", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AccountPreviewCard(
                name = account.name,
                iconKey = account.iconKey,
                iconColor = account.iconColor
            )

            SettingsGroupCard(title = "当前余额") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onBalanceClick)
                        .semantics { contentDescription = "编辑账户余额" }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "余额",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = Money.formatPlain(displayedBalance),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "编辑",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountPreviewCard(
    name: String,
    iconKey: String,
    iconColor: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccountIcon(iconKey = iconKey, colorHex = iconColor, size = 52.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "预览",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun IconPicker(
    selectedKey: String,
    selectedColor: String,
    onSelect: (String) -> Unit
) {
    val options = ACCOUNT_ICON_OPTIONS.filter { it.key in ACCOUNT_ICON_KEYS }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        options.chunked(4).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { option ->
                    val selected = option.key == selectedKey
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 68.dp)
                            .clickable { onSelect(option.key) }
                            .semantics {
                                contentDescription = option.label
                                this.selected = selected
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, accountColor(selectedColor), CircleShape)
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            AccountIcon(
                                iconKey = option.key,
                                colorHex = selectedColor,
                                size = 38.dp
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = option.label,
                            fontSize = 12.sp,
                            color = if (selected) {
                                accountColor(selectedColor)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorPicker(
    selectedColor: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ACCOUNT_COLORS.forEach { color ->
            val selected = color == selectedColor
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable { onSelect(color) }
                    .semantics {
                        contentDescription = "图标颜色 $color"
                        this.selected = selected
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (selected) 32.dp else 28.dp)
                        .clip(CircleShape)
                        .background(accountColor(color))
                        .then(
                            if (selected) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun AccountEditorTopBar(
    title: String,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = LocalRinklColors.current.iconButtonColor
                    ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun AccountEditorError(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        AccountEditorTopBar(title = "账户编辑", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "账户不存在或已被删除",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onBack) {
                Text("返回")
            }
        }
    }
}

/** 数字键盘输入只允许一个点，并限制两位小数。 */
private fun appendBalanceDigit(current: String, digit: String): String {
    if (digit == ".") {
        if (current.contains('.')) return current
        return if (current.isEmpty()) "0." else "$current."
    }
    val decimalDigits = current.substringAfter('.', "").length
    if (current.contains('.') && decimalDigits >= 2) return current
    if (current == "0") return digit
    return current + digit
}
