package com.example.rinklnote.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.util.DisplayPreferences
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
 * 卡片边框的统一规则（2026-09-11 最终版，用户拍板）。
 *
 * 全 App 除底部导航（毛玻璃）与加账单/新建账户 FAB 外，所有卡片都走这里——
 * 卡片自身完全透明、**不挂 `hazeEffect`**，只靠一道 1dp 边框勾出边界，
 * 自选背景照片直接透过卡片可见。
 *
 * 边框色**只有一个来源**：[LocalRinklColors] 的「边框色」槽（「自定义主题」页第 5 项）。
 * - 纯白底与自选照片底用**同一个色**，不再按「有无照片」分两套令牌 —— 早前那套
 *   只要漏传一处 `backgroundUri` 就会出现同页框线深浅不一致（2026-09-11 踩过三次），
 *   用户最终选择「统一跟随主题的边框色」。
 * - 边框色可设为**透明**（alpha = 0）：等于用户显式选择「不描边」，此时连 1dp 都不挂。
 *   **「不要边框」只此一个开关**，不要再引入 `backgroundUri == null` 之类的隐式条件。
 *
 * **全 App 只有这一个边框来源**：任何组件不要再自己写 `border(...)` 定边框色。
 *
 * 调用方式：`Modifier.clip(shape).then(applyCardGlass(shape))`。
 *
 * **卡片白色蒙版开关**（「我的 → 个性化」组，[DisplayPreferences.cardOverlayEnabled]）：
 * - 关闭（默认）：行为与 2026-09-11 版完全一致——只有边框、无任何蒙版。
 * - 开启：在边框**之前**铺一层雾蒙版压住照片背景，保证正文可读。浅色主题用灰白雾
 *   （`Color(0xFFF2F3F5).copy(alpha = 0.42f)`）；暗色主题用黑雾（`Black.copy(alpha = 0.28f)`）——
 *   暗底下白雾会把卡片抬亮、白字读不清。
 *   取值理由（2026-09-15 用户反馈「纯白蒙版偏死白、不够柔和」）：亮色由纯白 55% 改为
 *   带轻微冷灰调的灰白 `0xFFF2F3F5` 并降不透明度到 42%——灰调消掉「粉刷感」、更低的
 *   alpha 让背景照片多透一些，雾感更轻；暗色同步由 35% 降到 28%（同反馈下取更柔者，
 *   比 `0xFF141417 @ 0.4f` 透出度更高、压暗更轻）。正文可读性优先于透出度，
 *   若真机上深色文字发虚，优先回调亮色 alpha（0.42 → 0.48 一档）而非改回纯白。
 * - 蒙版与边框相互独立：即使槽位是「不描边」（透明边框），蒙版照常生效。
 *
 * @param shape 卡片圆角（与调用方 `clip` 用同一 shape，保证边框贴合圆角）
 */
@Composable
fun applyCardGlass(shape: Shape): Modifier {
    // 蒙版开关：collectAsState 读取（DisplayPreferences 由 MainActivity 从 DataStore 桥接进来）。
    val overlayEnabled by DisplayPreferences.cardOverlayEnabled.collectAsState()
    // 明暗判断不用 isSystemInDarkTheme：App 支持「设置 → 深色模式」在应用内强制 LIGHT/DARK，
    // 系统明暗可能与实际渲染主题相反；当前主题 background 的亮度在三种模式下都如实反映明暗。
    val overlayColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color.Black.copy(alpha = 0.28f) // 暗色：黑雾（0.35 → 0.28，2026-09-15 柔化）
    } else {
        Color(0xFFF2F3F5).copy(alpha = 0.42f) // 浅色：灰白雾（纯白 0.55 → 灰白 0.42，2026-09-15 柔化）
    }
    val border = LocalRinklColors.current.borderColor
    val overlay = if (overlayEnabled) Modifier.background(overlayColor) else Modifier
    // 透明 = 用户选了「不描边」：直接不挂 border，别画一条看不见的 1dp 线。
    if (border.alpha == 0f) return overlay
    // 蒙版挂在 border 之前：先铺雾、再描边，边框不被雾盖淡。
    return overlay.then(Modifier.border(width = 1.dp, color = border, shape = shape))
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
