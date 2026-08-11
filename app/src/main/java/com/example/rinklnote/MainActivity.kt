package com.example.rinklnote

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RinklNoteApp
        setContent {
            val themeMode by app.settingsManager.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            RinklNoteTheme(darkTheme = darkTheme) {
                AppNavigation(app = app)
            }
        }
    }
}
