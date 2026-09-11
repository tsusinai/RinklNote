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
import dev.chrisbanes.haze.hazeEffect
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
 * 普通卡片的「玻璃修饰」：根据是否铺了自选背景返回带/不带 `hazeEffect` 的 [Modifier]。
 *
 * - 有背景 → 返回**白色描边**（`border(1.dp, 白 0.55)`），卡片完全透明、靠白边勾勒边界
 * - 无背景 → 返回 `.hazeEffect(hazeState, RinklCardFrostedStyle)`（白雾玻璃）+ **很浅灰边**（`border(1.dp, 黑 0.08)`），
 *   让白雾卡在纯白背景上也能看出卡片轮廓（不糊成一片）
 *
 * 重点卡片（首支总览/总资产/本月预算/nav 栏）始终用 [RinklCardFrostedStyle]，
 * 不到此函数。调用方式：`Modifier.then(applyCardGlass(hazeState, backgroundUri, shape))`。
 *
 * @param shape 卡片圆角（与调用方 `clip` 用同一 shape，保证白边贴合圆角）
 */
@Composable
fun applyCardGlass(hazeState: HazeState, backgroundUri: String?, shape: Shape): Modifier {
    return if (backgroundUri != null) {
        // 透明玻璃：加白色描边勾勒卡片边界（照片背景上纯透明无边框会糊成一团）。
        Modifier.border(width = 1.dp, color = Color.White.copy(alpha = 0.55f), shape = shape)
    } else {
        // 白雾玻璃：白雾 + 很浅灰边（纯白背景上也能看清卡片轮廓）。
        Modifier
            .hazeEffect(hazeState, RinklCardFrostedStyle)
            .border(width = 1.dp, color = Color.Black.copy(alpha = 0.08f), shape = shape)
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
