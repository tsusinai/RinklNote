package com.example.rinklnote.ui.screen.profile

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.rinklnote.R
import com.example.rinklnote.data.network.RetrofitClient
import com.example.rinklnote.domain.AchievementState
import com.example.rinklnote.domain.MAX_SHOWCASE_BADGES
import com.example.rinklnote.ui.component.achievementBadgeName
import com.example.rinklnote.ui.component.achievementBadgeRes
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.SettingsGroupCard
import com.example.rinklnote.ui.component.SettingsRow
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.viewmodel.AiTokenViewModel
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import com.example.rinklnote.ui.viewmodel.BotChannel
import com.example.rinklnote.util.Constellation
import dev.chrisbanes.haze.HazeState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 个人资料页（personal-profile 路由）—— 全量同步的账号中心：
 * 形象资料（头像 / 昵称 / 签名 / 生日→星座）+ 徽章展示管理 + 账号事务（绑定 / 改密码 / AI 开关）。
 *
 * 入口 = 「我的」tab 个人卡片整卡点击（2026-09-17 改版：绑定 / 改密码 / AI 开关自「我的」迁入本页）。
 * 资料保存即 PUT /api/auth/profile（整体替换），成功后经 [PersonalProfileEffect.ProfileSynced]
 * 触发 AuthEvent.FetchProfile 刷新「我的」卡片。
 *
 * @param viewModel 资料页 VM（AppNavigation 作用域，存活跨裁剪路由跳转）
 * @param authViewModel 登录态 VM（消费 ProfileSynced 副作用 + 账号事务区开关/弹窗）
 * @param onPickAvatar 相册选图回调：nav 层据此跳 background-crop?mode=avatar（1:1 圆形取景）
 */
@Composable
fun PersonalProfileScreen(
    viewModel: PersonalProfileViewModel,
    authViewModel: AuthViewModel,
    aiTokenViewModel: AiTokenViewModel,
    backgroundUri: String?,
    hazeState: HazeState,
    onBack: () -> Unit,
    onLoginClick: () -> Unit,
    onBindBotClick: () -> Unit,
    onQqBotGuideClick: () -> Unit,
    onPickAvatar: (Uri) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 一次性副作用：资料/头像写回服务端成功 → 刷新「我的」卡片（AuthEvent.FetchProfile）。
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is PersonalProfileEffect.ProfileSynced) {
                Toast.makeText(context, "已同步到云端", Toast.LENGTH_SHORT).show()
                authViewModel.onEvent(AuthEvent.FetchProfile)
            }
        }
    }

    // 相册选头像：选完交给 nav 层进裁剪路由（圆形蒙版 1:1），确认后回调 AvatarCropConfirmed。
    val pickAvatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPickAvatar(uri) }

    // 弹窗状态机：同时至多一个。
    var dialog by remember { mutableStateOf<PersonalProfileDialog?>(null) }
    val dismissDialog: () -> Unit = { dialog = null }

    val unlocked = state.achievements.filter { it.unlocked }

    Box(modifier = Modifier.fillMaxSize()) {
        // 毛玻璃源：有自选照片时由 nav 层整窗铺满；无照片时本页自铺纯白。
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = hazeState)
        }

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "top-inset") { Spacer(modifier = Modifier.height(64.dp)) }

            item(key = "avatar") {
                AvatarCard(
                    state = state,
                    onClick = {
                        pickAvatarLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
            }

            if (!state.isLoggedIn) {
                item(key = "offline-hint") {
                    OfflineHintCard(onLoginClick = onLoginClick)
                }
            }

            item(key = "profile-fields") {
                SettingsGroupCard(title = "形象资料") {
                    SettingsRow(
                        icon = R.drawable.ic_register,
                        label = "昵称",
                        value = state.nickname ?: "未设置",
                        onClick = { dialog = PersonalProfileDialog.Nickname }
                    )
                    RinklDivider()
                    SettingsRow(
                        icon = R.drawable.ic_edit,
                        label = "个性签名",
                        value = state.signature ?: "未设置",
                        onClick = { dialog = PersonalProfileDialog.Signature }
                    )
                    RinklDivider()
                    SettingsRow(
                        icon = R.drawable.ic_cake,
                        label = "生日",
                        value = birthdayValueLabel(state.birthday),
                        onClick = { dialog = PersonalProfileDialog.Birthday }
                    )
                    if (state.saving) {
                        Text(
                            text = "保存中…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 52.dp, top = 2.dp, bottom = 10.dp)
                        )
                    }
                }
            }

            item(key = "badges") {
                ShowcaseBadgesCard(
                    unlocked = unlocked,
                    selected = state.showcaseBadges,
                    onToggle = { viewModel.onEvent(PersonalProfileEvent.ToggleBadge(it)) }
                )
            }

            if (state.isLoggedIn) {
                item(key = "account") {
                    SettingsGroupCard(title = "账号事务") {
                        SettingsRow(
                            icon = R.drawable.ic_ai,
                            label = "AI 主动推送",
                            trailing = {
                                Switch(
                                    checked = !authState.aiDisabled,
                                    onCheckedChange = { authViewModel.onEvent(AuthEvent.SetAiDisabled(!it)) }
                                )
                            }
                        )
                        RinklDivider()
                        SettingsRow(
                            icon = R.drawable.ic_ai,
                            label = "AI 助手接口",
                            onClick = { dialog = PersonalProfileDialog.AiToken }
                        )
                        RinklDivider()
                        SettingsRow(
                            icon = R.drawable.ic_lock,
                            label = "修改密码",
                            onClick = { dialog = PersonalProfileDialog.Password }
                        )
                        // 绑定通道管理：已绑定行点击走解绑确认，未绑定行点击进绑定页输码。
                        BotChannel.entries.forEach { channel ->
                            RinklDivider()
                            val binding = authState.bindingOf(channel)
                            SettingsRow(
                                icon = R.drawable.ic_link,
                                label = "${channel.label}机器人",
                                value = if (binding.bound) {
                                    if (binding.maskedId.isNotBlank()) "已绑定 …${binding.maskedId}" else "已绑定"
                                } else {
                                    "未绑定"
                                },
                                onClick = {
                                    if (binding.bound) dialog = PersonalProfileDialog.UnbindBot(channel)
                                    else onBindBotClick()
                                }
                            )
                        }
                        RinklDivider()
                        SettingsRow(
                            icon = R.drawable.ic_link,
                            label = "QQ 机器人绑定引导",
                            onClick = onQqBotGuideClick
                        )
                    }
                }
            }

            state.error?.let { message ->
                item(key = "error") {
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(48.dp)) }
        }

        ProfilePageTopBar(
            hasBackground = backgroundUri != null,
            onBack = onBack
        )
    }

    // ---------- 弹窗分发（单一状态机） ----------
    when (val current = dialog) {
        PersonalProfileDialog.Nickname -> TextEditDialog(
            title = "设置昵称",
            label = "昵称",
            placeholder = "留空恢复显示手机号",
            current = state.nickname,
            maxLength = 32,
            onConfirm = {
                dialog = null
                viewModel.onEvent(PersonalProfileEvent.NicknameChanged(it))
                viewModel.onEvent(PersonalProfileEvent.SaveProfile)
            },
            onDismiss = dismissDialog
        )

        PersonalProfileDialog.Signature -> TextEditDialog(
            title = "设置个性签名",
            label = "签名",
            placeholder = "写一句想对自己说的话",
            current = state.signature,
            maxLength = 120,
            singleLine = false,
            onConfirm = {
                dialog = null
                viewModel.onEvent(PersonalProfileEvent.SignatureChanged(it))
                viewModel.onEvent(PersonalProfileEvent.SaveProfile)
            },
            onDismiss = dismissDialog
        )

        PersonalProfileDialog.Birthday -> BirthdayPickerDialog(
            current = state.birthday,
            onConfirm = { iso ->
                dialog = null
                viewModel.onEvent(PersonalProfileEvent.BirthdayChanged(iso))
                viewModel.onEvent(PersonalProfileEvent.SaveProfile)
            },
            onClear = {
                dialog = null
                viewModel.onEvent(PersonalProfileEvent.BirthdayChanged(null))
                viewModel.onEvent(PersonalProfileEvent.SaveProfile)
            },
            onDismiss = dismissDialog
        )

        PersonalProfileDialog.Password ->
            PasswordDialog(viewModel = authViewModel, onDismiss = dismissDialog)

        PersonalProfileDialog.AiToken ->
            AiTokenDialog(viewModel = aiTokenViewModel, onDismiss = dismissDialog)

        is PersonalProfileDialog.UnbindBot -> UnbindBotDialog(
            channel = current.channel,
            onConfirm = {
                dismissDialog()
                authViewModel.onEvent(AuthEvent.UnbindBot(current.channel))
            },
            onDismiss = dismissDialog
        )

        null -> Unit
    }
}

/** 资料页弹窗（与「我的」页 ProfileDialog 状态机独立：两页同时各至多一个弹窗）。 */
private sealed interface PersonalProfileDialog {
    data object Nickname : PersonalProfileDialog
    data object Signature : PersonalProfileDialog
    data object Birthday : PersonalProfileDialog
    data object Password : PersonalProfileDialog
    data object AiToken : PersonalProfileDialog
    data class UnbindBot(val channel: BotChannel) : PersonalProfileDialog
}

// ---------------------------------------------------------------------------
// 卡片
// ---------------------------------------------------------------------------

/**
 * 大头像卡：96dp 圆形头像，点击进相册选图（随后跳裁剪路由）。
 * 显示优先级：服务端头像 URL > 本地 DataStore 缓存 > 昵称/手机号首字徽章。
 */
@Composable
private fun AvatarCard(state: PersonalProfileState, onClick: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(applyCardGlass(shape))
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            val serverAvatar = resolveAvatarFullUrl(RetrofitClient.BASE_URL, state.avatarUrl)
            val avatarSource = serverAvatar ?: state.localAvatarUri
            if (avatarSource != null) {
                AsyncImage(
                    model = avatarSource,
                    contentDescription = "头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                )
            } else {
                // 未设置头像：昵称首字 > 手机号首字 > 「账」
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.nickname?.trim()?.firstOrNull()?.toString() ?: "账",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            // 相机角标：提示「点头像更换」
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(LocalRinklColors.current.themeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_image),
                    contentDescription = "更换头像",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (state.uploadingAvatar) "头像上传中…" else "点击头像更换",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.uploadingAvatar) {
            Spacer(modifier = Modifier.height(6.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = LocalRinklColors.current.themeColor
            )
        }
    }
}

/** 未登录提示卡：说明本地/云端的同步边界，并给登录入口。 */
@Composable
private fun OfflineHintCard(onLoginClick: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(shape)
            .clip(shape)
            .then(applyCardGlass(shape))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "未登录：昵称/头像仅保存在本机",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "登录后形象资料与徽章展示将全量同步到服务端，多端共用",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = onLoginClick) { Text("登录 / 注册") }
    }
}

/** 徽章展示管理卡：横向陈列已解锁徽章，勾选 ≤3 枚上「我的」卡片。 */
@Composable
private fun ShowcaseBadgesCard(
    unlocked: List<AchievementState>,
    selected: List<String>,
    onToggle: (String) -> Unit
) {
    SettingsGroupCard(title = "徽章展示") {
        if (unlocked.isEmpty()) {
            Text(
                text = "还没有解锁的徽章，去「Rk省钱计划」完成挑战吧",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        } else {
            unlocked.forEach { badge ->
                val checked = badge.id in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(badge.id) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(achievementBadgeRes(badge.id)),
                        contentDescription = achievementBadgeName(badge.id),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = achievementBadgeName(badge.id),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "已解锁",
                            fontSize = 12.sp,
                            color = LocalRinklColors.current.incomeColor
                        )
                    }
                    Checkbox(checked = checked, onCheckedChange = { onToggle(badge.id) })
                }
            }
        }
        Text(
            text = "最多展示 $MAX_SHOWCASE_BADGES 枚，勾选后出现在「我的」卡片；徽章因删账单回退时会自动隐藏",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

/** 资料页顶栏：返回 + 居中标题，风格与自定义主题页一致。 */
@Composable
private fun ProfilePageTopBar(hasBackground: Boolean, onBack: () -> Unit) {
    // 有照片背景时用白字（靠照片/遮罩衬托），否则用自定义主题「顶栏标题色」。
    val textColor = if (hasBackground) Color.White else LocalRinklColors.current.topBarTitleColor
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = "个人资料",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

// ---------------------------------------------------------------------------
// 弹窗
// ---------------------------------------------------------------------------

/** 单行/多行文本编辑弹窗（昵称 / 签名共用；确认时空串 = 清除该字段）。 */
@Composable
private fun TextEditDialog(
    title: String,
    label: String,
    placeholder: String,
    current: String?,
    maxLength: Int,
    singleLine: Boolean = true,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(maxLength) },
                    label = { Text(label) },
                    placeholder = { Text(placeholder) },
                    supportingText = { Text("${text.length}/$maxLength") },
                    singleLine = singleLine
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 生日选择弹窗（Material3 DatePicker）：确认回传 ISO 串（yyyy-MM-dd），
 * 另给「清除」入口（部分资料页允许不留生日）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayPickerDialog(
    current: String?,
    onConfirm: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val initialMillis = remember(current) {
        runCatching {
            current?.let { LocalDate.parse(it).toEpochDay() * 86_400_000L }
        }.getOrNull()
    }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    // DatePicker 产出 UTC 毫秒，必须按 UTC 取回日期，否则会差一天。
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        val iso = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        onConfirm(iso)
                    }
                }
            ) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = onClear) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    ) {
        DatePicker(state = pickerState)
    }
}

// ---------------------------------------------------------------------------
// 纯格式化
// ---------------------------------------------------------------------------

/** 生日行的展示值：`yyyy-MM-dd · 星座`（星座按公历区间实时推算）；未设置显示「未设置」。 */
internal fun birthdayValueLabel(birthdayIso: String?): String {
    val iso = birthdayIso?.takeIf { it.isNotBlank() } ?: return "未设置"
    val constellation = Constellation.ofBirthday(iso) ?: return iso
    return "$iso · $constellation"
}
