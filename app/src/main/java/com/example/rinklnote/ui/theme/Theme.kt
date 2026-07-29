package com.example.rinklnote.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Blue80,
    onPrimary = Color.White,
    primaryContainer = Blue80.copy(alpha = 0.12f),
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
    error = ExpenseRed,
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Color.Black,
    primaryContainer = Blue80.copy(alpha = 0.2f),
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
    error = DarkExpenseRed,
    onError = Color.Black,
)

@Composable
fun RinklNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
