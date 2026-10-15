package com.example.rinklnote.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.rinklnote.R
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.theme.RinklNoteTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** 导航数据只含展示信息，路由与页面顺序仍由导航层管理。 */
data class LiquidGlassTab(val label: String, val iconRes: Int)

private object GlassNav {
    val Height = 60.dp
    val Inset = 6.dp
    val Radius = 30.dp
    val IconSize = 20.dp
    val Blur = 22.dp
}

/** 独立预览方便后续调整玻璃边缘，不依赖路由或设备数据。 */
@Preview(name = "液态玻璃导航", widthDp = 390, heightDp = 90, showBackground = true)
@Composable
private fun LiquidGlassBottomBarPreview() {
    RinklNoteTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.BottomCenter) {
            LiquidGlassBottomBar(
                tabs = listOf(LiquidGlassTab("计划", R.drawable.ic_nav_plan),
                    LiquidGlassTab("记账", R.drawable.ic_nav_bookkeeping),
                    LiquidGlassTab("资产", R.drawable.ic_nav_assets),
                    LiquidGlassTab("我的", R.drawable.ic_nav_profile)),
                selectedIndex = 1, hazeState = null, onTabClick = {})
        }
    }
}

/** 半透明浮动胶囊：模糊采样、双向光泽边缘与柔和移动的选中态。 */
@Composable
fun LiquidGlassBottomBar(
    tabs: List<LiquidGlassTab>,
    selectedIndex: Int,
    hazeState: HazeState?,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val accent = LocalRinklColors.current.navIconColor
        ?: lerp(LocalRinklColors.current.themeColor, colors.onSurfaceVariant, 0.18f)
    val inactive = LocalRinklColors.current.iconButtonColor ?: colors.onSurfaceVariant
    val shape = RoundedCornerShape(GlassNav.Radius)
    val material = HazeStyle(
        backgroundColor = colors.surface.copy(alpha = if (dark) 0.2f else 0.1f),
        tints = listOf(HazeTint(colors.surface.copy(alpha = if (dark) 0.22f else 0.14f))),
        blurRadius = GlassNav.Blur,
        noiseFactor = 0f,
        fallbackTint = HazeTint(colors.surface.copy(alpha = 0.7f))
    )
    Box(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(GlassNav.Height)
                .shadow(4.dp, shape, ambientColor = colors.onSurface.copy(alpha = 0.035f),
                    spotColor = colors.onSurface.copy(alpha = 0.045f))
                .clip(shape)
                .then(if (hazeState != null) Modifier.hazeEffect(hazeState, material)
                    else Modifier.background(colors.surface.copy(alpha = 0.5f)))
                .drawWithCache {
                    val sheen = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = if (dark) 0.07f else 0.22f),
                            Color.Transparent, accent.copy(alpha = 0.025f)), Offset.Zero, Offset(size.width, size.height))
                    val rim = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = if (dark) 0.25f else 0.65f),
                            accent.copy(alpha = 0.065f), Color.White.copy(alpha = if (dark) 0.12f else 0.4f)),
                        Offset.Zero, Offset(size.width, size.height))
                    onDrawWithContent {
                        drawRect(sheen)
                        drawContent()
                        val stroke = 1.dp.toPx()
                        drawRoundRect(rim, topLeft = Offset(stroke/2, stroke/2),
                            size = androidx.compose.ui.geometry.Size(size.width-stroke, size.height-stroke),
                            cornerRadius = CornerRadius(GlassNav.Radius.toPx()), style = Stroke(stroke))
                    }
                }
        ) {
            // 使用实际内宽分配点击区，避免外边距导致最后一个入口被裁切。
            val slot = (maxWidth-GlassNav.Inset*2)/tabs.size
            val selectionX by animateDpAsState(
                GlassNav.Inset + slot*selectedIndex.coerceIn(tabs.indices),
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 450f), label = "玻璃导航选中位置")
            Box(Modifier.offset { IntOffset(selectionX.roundToPx(), GlassNav.Inset.roundToPx()) }
                .width(slot).height(GlassNav.Height-GlassNav.Inset*2)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(
                    Color.White.copy(alpha = if (dark) 0.08f else 0.42f), accent.copy(alpha = if (dark) 0.1f else 0.055f)))))
            Row(Modifier.fillMaxSize().padding(GlassNav.Inset).selectableGroup()) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    val tint by animateColorAsState(if (selected) accent else inactive,
                        animationSpec = Motion.SelectColor, label = "玻璃导航图标颜色")
                    Column(Modifier.width(slot).height(GlassNav.Height-GlassNav.Inset*2)
                        .semantics { this.selected = selected }
                        .motionClickable(role = Role.Tab, onClickLabel = tab.label) { onTabClick(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                        Icon(painterResource(tab.iconRes), contentDescription = null, tint = tint,
                            modifier = Modifier.size(GlassNav.IconSize))
                        Text(tab.label, color = tint, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}
