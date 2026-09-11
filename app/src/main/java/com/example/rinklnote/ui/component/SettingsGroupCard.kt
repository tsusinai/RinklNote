package com.example.rinklnote.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.ui.theme.LocalRinklColors

/**
 * 设置类页面的「分组卡」骨架：透明玻璃底 + 统一边框 + 可选浅色小标题 + 若干设置行。
 *
 * 材质与全 App 卡片一致：`rinkShadow` 阴影 + [applyCardGlass] 只画 1dp 边框、不挂 `hazeEffect`，
 * 自选背景照片直接透过卡片可见。`clip` 之后只接 padding，**绝不再加 `background`**
 * （会盖掉透明框的观感）。
 *
 * 2026-09-11 统一来源：「我的」页与「自定义主题」页原先各写了一份逐行相同的实现
 * （`GroupCard` / `ThemeGroupCard`），现合并为本组件——避免两边改一处漏一处。
 * 全 App 需要「分组小标题 + 设置行」的卡片都应当用它。
 *
 * @param title 分组小标题，`null` 表示不显示
 */
@Composable
fun SettingsGroupCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rinkShadow(RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .then(applyCardGlass(RoundedCornerShape(16.dp)))
            .padding(vertical = 4.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            )
        }
        content()
    }
}

/**
 * 设置类页面的「单行设置」：前置图标 + 标签 + 右侧值 / 「>」/ 开关。
 *
 * **行高统一 48dp**：竖向内边距 8dp —— 带 `Switch`（32dp 高）的行正好 48dp，无按钮的行由
 * `defaultMinSize(48dp)` 兜到同高，因此带开关与不带开关的行等高。
 * （2026-09-11 修正：原竖向 12dp 会把带开关的行撑到 56dp，出现「一行高一行矮」。）
 *
 * 触摸目标 ≥ 48dp（`defaultMinSize`）。
 *
 * @param icon 前置图标资源，`null` 表示不显示图标列
 * @param iconTint 图标色，默认跟随自定义主题「图标/按钮色」槽
 * @param value 右侧次要文字，`null` 表示不显示
 * @param labelColor 标签文字色（如「退出登录」用 error 色）
 * @param onClick 非 `null` 时整行可点，且右侧自动补「>」箭头
 * @param trailing 右侧自定义内容（如 `Switch`）；给了它就不再画「>」箭头
 */
@Composable
fun SettingsRow(
    icon: Int? = null,
    iconTint: Color = LocalRinklColors.current.iconButtonColor ?: MaterialTheme.colorScheme.primary,
    label: String,
    value: String? = null,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = label,
            fontSize = 16.sp,
            color = labelColor,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = LocalRinklColors.current.iconButtonColor
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
