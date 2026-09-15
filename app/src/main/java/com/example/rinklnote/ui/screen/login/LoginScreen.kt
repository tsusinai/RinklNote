package com.example.rinklnote.ui.screen.login

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.ui.component.pressScale
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthViewModel

/** 大陆手机号：1 开头、第二位 3-9、共 11 位数字。 */
private val PhonePattern = Regex("^1[3-9]\\d{9}$")

@Composable
fun LoginPage(
    viewModel: AuthViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    // 第三方登录占位按钮的 Toast 用
    val context = LocalContext.current

    // —— UI 层校验（R3-A4）：点击登录/注册（或键盘 Done）时判断，不通过则不派发 Login/Register 事件，
    //    避免拿明显非法的手机号/密码去打服务端。字段级错误在重新输入时即清除。
    var phoneError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var showForgotDialog by remember { mutableStateOf(false) }

    // 提交前统一校验：手机号 11 位大陆号段 + 密码非空；注册额外要求 ≥6 位
    // （提示文案与 AuthViewModel.register 既有规则对齐：「密码长度至少6位」；登录仅要求非空，与 VM 一致）。
    // 返回 true 表示校验通过、可以派发事件。
    fun validateAndMarkErrors(isRegister: Boolean): Boolean {
        val phoneOk = PhonePattern.matches(state.phone)
        val passwordTooShort = isRegister && state.password.length < 6
        phoneError = if (phoneOk) null else "请输入正确的 11 位手机号"
        passwordError = when {
            state.password.isBlank() -> "密码不能为空"
            passwordTooShort -> "密码长度至少6位"
            else -> null
        }
        return phoneOk && state.password.isNotBlank() && !passwordTooShort
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
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
                Spacer(modifier = Modifier.height(32.dp))
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = {
                        phoneError = null // 重新输入即清除本字段错误
                        viewModel.onEvent(AuthEvent.PhoneChanged(it))
                    },
                    label = { Text("手机号") },
                    singleLine = true,
                    isError = phoneError != null,
                    supportingText = {
                        if (phoneError != null) {
                            Text(
                                text = phoneError!!,
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
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = {
                        passwordError = null // 重新输入即清除本字段错误
                        viewModel.onEvent(AuthEvent.PasswordChanged(it))
                    },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = passwordError != null,
                    supportingText = {
                        if (passwordError != null) {
                            Text(
                                text = passwordError!!,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        // 键盘「完成」视同点「登录」：先收起键盘并校验，通过才派发
                        onDone = {
                            focusManager.clearFocus()
                            if (validateAndMarkErrors(isRegister = false)) {
                                viewModel.onEvent(AuthEvent.Login)
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
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

                // —— 其他登录方式（占位入口，暂不可用）——
                // TODO 其他登录方式：QQ 可后续接服务端 qq-login 端点（server AuthRoutes 已有），微信需新增端点
                Spacer(modifier = Modifier.height(32.dp))
                // 分割行：中间 12sp 说明文字，左右各一段发丝线（1 物理像素 + 边框令牌色，与 RinklDivider 同规格；
                // RinklDivider 只支持 endInset 单侧留白，做不了文字两侧对称线，故用 weight Box 等效实现）
                val hairline = with(LocalDensity.current) { 1f.toDp() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(hairline)
                            .background(LocalRinklColors.current.dividerColor)
                    )
                    Text(
                        text = "其他登录方式",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(hairline)
                            .background(LocalRinklColors.current.dividerColor)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                // 圆形占位按钮：Row 不占满宽度，由外层 Column 的 CenterHorizontally 居中；
                // 品牌色为官方固定值（微信 #07C160 / QQ #12B7F5），非主题语义色，不走 RinklColors 令牌
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SocialLoginCircle(
                        label = "微信登录",
                        char = "微",
                        backgroundColor = Color(0xFF07C160),
                        onClick = {
                            Toast.makeText(context, "暂未开放，敬请期待", Toast.LENGTH_SHORT).show()
                        }
                    )
                    SocialLoginCircle(
                        label = "QQ 登录",
                        char = "Q",
                        backgroundColor = Color(0xFF12B7F5),
                        onClick = {
                            Toast.makeText(context, "暂未开放，敬请期待", Toast.LENGTH_SHORT).show()
                        }
                    )
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
 * 第三方登录圆形占位按钮：48dp 品牌色圆底 + 白色单字，点击仅弹 Toast（登录能力暂未开放）。
 * 品牌色为官方固定值，不属 App 主题语义色，故不走 RinklColors 令牌；后续接通真实登录时再替换官方图标。
 *
 * @param label 无障碍描述（TalkBack 朗读 + 点击动作标签），如「微信登录」
 * @param char 圆底上的单字，如「微」/「Q」（项目无微信/QQ 图标资源，用文字最稳）
 * @param backgroundColor 品牌底色（微信 #07C160 / QQ #12B7F5）
 * @param onClick 点击回调（当前只弹「暂未开放，敬请期待」Toast）
 */
@Composable
private fun SocialLoginCircle(
    label: String,
    char: String,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = char,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}
