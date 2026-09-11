package com.example.rinklnote.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Blue80,
    onPrimary = Color.White,
    primaryContainer = Blue80.copy(alpha = 0.12f),
    onPrimaryContainer = Color(0xFF0B3B66),
    secondary = IconGray,
    onSecondary = Color.White,
    tertiary = ExpenseRed,
    onTertiary = Color.White,
    background = BackgroundLight,
    onBackground = Color.Black,
    surface = SurfaceWhite,
    onSurface = Color.Black,
    surfaceVariant = ChartSummaryBg,
    onSurfaceVariant = IconGray,
    outline = RadioBorder,
    outlineVariant = RadioBorder,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF1F3F5),
    surfaceContainerHigh = Color(0xFFEBEDEF),
    surfaceContainerHighest = Color(0xFFE5E7E9),
    error = ExpenseRed,
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Color.Black,
    primaryContainer = Blue80.copy(alpha = 0.2f),
    onPrimaryContainer = Color.White,
    secondary = DarkOnSurfaceVariant,
    onSecondary = Color.Black,
    tertiary = DarkExpenseRed,
    onTertiary = Color.Black,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = Color(0xFF444444),
    outlineVariant = Color(0xFF333333),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF17181A),
    surfaceContainer = Color(0xFF1D1E21),
    surfaceContainerHigh = Color(0xFF232428),
    surfaceContainerHighest = Color(0xFF292A2E),
    error = DarkExpenseRed,
    onError = Color.Black,
)


private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun RinklNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    rinklColors: RinklColors = defaultRinklColors(darkTheme),
    content: @Composable () -> Unit
) {
    // 「自定义主题」的用户覆盖叠在基础色板之上：只改被用户改过的那几个角色，
    // 其余（语义红/绿、容器色、surface 层级）保持 App 既有观感。
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = baseScheme.copy(
        primary = rinklColors.themeColor,
        primaryContainer = rinklColors.themeColor.copy(alpha = if (darkTheme) 0.20f else 0.12f),
        // 字体颜色 → onSurface/onBackground：这两者覆盖了全 App 绝大多数正文与标题。
        onSurface = rinklColors.fontColor,
        onBackground = rinklColors.fontColor,
        // 次级文字/说明文字/次级图标 → 字体色降透明度。全 App 约 80 处用的是 onSurfaceVariant，
        // 在这里统一收口，用户改「字体颜色」时说明文字才会跟着变（否则永远停在 IconGray 灰）。
        onSurfaceVariant = rinklColors.fontColor.copy(alpha = 0.62f),
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalRinklColors provides rinklColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}
