package com.example.rinklnote.ui.screen.challenge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.pressScale
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 省钱挑战区「新丑风大字报」局部样式库。
 *
 * 定位（范围红线）：大字报风格**只在挑战区内部成立**，与工具区形成刻意反差——
 * 日常记账是安静工具，挑战区是贴满海报的游乐场。本文件所有描边 / 阴影 / 撞色都是
 * **局部绘制**（drawBehind 实心偏移阴影 + 局部 border），绝不触碰全局 RinklColors
 * 令牌（BORDER 默认透明等全局约定保持原样）。
 *
 * 视觉体系：
 * - 硬阴影贴纸卡：[PosterCard] 3dp 纯黑描边 + 6dp 实心偏移阴影，按压压平阴影收回；
 * - 撞色系统：[PosterPair] 从 primary / expense / income 色相衍生（已解锁主题换色相后自然跟随），
 *   暗色主题下饱和度 / 明度另行校准，撞色同样成立；
 * - 超大数字：[HugeNumberText] 44–56sp 超粗字重，每页的绝对主角；
 * - 动效：[Modifier.posterBounceEnter] spring 低阻尼弹跳进场、[BloodBar] 血条式逐格填充、
 *   [StickerBurstOverlay] 解锁时刻全屏贴纸炸开——全部 Compose spring / 现有 Motion 令牌，无第三方库。
 */

// ---------------------------------------------------------------------------
// 撞色系统
// ---------------------------------------------------------------------------

/** 一对撞色：海报底色 + 油墨色（文字 / 描边内容色）。 */
data class PosterPair(
    val background: Color,
    val ink: Color,
)

/** 挑战区固定锚点撞色：柠檬黄 × 黑（大字报的「报」字本位，不随主题漂移）。 */
val PosterLemon: Color = Color(0xFFFFD633)

/** 底色够浅 → 黑油墨，否则白油墨（保证任意撞色底上文字可读）。 */
internal fun contrastInk(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White

/**
 * 把主题令牌色「海报化」：色相不变，饱和度 / 明度拉到海报级（新丑风的荧光感）。
 * [dark] = 暗色主题时明度略降，避免大面积荧光在黑底上刺眼——撞色在两种主题下都成立。
 * 换装间解锁新主题 → primary / expense / income 色相变化 → 撞色自动跟随。
 */
internal fun posterizeVivid(color: Color, dark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] = (hsv[1] + 0.25f).coerceAtMost(1f)
    hsv[2] = if (dark) 0.92f else 0.98f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** 五套功能卡撞色（顺序固定：竞技场 / 徽章馆 / 预测器 / 信封 / 换装间）。 */
data class PosterPairs(
    val electric: PosterPair, // 电光蓝 × 白 ← primary（主题色）衍生
    val tomato: PosterPair,   // 番茄红 × 白 ← expense（支出色）衍生
    val mint: PosterPair,     // 薄荷绿 × 黑 ← income（收入色）衍生
    val lemon: PosterPair,    // 柠檬黄 × 黑（固定锚点）
    val paper: PosterPair,    // 白纸 × 黑字（大字报本体）
)

/**
 * 挑战区撞色集合。明暗判断与 applyCardGlass 同口径（背景亮度），尊重应用内强制明暗模式；
 * 颜色一律由现有令牌衍生，本文件不引入任何新的全局令牌。
 */
@Composable
fun rememberPosterPairs(): PosterPairs {
    val rinkl = LocalRinklColors.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val electric = posterizeVivid(rinkl.themeColor, dark)
    val tomato = posterizeVivid(rinkl.expenseColor, dark)
    val mint = posterizeVivid(rinkl.incomeColor, dark)
    return PosterPairs(
        electric = PosterPair(electric, contrastInk(electric)),
        tomato = PosterPair(tomato, contrastInk(tomato)),
        mint = PosterPair(mint, contrastInk(mint)),
        lemon = PosterPair(PosterLemon, Color.Black),
        paper = PosterPair(Color.White, Color.Black),
    )
}

// ---------------------------------------------------------------------------
// 硬阴影贴纸卡
// ---------------------------------------------------------------------------

/** 贴纸卡按压态：阴影收回的动画规格（Motion.PressScale 是 Float 规格，Dp 版本同参数自建）。 */
private val ShadowFlatten: androidx.compose.animation.core.FiniteAnimationSpec<Dp> =
    androidx.compose.animation.core.tween(Motion.DurationPress, easing = Motion.IndicatorEasing)

/**
 * 硬阴影贴纸卡：3dp 纯黑描边 + [shadowDepth] 实心偏移阴影（drawBehind 手绘，不用 blur shadow）。
 * 按压时卡片压平——阴影深度收回 0、整体下落 [shadowDepth]，并叠加现有 [pressScale] 轻缩放。
 *
 * @param pair 撞色对（底色 + 油墨色）
 * @param onClick null = 纯展示卡（不可点）
 */
@Composable
fun PosterCard(
    pair: PosterPair,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    shadowDepth: Dp = 6.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 按压 → 阴影收回（压平）；松开 → 阴影弹回。
    val depth by animateDpAsState(
        targetValue = if (pressed) 0.dp else shadowDepth,
        animationSpec = ShadowFlatten,
        label = "posterShadowDepth"
    )
    Column(
        modifier = modifier
            // 下落量 = 阴影深度：压平时卡片底边正好落到阴影原本的位置，视觉上「拍平」。
            .graphicsLayer { translationY = depth.toPx() }
            .drawBehind {
                // 实心偏移阴影：在卡片正下方画一个同形圆角矩形，无模糊、硬边（大字报质感的关键）。
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(0f, depth.toPx()),
                    size = size,
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                )
            }
            .clip(shape)
            .background(pair.background)
            .border(3.dp, Color.Black, shape)
            .then(
                if (onClick != null) {
                    Modifier
                        .pressScale(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        content()
    }
}

/** 贴纸小徽标：白底黑框小方块（emoji / 短文案），贴在卡片角上的「贴纸」质感。 */
@Composable
fun StickerChip(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Color.White,
    ink: Color = Color.Black,
    rotate: Float = 0f,
) {
    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = rotate }
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink)
    }
}

// ---------------------------------------------------------------------------
// 超大数字 / 大字报排版
// ---------------------------------------------------------------------------

/**
 * 超大数字（大字报主角）：44–56sp 超粗字重 + 收紧行距。模板里手写「¥」字面量的地方
 * 传 [Money.formatPlain] 的结果，带符号金额一律先过 Money 工具（整数分契约）。
 */
@Composable
fun HugeNumberText(
    text: String,
    ink: Color,
    modifier: Modifier = Modifier,
    fontSize: Int = 52,
) {
    Text(
        text = text,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp,
        lineHeight = (fontSize + 4).sp,
        color = ink,
        modifier = modifier
    )
}

// ---------------------------------------------------------------------------
// 血条（逐格填充进度条）
// ---------------------------------------------------------------------------

/**
 * 血条式进度条：N 格逐格填充，填充比例用现有 [Motion.ChartDraw] 时长从 0 拉到目标值
 * （进场时先空条再涨满，「游戏血条」的仪式感）。2dp 黑框 + 圆角，与贴纸卡同一硬边语言。
 */
@Composable
fun BloodBar(
    fraction: Float,
    fillColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    segmentCount: Int = 24,
) {
    val target = fraction.coerceIn(0f, 1f)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) {
        // 目标变化时从 0 重涨：逐格填充的动效每次都给足反馈（进度本身就是页面主角）。
        progress.snapTo(0f)
        progress.animateTo(target, Motion.ChartDraw)
    }
    val shape = RoundedCornerShape(8.dp)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(shape)
            .border(2.dp, Color.Black, shape)
    ) {
        // 底槽
        drawRect(trackColor)
        val gap = 2.dp.toPx()
        val segmentWidth = (size.width - gap * (segmentCount - 1)) / segmentCount
        val filled = ceil(progress.value * segmentCount).toInt().coerceIn(0, segmentCount)
        for (i in 0 until filled) {
            drawRect(
                color = fillColor,
                topLeft = Offset(i * (segmentWidth + gap), 0f),
                size = androidx.compose.ui.geometry.Size(segmentWidth, size.height),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 弹跳进场
// ---------------------------------------------------------------------------

/**
 * spring 低阻尼弹跳进场：按 [order] 逐个错峰（每 60ms 一张），缩放 0.7 → 1 带轻微过冲。
 * 阻尼 0.45 = 明显弹一下但不失控；规格用 Compose spring（设计约束：不引第三方库）。
 */
fun Modifier.posterBounceEnter(order: Int): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order * 60L)
        progress.animateTo(
            1f,
            spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium)
        )
    }
    graphicsLayer {
        val p = progress.value
        scaleX = 0.7f + 0.3f * p
        scaleY = 0.7f + 0.3f * p
        alpha = p.coerceIn(0f, 1f)
    }
}

// ---------------------------------------------------------------------------
// 贴纸按钮 / 步进按钮
// ---------------------------------------------------------------------------

/** 贴纸按钮：撞色底 + 黑框 + 按压压平，挑战区内的操作按钮统一观感。 */
@Composable
fun StickerButton(
    text: String,
    pair: PosterPair,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth by animateDpAsState(if (pressed) 0.dp else 4.dp, ShadowFlatten, label = "stickerDepth")
    Box(
        modifier = modifier
            .graphicsLayer { translationY = depth.toPx() }
            .drawBehind {
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(0f, depth.toPx()),
                    size = size,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .clip(shape)
            .background(pair.background)
            .border(2.dp, Color.Black, shape)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = pair.ink)
    }
}

/** 海报步进按钮（±）：贴纸化方框，44dp 触控目标。 */
@Composable
fun PosterStepButton(symbol: String, pair: PosterPair, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(pair.background)
            .border(2.dp, Color.Black, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text = symbol, fontSize = 20.sp, fontWeight = FontWeight.Black, color = pair.ink)
    }
}

// ---------------------------------------------------------------------------
// 顶栏 / 吉祥物贴纸位
// ---------------------------------------------------------------------------

/** 挑战区二级页悬浮顶栏：返回 + 居中加粗标题（对齐既有 RinklTopBar，颜色走顶栏令牌）。 */
@Composable
fun PosterTopBar(
    title: String,
    onBack: () -> Unit,
    scrimAlpha: Float = 0f,
) {
    val textColor = LocalRinklColors.current.topBarTitleColor
    RinklTopBar(scrimAlpha = scrimAlpha, horizontalPadding = 8.dp) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(44.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * 小盘吉祥物贴纸位：hub 大字报右上角「探头」。使用 resource/二次元logo/ 现有位图
 * （drawable-nodpi/mascot_xiaopan.png），轻微旋转 + 压在海报边框上制造贴纸感。
 */
@Composable
fun MascotPeek(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Image(
        painter = painterResource(R.drawable.mascot_xiaopan),
        contentDescription = "小盘吉祥物",
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = -8f }
    )
}

// ---------------------------------------------------------------------------
// 解锁时刻全屏贴纸炸开
// ---------------------------------------------------------------------------

private const val BurstStickerCount = 14
private val BurstStickers = listOf("🎉", "⭐️", "💰", "🔥", "🏅", "🍬", "✨")

/**
 * 全屏贴纸炸开：解锁一枚徽章的瞬间，把一撮贴纸 emoji 从屏幕中心抛向四周再淡出。
 * [trigger] 从 false → true 时炸一次；动画结束自动回调 [onFinished]。
 * 纯 Compose Animatable 实现：每个贴纸一个随机方向的位移进度（同一 spring 规格）。
 */
@Composable
fun StickerBurstOverlay(
    trigger: Boolean,
    onFinished: () -> Unit,
) {
    // 每次触发重新洗一份随机方向（记住 seed 防重组期间抖动）。
    val seeds = remember(trigger) {
        List(BurstStickerCount) {
            BurstSeed(
                emoji = BurstStickers[Random.nextInt(BurstStickers.size)],
                angleDeg = Random.nextFloat() * 360f,
                distance = 0.35f + Random.nextFloat() * 0.3f, // 屏幕短边占比
                rotate = (Random.nextFloat() - 0.5f) * 120f,
            )
        }
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger) {
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
            delay(500)
            onFinished()
        }
    }
    AnimatedVisibility(
        visible = trigger,
        enter = fadeIn(animationSpec = Motion.Fade),
        exit = fadeOut(animationSpec = Motion.Fade)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            seeds.forEach { seed ->
                val rad = Math.toRadians(seed.angleDeg.toDouble())
                Text(
                    text = seed.emoji,
                    fontSize = 30.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            val t = progress.value
                            translationX = (cos(rad) * seed.distance * 900f * t).toFloat()
                            translationY = (sin(rad) * seed.distance * 900f * t).toFloat() + t * t * 220f
                            rotationZ = seed.rotate * t
                            alpha = (1f - t * 0.9f).coerceIn(0f, 1f)
                        }
                )
            }
        }
    }
}

private data class BurstSeed(
    val emoji: String,
    val angleDeg: Float,
    val distance: Float,
    val rotate: Float,
)
