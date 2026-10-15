package com.example.rinklnote.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.rinklnote.ui.component.motionClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import com.example.rinklnote.ui.component.RinklIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/** 省钱计划入口开关（暂时关闭相关功能和入口，待后续开放时置为 true 即可恢复）。 */
private const val SHOW_CHALLENGES = false

/**
 * 记账页左上角「更多」抽屉：从屏幕左缘滑入，顶部个人信息 + 下方功能模块列表。
 *
 * 实现方式对齐 [com.example.rinklnote.ui.screen.quickadd.QuickAddDrawer]：
 * Box 层级 + AnimatedVisibility（slideInHorizontally，走 Motion 令牌）；
 * 面板满高顶天立地（不做 0.88h 居中悬浮），顶部节奏 = 状态栏避让 + 12dp 呼吸，单一来源；
 * 面板材质同款「雾面玻璃」（[RinklCardFrostedStyle] + [applyCardGlass]）——
 * `hazeState` 非空才挂 `hazeEffect`（有自选背景时由 nav 层传入），否则回落纯白实心。
 *
 * 交互约定：点击功能行**先关闭抽屉再触发回调**（`onDismiss` 在内部先行调用）；
 * 遮罩层为**透明可点击层**（不做黑化，拉出时背景保持原观感，对齐 [com.example.rinklnote.ui.screen.quickadd.QuickAddDrawer]），
 * 点击空白处关闭；系统返回键关闭（[BackHandler]）。
 *
 * @param isVisible 抽屉可见性（由调用方 `remember` 的布尔状态驱动）
 * @param profileName 登录昵称/手机号；未登录时忽略，显示「未登录」
 * @param profileLoggedIn 是否已登录
 * @param onDismiss 关闭抽屉
 * @param onOpenSearch 「搜索账单」项回调（路由 `bill-search` 由主会话接线）；
 *   默认空实现——现调用点（BookkeepingScreen）未传参也能编译，主会话集成时再接通
 * @param onOpenBillMap 「账单地图」项回调（路由 `bill-map` 由主会话接线）
 * @param onOpenImport 「导入账单」项回调（路由 `bill-import` 由主会话接线）
 * @param onOpenSettings 「设置」项回调（当前跳「我的」页）
 * @param onOpenAbout 「关于」项回调（当前跳「我的」页，后续可接独立关于页）
 * @param onOpenMultiCurrency 「多币种」项回调（路由 `multi-currency` 由主会话接线）；
 *   默认空实现——现调用点（BookkeepingScreen）未传参也能编译，主会话集成时再接通
 * @param onOpenChallenges 「Rk省钱计划」项回调（路由 `challenges` 由主会话接线）；
 *   默认空实现——同上，主会话集成时接通
 * @param hazeState 毛玻璃状态；null = 无背景照片可采样，面板回落纯白实心
 */
@Composable
fun MoreDrawer(
    isVisible: Boolean,
    profileName: String,
    profileLoggedIn: Boolean,
    onDismiss: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenLogin: () -> Unit = {},
    onOpenBillMap: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenMultiCurrency: () -> Unit = {},
    onOpenChallenges: () -> Unit = {},
    onOpenAutoCapture: () -> Unit = {},
    onOpenBot: () -> Unit = {},
    pendingCaptureCount: Int = 0,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = isVisible) { onDismiss() }

    // 面板：只切右缘圆角（左缘贴屏幕边）；材质按 hazeState 有无切换（同 QuickAddDrawer）
    val panelShape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
    val panelSurface = if (hazeState != null) {
        Modifier.hazeEffect(hazeState, RinklCardFrostedStyle)
    } else {
        Modifier.background(MaterialTheme.colorScheme.surface)
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(
            animationSpec = Motion.DrawerEnter,
            initialOffsetX = { -it } // 从左缘滑入
        ),
        exit = slideOutHorizontally(
            animationSpec = Motion.DrawerExit,
            targetOffsetX = { -it }
        ),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 透明可点击层：仅用于点击空白处关闭（不做黑化遮罩，随抽屉进出出现/消失）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .motionClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )

            // 面板收窄到 240dp，保留更多主页内容；右缘圆角与原有背景材质一致。
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(adaptiveMoreDrawerWidth())
                    .clip(panelShape)
                    .then(panelSurface)
                    .then(applyCardGlass(panelShape))
                    .motionClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* 面板吞掉点击，避免透传到点击层误关 */ }
            ) {
                DrawerContent(
                    profileName = profileName,
                    profileLoggedIn = profileLoggedIn,
                    onDismiss = onDismiss,
                    onOpenSearch = onOpenSearch,
                    onOpenLogin = onOpenLogin,
                    onOpenBillMap = onOpenBillMap,
                    onOpenImport = onOpenImport,
                    onOpenMultiCurrency = onOpenMultiCurrency,
                    onOpenChallenges = onOpenChallenges,
                    onOpenAutoCapture = onOpenAutoCapture,
                    onOpenBot = onOpenBot,
                    pendingCaptureCount = pendingCaptureCount,
                    onOpenSettings = onOpenSettings,
                    onOpenAbout = onOpenAbout
                )
            }
        }
    }
}

/** 行点击的统一收口：先关抽屉再触发回调（导航发生在关抽屉动画启动之后）。 */
private fun closeThen(onDismiss: () -> Unit, action: () -> Unit): () -> Unit =
    { onDismiss(); action() }

/** 侧栏最多 240dp；窄屏仍留出至少 28% 的主页用于点击收起。 */
@Composable
private fun adaptiveMoreDrawerWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return minOf(240.dp, screenWidth * 0.72f)
}

@Composable
private fun DrawerContent(
    profileName: String,
    profileLoggedIn: Boolean,
    onDismiss: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenBillMap: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenMultiCurrency: () -> Unit,
    onOpenChallenges: () -> Unit,
    onOpenAutoCapture: () -> Unit,
    onOpenBot: () -> Unit,
    pendingCaptureCount: Int,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 顶部节奏单一来源：状态栏避让（系统 insets，唯一系统层）+ 固定 12dp 呼吸（唯一呼吸层），
            // 此后头像区不再叠加任何顶部间距（此前 12/10/8dp 三层内边距叠加已移除）
            .statusBarsPadding()
            .padding(top = 12.dp)
            // 内容列左右留白 12dp（头像区行内再补 4dp 凑 16dp；列表行内自带 16dp 内边距）
            .padding(horizontal = 12.dp)
            // 底部兜底：面板满高后伸到手势条下面，只避让 Bottom 一侧（避免横屏时侧边
            // 导航条 inset 把面板内容挤偏），再垫 8dp 收尾，末行「关于」不顶死手势条
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(bottom = 8.dp)
    ) {
        // ---- 顶部个人信息区 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 信息区：未登录时支持点击跳转登录，与右侧关闭按钮并列解耦（避免嵌套 clickable 冲突）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (profileLoggedIn) {
                            Modifier
                        } else {
                            Modifier.motionClickable(onClickLabel = "去登录") {
                                onDismiss()
                                onOpenLogin()
                            }
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarBadge(
                    character = if (profileLoggedIn) {
                        profileName.trim().firstOrNull()?.toString() ?: "账"
                    } else {
                        "账"
                    },
                    size = 36.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (profileLoggedIn && profileName.isNotBlank()) profileName else "未登录",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (profileLoggedIn) "已登录" else "登录同步账单",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            // 右上角关闭：IconButton 保证 ≥48dp 触控 + 读屏「关闭」（体验审查 P2，原 28dp 触点过小）
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_close),
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 信息区与操作区之间拉开 12dp 层次（分割线上下各 6dp）
        Spacer(modifier = Modifier.height(6.dp))
        RinklDivider(endInset = 6.dp)
        Spacer(modifier = Modifier.height(6.dp))

        // 功能区可滚动，横屏和较大字号下仍可访问最后一项。
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            DrawerSectionLabel("记账工具")
            MoreDrawerRow(
                iconRes = R.drawable.ic_more_search,
                label = "搜索账单",
                onClick = closeThen(onDismiss, onOpenSearch)
            )
            MoreDrawerRow(
                iconRes = R.drawable.ic_more_map,
                label = "账单地图",
                onClick = closeThen(onDismiss, onOpenBillMap)
            )
            MoreDrawerRow(
                iconRes = R.drawable.ic_more_import,
                label = "导入账单",
                onClick = closeThen(onDismiss, onOpenImport)
            )
            MoreDrawerRow(
                iconRes = R.drawable.ic_more_currency,
                label = "多币种",
                onClick = closeThen(onDismiss, onOpenMultiCurrency)
            )
            MoreDrawerRow(
                iconRes = R.drawable.ic_more_capture,
                label = "自动记账",
                badge = if (pendingCaptureCount > 0) {
                    {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.error)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (pendingCaptureCount > 99) "99+" else pendingCaptureCount.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                } else null,
                onClick = closeThen(onDismiss, onOpenAutoCapture)
            )
            if (SHOW_CHALLENGES) {
                MoreDrawerRow(
                    iconRes = R.drawable.ic_more_challenge,
                    label = "Rk省钱计划",
                    onClick = closeThen(onDismiss, onOpenChallenges)
                )
            }
            Spacer(Modifier.height(12.dp))
            RinklDivider()
            Spacer(Modifier.height(8.dp))
            DrawerSectionLabel("连接与偏好")
            MoreDrawerRow(
                iconRes = R.drawable.ic_more_bot,
                label = "机器人",
                onClick = closeThen(onDismiss, onOpenBot)
            )
            MoreDrawerRow(
                iconRes = R.drawable.ic_more_settings,
                label = "设置",
                onClick = closeThen(onDismiss, onOpenSettings)
            )
            MoreDrawerRow(
                iconRes = R.drawable.ic_more_info,
                label = "关于",
                onClick = closeThen(onDismiss, onOpenAbout)
            )
        }
    }
}

/** 简短分组标题，减少窄侧栏内的视觉负担。 */
@Composable
private fun DrawerSectionLabel(label: String) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
}

/**
 * 功能模块单行：前置图标 + 标签 + 右侧「>」箭头。
 *
 * 48dp 触控行，14sp 标签与 20dp 细线图标，保持窄侧栏清晰紧凑；
 * 图标色跟随自定义主题「图标/按钮色」槽（与设置页行图标一致），经参数注入到 icon 槽。
 */
@Composable
private fun MoreDrawerRow(
    iconRes: Int,
    label: String,
    badge: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val iconTint = LocalRinklColors.current.iconButtonColor
        ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // mergeDescendants：读屏把「图标 + 标签 + 箭头」合并为单条「标签，按钮」语义，不逐项播报
            .semantics(mergeDescendants = true) {}
            .motionClickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = iconTint,
            modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badge != null) {
            badge()
            Spacer(modifier = Modifier.width(6.dp))
        }
        Icon(
            painter = painterResource(R.drawable.ic_more_chevron),
            contentDescription = null,
            tint = LocalRinklColors.current.iconButtonColor
                ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 圆形头像占位（首字符徽章），样式对齐「我的」页头像：primaryContainer 底 + onPrimaryContainer 字。
 */
@Composable
private fun AvatarBadge(character: String, size: androidx.compose.ui.unit.Dp = 48.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = character,
            fontSize = (size.value * 0.43f).sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
