package com.example.rinklnote.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/**
 * 记账页左上角「更多」抽屉：从屏幕左缘滑入，顶部个人信息 + 下方功能模块列表。
 *
 * 实现方式对齐 [com.example.rinklnote.ui.screen.quickadd.QuickAddDrawer]：
 * Box 层级 + AnimatedVisibility（slideInHorizontally，走 Motion 令牌）；
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
 * @param hazeState 毛玻璃状态；null = 无背景照片可采样，面板回落纯白实心
 */
@Composable
fun MoreDrawer(
    isVisible: Boolean,
    profileName: String,
    profileLoggedIn: Boolean,
    onDismiss: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenBillMap: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenMultiCurrency: () -> Unit = {},
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )

            // 抽屉面板：左缘贴边、宽 200dp、高 88%（几何对齐 QuickAddDrawer 面板，左右镜像）、右缘 18dp 圆角
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(0.88f)
                    .width(200.dp)
                    .padding(vertical = 8.dp)
                    .clip(panelShape)
                    .then(panelSurface)
                    .then(applyCardGlass(panelShape))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* 面板吞掉点击，避免透传到点击层误关 */ }
            ) {
                DrawerContent(
                    profileName = profileName,
                    profileLoggedIn = profileLoggedIn,
                    onDismiss = onDismiss,
                    onOpenSearch = onOpenSearch,
                    onOpenBillMap = onOpenBillMap,
                    onOpenImport = onOpenImport,
                    onOpenMultiCurrency = onOpenMultiCurrency,
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

@Composable
private fun DrawerContent(
    profileName: String,
    profileLoggedIn: Boolean,
    onDismiss: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBillMap: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenMultiCurrency: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 个人信息区避让状态栏：面板上缘可能顶进状态栏，内容整体下压
            .statusBarsPadding()
            // 顶部呼吸：状态栏避让后再垫 12dp，头像区不顶死状态栏
            .padding(top = 12.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // ---- 顶部个人信息区 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarBadge(
                character = if (profileLoggedIn) {
                    profileName.trim().firstOrNull()?.toString() ?: "账"
                } else {
                    "账"
                },
                size = 48.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (profileLoggedIn && profileName.isNotBlank()) profileName else "未登录",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (profileLoggedIn) "已登录" else "登录后可云端同步账单",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            // 右上角关闭（轻量 ✕ 触点，同快捷记账抽屉建议条的关闭样式）
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 信息区与操作区之间拉开 12dp 层次（分割线上下各 6dp）
        Spacer(modifier = Modifier.height(6.dp))
        RinklDivider(endInset = 6.dp)
        Spacer(modifier = Modifier.height(6.dp))

        // ---- 功能模块列表（行高 48dp，SettingsRow 风格）----
        MoreDrawerRow(
            icon = { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = tint
                )
            },
            label = "搜索账单",
            onClick = closeThen(onDismiss, onOpenSearch)
        )
        MoreDrawerRow(
            icon = { tint -> Icon(Icons.Filled.LocationOn, contentDescription = null, tint = tint) },
            label = "账单地图",
            onClick = closeThen(onDismiss, onOpenBillMap)
        )
        MoreDrawerRow(
            icon = { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_sync),
                    contentDescription = null,
                    tint = tint
                )
            },
            label = "导入账单",
            onClick = closeThen(onDismiss, onOpenImport)
        )
        MoreDrawerRow(
            icon = { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_finance),
                    contentDescription = null,
                    tint = tint
                )
            },
            label = "多币种",
            onClick = closeThen(onDismiss, onOpenMultiCurrency)
        )
        MoreDrawerRow(
            icon = { tint -> Icon(Icons.Filled.Settings, contentDescription = null, tint = tint) },
            label = "设置",
            onClick = closeThen(onDismiss, onOpenSettings)
        )
        MoreDrawerRow(
            icon = { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = tint
                )
            },
            label = "关于",
            onClick = closeThen(onDismiss, onOpenAbout)
        )
    }
}

/**
 * 功能模块单行：前置图标 + 标签 + 右侧「>」箭头。
 *
 * 行高、字号、图标尺寸与 [SettingsRow] 对齐（48dp 行 / 16sp 标签 / 24dp 图标 / 20dp 箭头）；
 * 图标色跟随自定义主题「图标/按钮色」槽（与设置页行图标一致），经参数注入到 icon 槽。
 */
@Composable
private fun MoreDrawerRow(
    icon: @Composable (Color) -> Unit,
    label: String,
    onClick: () -> Unit
) {
    val iconTint = LocalRinklColors.current.iconButtonColor
        ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            icon(iconTint)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = LocalRinklColors.current.iconButtonColor
                ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
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
