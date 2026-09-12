package com.example.rinklnote.ui.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** 键盘/金额长按的统一震动反馈，直接走系统 Vibrator，避免 Compose 轻触反馈被部分 ROM 吞掉。 */
class PressHaptics(context: Context) {
    private val vibrator: Vibrator? = run {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun tap() {
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(18L, 90)
        }
        vibrate(effect)
    }

    fun longPress() {
        val effect = VibrationEffect.createOneShot(32L, 150)
        vibrate(effect)
    }

    private fun vibrate(effect: VibrationEffect) {
        val device = vibrator ?: return
        if (device.hasVibrator()) device.vibrate(effect)
    }
}

@Composable
fun rememberPressHaptics(): PressHaptics {
    val context = LocalContext.current
    return remember(context) { PressHaptics(context) }
}
