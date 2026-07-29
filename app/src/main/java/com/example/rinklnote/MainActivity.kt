package com.example.rinklnote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.rinklnote.navigation.AppNavigation
import com.example.rinklnote.ui.theme.RinklNoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RinklNoteApp
        setContent {
            RinklNoteTheme {
                AppNavigation(app = app)
            }
        }
    }
}
