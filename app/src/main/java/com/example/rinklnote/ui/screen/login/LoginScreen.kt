package com.example.rinklnote.ui.screen.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.ui.component.AlertDialog
import com.example.rinklnote.ui.component.PasswordBox
import com.example.rinklnote.ui.component.RinklButton as Button
import com.example.rinklnote.ui.component.RinklIconButton as IconButton
import com.example.rinklnote.ui.component.RinklLinearProgressIndicator as LinearProgressIndicator
import com.example.rinklnote.ui.component.RinklTextButton as TextButton
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.theme.RinklNoteTheme
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthMode
import com.example.rinklnote.ui.viewmodel.AuthState
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import com.example.rinklnote.util.PasswordRules

/** 大陆手机号：1 开头、第二位 3-9、共 11 位数字。 */
private val PhonePattern = Regex("^1[3-9]\\d{9}$")

/** 邮箱格式仅用于客户端提示，服务端仍负责最终校验。 */
private val EmailPattern = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

@Composable
fun LoginPage(viewModel: AuthViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onDismiss()
    }
    BackHandler { onDismiss() }
    LoginForm(state = state, onEvent = viewModel::onEvent, onDismiss = onDismiss)
}

/** 登录与注册共用表单，主操作随当前场景切换；界面状态在旋转后保留。 */
@Composable
internal fun LoginForm(
    state: AuthState,
    onEvent: (AuthEvent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var isRegister by rememberSaveable { mutableStateOf(false) }
    var identityError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var showForgotDialog by remember { mutableStateOf(false) }
    val enterProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enterProgress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
    }
    val shakeProgress = remember { Animatable(0f) }
    var shakeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(shakeTick) {
        if (shakeTick > 0) {
            shakeProgress.snapTo(0f)
            shakeProgress.animateTo(0f, keyframes {
                durationMillis = 320
                0f at 0
                -1f at 60
                1f at 120
                -0.7f at 180
                0.7f at 240
                0f at 320
            })
        }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) shakeTick++
    }

    fun submit() {
        // 键盘完成与按钮共用校验，加载期间不重复提交。
        if (state.isLoading) return
        focusManager.clearFocus()
        identityError = when {
            state.authMode == AuthMode.PHONE && !PhonePattern.matches(state.phone) -> "请输入正确的 11 位手机号"
            state.authMode == AuthMode.EMAIL && !EmailPattern.matches(state.email.trim()) -> "请输入正确的邮箱地址"
            else -> null
        }
        passwordError = when {
            state.password.isBlank() -> "密码不能为空"
            isRegister -> PasswordRules.validate(state.password)
            else -> null
        }
        if (identityError != null || passwordError != null) {
            shakeTick++
            return
        }
        onEvent(if (isRegister) AuthEvent.Register else AuthEvent.Login)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        RinklTopBar(scrimAlpha = 0f, horizontalPadding = 8.dp) {
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = LocalRinklColors.current.topBarTitleColor)
            }
            Text(
                text = if (isRegister) "注册账号" else "登录账号",
                style = MaterialTheme.typography.titleMedium,
                color = LocalRinklColors.current.topBarTitleColor,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // 宽屏限制表单宽度，小屏与键盘弹出时允许滚动，避免按钮被遮挡。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(top = 28.dp, bottom = 24.dp)
                    .graphicsLayer {
                        alpha = enterProgress.value.coerceIn(0f, 1f)
                        translationY = (1f - enterProgress.value) * 16.dp.toPx()
                    }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_brand), contentDescription = "记一笔标识",
                        tint = LocalRinklColors.current.themeColor, modifier = Modifier.size(32.dp)
                    )
                    Text("记一笔", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    if (isRegister) "开启你的记账日常" else "欢迎回来",
                    fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "登录后，让每一笔账单在设备间同步。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(32.dp))
                IdentitySwitch(
                    mode = state.authMode,
                    enabled = !state.isLoading,
                    onChange = {
                        focusManager.clearFocus()
                        identityError = null
                        onEvent(AuthEvent.AuthModeChanged(it))
                    }
                )
                Spacer(Modifier.height(24.dp))
                Column(Modifier.graphicsLayer { translationX = shakeProgress.value * 8.dp.toPx() }) {
                    val phoneMode = state.authMode == AuthMode.PHONE
                    IdentityField(
                        label = if (phoneMode) "手机号" else "邮箱",
                        value = if (phoneMode) state.phone else state.email,
                        onValueChange = {
                            identityError = null
                            onEvent(if (phoneMode) AuthEvent.PhoneChanged(it) else AuthEvent.EmailChanged(it))
                        },
                        placeholder = if (phoneMode) "请输入 11 位手机号" else "请输入邮箱地址",
                        enabled = !state.isLoading,
                        errorText = identityError,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (phoneMode) KeyboardType.Phone else KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )
                    Spacer(Modifier.height(20.dp))
                    PasswordBox(
                        value = state.password,
                        onValueChange = {
                            passwordError = null
                            onEvent(AuthEvent.PasswordChanged(it))
                        },
                        label = if (isRegister) "设置密码" else "密码",
                        placeholder = if (isRegister) "至少 6 位，包含大小写字母" else "请输入密码",
                        showStrength = isRegister,
                        isError = passwordError != null,
                        errorMessage = passwordError,
                        onImeDone = ::submit
                    )
                    if (isRegister) {
                        Spacer(Modifier.height(8.dp))
                        Text("至少 6 位，且同时包含大写和小写字母", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
                if (!isRegister) {
                    TextButton(
                        onClick = { showForgotDialog = true }, enabled = !state.isLoading,
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) { Text("忘记密码？", style = MaterialTheme.typography.bodyMedium) }
                } else {
                    Spacer(Modifier.height(24.dp))
                }
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = ::submit,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.large,
                    // 浅蓝按钮采用深色文字，保持品牌色与可读性。
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    elevation = null
                ) {
                    Text(
                        if (state.isLoading) { if (isRegister) "正在注册…" else "正在登录…" }
                        else if (isRegister) "注册并登录" else "登录",
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                if (state.isLoading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "正在提交，请稍候" })
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        focusManager.clearFocus()
                        isRegister = !isRegister
                        identityError = null
                        passwordError = null
                        onEvent(AuthEvent.ClearError)
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Text(if (isRegister) "已有账号？  去登录" else "还没有账号？  去注册", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(32.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) { Text("暂不登录，继续记账", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    if (showForgotDialog) {
        // 服务端暂无自助找回端点，保留现有说明入口。
        AlertDialog(
            onDismissRequest = { showForgotDialog = false },
            title = { Text("忘记密码") },
            text = { Text("暂未开放自助找回，请联系管理员重置") },
            confirmButton = { TextButton(onClick = { showForgotDialog = false }) { Text("确定") } }
        )
    }
}

/** 灰底输入区与密码框统一，标签及错误提示支持无障碍读取。 */
@Composable
private fun IdentityField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    errorText: String?,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions
) {
    var focused by remember { mutableStateOf(false) }
    val rinkl = LocalRinklColors.current
    val borderColor by animateColorAsState(
        when {
            errorText != null -> MaterialTheme.colorScheme.error
            focused -> rinkl.themeColor
            else -> Color.Transparent
        }, animationSpec = Motion.SelectColor, label = "身份输入描边"
    )
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, color = rinkl.fontColor),
            cursorBrush = SolidColor(rinkl.themeColor),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .border(1.dp, borderColor, MaterialTheme.shapes.medium)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            }
        )
        errorText?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
    }
}

/** 48dp 分段选项保留滑块动画，选中态同时通过字重与无障碍语义表达。 */
@Composable
private fun IdentitySwitch(mode: AuthMode, enabled: Boolean, onChange: (AuthMode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val segmentWidth = maxWidth / 2
        val thumbX by animateDpAsState(
            if (mode == AuthMode.EMAIL) segmentWidth else 0.dp,
            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "身份切换滑块"
        )
        Box(Modifier.fillMaxWidth().height(48.dp).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
            Box(Modifier.offset(x = thumbX + 4.dp, y = 4.dp).width(segmentWidth - 8.dp).height(40.dp)
                .clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer))
            Row(Modifier.fillMaxSize().selectableGroup()) {
                IdentityTab("手机号", mode == AuthMode.PHONE, enabled, { onChange(AuthMode.PHONE) }, Modifier.weight(1f))
                IdentityTab("邮箱", mode == AuthMode.EMAIL, enabled, { onChange(AuthMode.EMAIL) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IdentityTab(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "手机号登录", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun LoginPreview() {
    RinklNoteTheme { LoginForm(AuthState(), {}, {}) }
}

@Preview(name = "邮箱登录·深色", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun LoginDarkPreview() {
    RinklNoteTheme(darkTheme = true) { LoginForm(AuthState(authMode = AuthMode.EMAIL), {}, {}) }
}
