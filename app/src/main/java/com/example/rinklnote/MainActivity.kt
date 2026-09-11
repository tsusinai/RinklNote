package com.example.rinklnote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.data.local.ThemeMode
import com.example.rinklnote.navigation.AppNavigation
import com.example.rinklnote.ui.theme.RinklNoteTheme
import com.example.rinklnote.ui.theme.RinklThemeSlot
import com.example.rinklnote.ui.theme.rinklColorsOf
import com.example.rinklnote.widget.RinklNoteAppWidgetReceiver.Companion.EXTRA_CATEGORY_ID
import com.example.rinklnote.widget.RinklNoteAppWidgetReceiver.Companion.EXTRA_OPEN_QUICK_ADD

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RinklNoteApp
        consumeLaunchIntent(app, intent)
        setContent {
            val themeMode by app.settingsManager.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 「自定义主题」的用户覆盖：5 个颜色槽读自 DataStore，未自定义的槽回落默认色。
            val customColors by app.settingsManager.customThemeColors
                .collectAsStateWithLifecycle(initialValue = emptyMap())
            val rinklColors = rinklColorsOf(
                dark = darkTheme,
                themeColor = customColors[RinklThemeSlot.PRIMARY],
                fontColor = customColors[RinklThemeSlot.FONT],
                topBarTitleColor = customColors[RinklThemeSlot.TOP_BAR],
                iconButtonColor = customColors[RinklThemeSlot.ICON],
                borderColor = customColors[RinklThemeSlot.BORDER]
            )
            RinklNoteTheme(darkTheme = darkTheme, rinklColors = rinklColors) {
                AppNavigation(app = app)
            }
        }
    }

    /** 小组件点分类携带 extra 打开时（singleTop）复用本实例，由 onNewIntent 交回。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeLaunchIntent(application as RinklNoteApp, intent)
    }

    private fun consumeLaunchIntent(app: RinklNoteApp, intent: Intent?) {
        app.setPendingQuickAdd(parseDeepLink(intent) ?: parseWidgetExtra(intent))
    }

    /** `rinklnote://add?amount=&category=&remark=&type=` → 预填参数；命中即返回（即使全缺省也打开抽屉）。 */
    private fun parseDeepLink(intent: Intent?): PendingQuickAdd? {
        val data = intent?.data ?: return null
        if (!data.scheme.equals("rinklnote", ignoreCase = true) || data.host != "add") return null
        return PendingQuickAdd(
            amount = data.getQueryParameter("amount")?.takeIf { it.isNotBlank() },
            categoryName = data.getQueryParameter("category")?.takeIf { it.isNotBlank() },
            remark = data.getQueryParameter("remark")?.takeIf { it.isNotBlank() },
            billType = data.getQueryParameter("type")?.takeIf { it.isNotBlank() }
        )
    }

    /** 主屏小组件点分类 → 预选分类 id；未指定分类则不打扰（返回 null，不开抽屉）。 */
    private fun parseWidgetExtra(intent: Intent?): PendingQuickAdd? {
        if (intent?.getBooleanExtra(EXTRA_OPEN_QUICK_ADD, false) == true) {
            val catId = if (intent.hasExtra(EXTRA_CATEGORY_ID)) intent.getLongExtra(EXTRA_CATEGORY_ID, -1L) else -1L
            return if (catId >= 0) PendingQuickAdd(categoryId = catId) else null
        }
        return null
    }
}
