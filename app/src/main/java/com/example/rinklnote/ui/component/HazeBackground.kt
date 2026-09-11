package com.example.rinklnote.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeSource

/**
 * 全局卡片毛玻璃样式（Card Frosted Glass）—— 带白雾的「雾面」玻璃。
 *
 * 设计权衡：`HazeMaterials.regular()` 默认 tint 接近纯白（alpha≈0.6），会把背景全盖住，
 * 卡片退化成"有阴影的白卡"。改成自定义 [HazeStyle]：极淡白雾（0.06f）+ 24dp 模糊，
 * 让背景渐变与光斑透过卡片更清晰可见。
 *
 * 用途：重点信息卡（首页「首支总览」SummaryBar、资产「总资产」TotalAssetsCard、
 * 计划「本月预算」TotalBudgetCard、底部 nav 栏）在有/无自选背景时都用它——
 * 白雾能保证文字对比度，即使背景是照片也可读。
 *
 * **必须配 [DefaultHazeBackground]/nav 层 [AppBackground] 使用**：背景层必须 `hazeSource(state)`
 * 包裹整个 backdrop（渐变 + 光斑），否则卡片采样不到背景，毛玻璃退化成纯 tint 雾。
 *
 * 触摸目标 ≥ 44dp 由调用方用 `IconButton(48dp)` / `defaultMinSize(44dp)` 保障。
 */
val RinklCardFrostedStyle: HazeStyle = HazeStyle(
    backgroundColor = Color.Transparent,
    tints = listOf(HazeTint(Color.White.copy(alpha = 0.06f))),
    blurRadius = 24.dp,
    noiseFactor = 0f,
    fallbackTint = HazeTint.Unspecified
)

/**
 * 全局卡片「透窗」样式（Clear Glass）—— 完全透明，无 tint、无 blur。
 *
 * 用途：普通卡片（账单卡 BillCard、热力图 HeatmapBox、账户卡 AccountCard、
 * 我的页各 GroupCard、分类预算卡 CategoryBudgetCard）在**有自选背景**时用它，
 * 让背景照片透过卡片**清晰可见**（不模糊），只靠卡片圆角 + `rinkShadow` 阴影作边界。
 *
 * 实现：不调用 `hazeEffect`，等于卡片位置完全无任何蒙层（最高透明度）。
 * 视觉上像透过一块无玻璃的「窗框」看照片。
 *
 * 无自选背景时这些卡片应回退到 [RinklCardFrostedStyle]（背景是浅色渐变 + 光斑，
 * 完全透明会让文字对比度不足）。调用方按 `backgroundUri != null` 选择。
 */

/**
 * 卡片的「统一透明框」修饰：全 App 除底部导航（毛玻璃）和加账单/新建账户 FAB 外，
 * 所有卡片统一走这里——完全透明、只靠边框勾勒边界。
 *
 * - 有自选背景 → **白色描边**（`border(1.dp, 白 0.55)`），背景照片透过卡片清晰可见
 * - 无背景（纯白页）→ **很浅灰边**（`border(1.dp, 黑 0.08)`），透明框在纯白底上以线条呈现
 *
 * 调用方式：`Modifier.clip(shape).then(applyCardGlass(hazeState, backgroundUri, shape))`。
 *
 * @param shape 卡片圆角（与调用方 `clip` 用同一 shape，保证边框贴合圆角）
 */
@Composable
fun applyCardGlass(hazeState: HazeState, backgroundUri: String?, shape: Shape): Modifier {
    return if (backgroundUri != null) {
        // 透明框：白色描边（照片背景上纯透明无边框会糊成一团）。
        Modifier.border(width = 1.dp, color = Color.White.copy(alpha = 0.55f), shape = shape)
    } else {
        // 透明框：很浅灰边（纯白背景上以线条勾勒卡片轮廓）。
        Modifier.border(width = 1.dp, color = Color.Black.copy(alpha = 0.08f), shape = shape)
    }
}

/**
 * 记账/资产/预算/我的页默认背景：**纯白**（`background` 主题色），作为毛玻璃（haze）的 blur 源。
 *
 * - 用户未自选照片时由各页自行铺设（`hazeSource`）；选了照片则由 nav 层 [AppBackground] 整窗铺满，
 *   此时本函数不再被调用（背景由照片提供，毛玻璃直接采样照片）。
 * - 抽公共前：该背景是 `BookkeepingScreen` 的 private `DefaultBackgroundLayer`（最初为渐变+光斑）。
 * - 2026-09-10 用户拍板：无自选背景时背景**换回纯白**（去掉渐变与装饰光斑），
 *   让首组件白色实心卡与纯白背景融为一体、观感干净统一。
 *
 * @param hazeState 毛玻璃状态持有者（[HazeState]），纯白底层注册为 blur source
 * @param modifier 外部修饰符，默认 [Modifier]，纯白层会强制 `fillMaxSize` 铺满
 */
@Composable
fun DefaultHazeBackground(hazeState: HazeState, modifier: Modifier = Modifier) {
    // 整个 backdrop 包在一个 hazeSource 里，让所有卡片都能采样到背景。
    Box(
        modifier = modifier
            .fillMaxSize()
            .hazeSource(hazeState)
            .background(MaterialTheme.colorScheme.background)
    )
}
