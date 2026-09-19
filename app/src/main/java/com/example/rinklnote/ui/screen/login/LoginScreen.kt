package com.example.rinklnote.ui.screen.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.example.rinklnote.ui.component.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.ui.component.PasswordBox
import com.example.rinklnote.ui.component.pressScale
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthMode
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import com.example.rinklnote.util.PasswordRules

/** 大陆手机号：1 开头、第二位 3-9、共 11 位数字。 */
private val PhonePattern = Regex("^1[3-9]\\d{9}$")

/** 标准邮箱（仅客户端提示用，服务端有兜底校验）：本地段@域名.顶级域。 */
private val EmailPattern = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

@Composable
fun LoginPage(
    viewModel: AuthViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    // —— UI 层校验（R3-A4）：点击登录/注册（或键盘 Done）时判断，不通过则不派发 Login/Register 事件，
    //    避免拿明显非法的手机号/邮箱/密码去打服务端。字段级错误在重新输入时即清除。
    var identityError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var showForgotDialog by remember { mutableStateOf(false) }

    // —— 进场动效：表单整体「低阻尼 spring 位移 + 淡入」（无第三方库，走 Compose spring）
    val enterProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enterProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
        )
    }

    // —— 错误抖动（shake）：校验失败 / 提交出错时触发一次横向 keyframes 抖动
    val shakeProgress = remember { Animatable(0f) }
    var shakeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(shakeTick) {
        if (shakeTick > 0) {
            shakeProgress.snapTo(0f)
            shakeProgress.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = 320
                    0f at 0
                    -1f at 60
                    1f at 120
                    -0.7f at 180
                    0.7f at 240
                    0f at 320
                }
            )
        }
    }
    // 服务端返回错误时也抖一下（提交后 error 非空即视为一次失败反馈）
    LaunchedEffect(state.error) {
        if (state.error != null) shakeTick++
    }

    // 提交前统一校验：按身份模式校验第一输入行（手机号 11 位大陆号段 / 邮箱格式）；
    // 密码登录仅要求非空（与 AuthViewModel 一致），注册走 [PasswordRules]（≥6 位 + 大小写，
    // 与服务端 PasswordPolicy 同构）。返回 true 表示校验通过、可以派发事件。
    fun validateAndMarkErrors(isRegister: Boolean): Boolean {
        val identityOk = when (state.authMode) {
            AuthMode.PHONE -> PhonePattern.matches(state.phone)
            AuthMode.EMAIL -> EmailPattern.matches(state.email.trim())
        }
        identityError = when {
            identityOk -> null
            state.authMode == AuthMode.EMAIL -> "请输入正确的邮箱地址"
            else -> "请输入正确的 11 位手机号"
        }
        passwordError = when {
            state.password.isBlank() -> "密码不能为空"
            isRegister -> PasswordRules.validate(state.password)
            else -> null
        }
        val ok = identityOk && passwordError == null
        if (!ok) shakeTick++
        return ok
    }

    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onDismiss()
    }
    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 输入法弹出时整列上移，避免盖住底部按钮；收起时 imePadding 为 0，布局不变。
        // enableEdgeToEdge 下系统不会自动避让（manifest 的 adjustResize 已失效），必须显式让位。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            // 顶栏：返回 + 标题（statusBarsPadding 避让状态栏，勿删）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "登录/注册",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 居中表单（区块节奏：4/8/12/16，页面级大间隔 32/48 不在此列）
            // 进场动效：alpha 淡入 + 轻微下移复位（低阻尼 spring，见 enterProgress）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .graphicsLayer {
                        alpha = enterProgress.value
                        translationY = (1f - enterProgress.value) * 48f
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                // 品牌区：主标题 28sp Bold primary + 副标语 14sp
                Text(
                    text = "RinklNote",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "登录后即可云端同步账单",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                // 「手机号 | 邮箱」同位切换：滑块随模式平移（spring），与 Web 登录页同构
                IdentitySwitch(
                    mode = state.authMode,
                    onChange = {
                        identityError = null
                        viewModel.onEvent(AuthEvent.AuthModeChanged(it))
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 输入区：错误抖动作用在这一组（shakeProgress 0→1 的 keyframes 横向摆动）
                Column(
                    modifier = Modifier.graphicsLayer {
                        translationX = shakeProgress.value * 10.dp.toPx()
                    }
                ) {
                    // 第一输入行：随身份模式切换 手机号 / 邮箱
                    if (state.authMode == AuthMode.PHONE) {
                        OutlinedTextField(
                            value = state.phone,
                            onValueChange = {
                                identityError = null // 重新输入即清除本字段错误
                                viewModel.onEvent(AuthEvent.PhoneChanged(it))
                            },
                            label = { Text("手机号") },
                            singleLine = true,
                            isError = identityError != null,
                            supportingText = {
                                if (identityError != null) {
                                    Text(
                                        text = identityError!!,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 12.sp
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                // 键盘「下一项」：焦点落到密码框
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = state.email,
                            onValueChange = {
                                identityError = null
                                viewModel.onEvent(AuthEvent.EmailChanged(it))
                            },
                            label = { Text("邮箱") },
                            placeholder = { Text("name@example.com") },
                            singleLine = true,
                            isError = identityError != null,
                            supportingText = {
                                if (identityError != null) {
                                    Text(
                                        text = identityError!!,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 12.sp
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // 自定义密码框（2026-09-17）：圆点弹入 + 手绘眼睛开合 + 实时强度
                    PasswordBox(
                        value = state.password,
                        onValueChange = {
                            passwordError = null // 重新输入即清除本字段错误
                            viewModel.onEvent(AuthEvent.PasswordChanged(it))
                        },
                        label = "密码",
                        showStrength = true,
                        isError = passwordError != null,
                        errorMessage = passwordError,
                        onImeDone = {
                            focusManager.clearFocus()
                            // 键盘「完成」视同点「登录」：先收起键盘并校验，通过才派发
                            if (validateAndMarkErrors(isRegister = false)) {
                                viewModel.onEvent(AuthEvent.Login)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                // 忘记密码入口（右对齐小字按钮）——当前仅占位弹窗，服务端暂无对应端点。
                // TODO 忘记密码接口：服务端补 POST /auth/forgot-password 后接入（AuthViewModel 加 AuthEvent.ForgotPassword）
                TextButton(
                    onClick = { showForgotDialog = true },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = "忘记密码？",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (state.error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 大按钮按压反馈：按下缩放 0.97（pressScale），用同一个 InteractionSource 驱动
                    val loginInteraction = remember { MutableInteractionSource() }
                    val registerInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            if (validateAndMarkErrors(isRegister = false)) {
                                viewModel.onEvent(AuthEvent.Login)
                            }
                        },
                        enabled = !state.isLoading,
                        interactionSource = loginInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .pressScale(loginInteraction)
                    ) { Text("登录") }
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            if (validateAndMarkErrors(isRegister = true)) {
                                viewModel.onEvent(AuthEvent.Register)
                            }
                        },
                        enabled = !state.isLoading,
                        interactionSource = registerInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .pressScale(registerInteraction)
                    ) { Text("注册") }
                }
                if (state.isLoading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (showForgotDialog) {
        // TODO 忘记密码接口：服务端补 POST /auth/forgot-password 后接入（AuthViewModel 加 AuthEvent.ForgotPassword）
        AlertDialog(
            onDismissRequest = { showForgotDialog = false },
            title = { Text("忘记密码") },
            text = { Text("暂未开放自助找回，请联系管理员重置") },
            confirmButton = {
                TextButton(onClick = { showForgotDialog = false }) { Text("确定") }
            }
        )
    }
}

/**
 * 「手机号 | 邮箱」同位分段切换（与 Web 登录页 .toggle 同构）：
 * 滑块（主题色槽）随模式 spring 平移，选中字色走 onPrimary。
 */
@Composable
private fun IdentitySwitch(mode: AuthMode, onChange: (AuthMode) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val segmentWidth = maxWidth / 2
        val thumbX by animateDpAsState(
            targetValue = if (mode == AuthMode.EMAIL) segmentWidth else 0.dp,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            label = "identityThumb"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            // 滑块：四周留 4dp 呼吸，宽度 = 半宽 - 8dp
            Box(
                modifier = Modifier
                    .offset(x = thumbX + 4.dp, y = 4.dp)
                    .width(segmentWidth - 8.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(LocalRinklColors.current.themeColor)
            )
            Row(modifier = Modifier.fillMaxSize()) {
                IdentityTab(
                    text = "手机号",
                    selected = mode == AuthMode.PHONE,
                    onClick = { onChange(AuthMode.PHONE) },
                    modifier = Modifier.weight(1f)
                )
                IdentityTab(
                    text = "邮箱",
                    selected = mode == AuthMode.EMAIL,
                    onClick = { onChange(AuthMode.EMAIL) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 分段切换的单个选项：文字居中、整格可点（TalkBack 选中态由 selected 语义体现）。 */
@Composable
private fun IdentityTab(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clickable(onClickLabel = text, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
