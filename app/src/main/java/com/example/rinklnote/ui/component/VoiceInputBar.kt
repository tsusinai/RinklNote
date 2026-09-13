package com.example.rinklnote.ui.component

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.R
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.util.VoiceParser
import com.example.rinklnote.util.VoiceRecorder
import com.example.rinklnote.util.VoiceResult
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

private enum class VoicePhase { Listening, Processing, Error }

/**
 * Bottom floating mini voice bar — the "third page" is gone.
 * Flow: on device SpeechRecognizer is started immediately and streams live partial
 * text (real-time feedback) while speaking; the transcript is also parsed locally
 * (VoiceParser) for a live amount/category preview. When the device recognizer gives
 * a final result it is handed straight to the NLP fill. Only when the device recognizer
 * fails / returns nothing is the recording (cached in parallel by VoiceRecorder)
 * uploaded to the server Whisper endpoint as a fallback.
 */
@Composable
fun VoiceInputBar(
    api: ApiService?,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
    continuous: Boolean = false
) {
    val context = LocalContext.current.applicationContext
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    val recorder = remember { VoiceRecorder(context) }
    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(VoicePhase.Listening) }
    var liveText by remember { mutableStateOf("") }
    var fallbackAudio by remember { mutableStateOf(false) }
    // Guards against double delivery (e.g. onError after stopListening).
    var ended by remember { mutableStateOf(false) }
    // 连续多笔：每记一笔 +1，驱动 LaunchedEffect 重新聆听下一笔（避免本地函数前向引用）。
    var recognitionRound by remember { mutableStateOf(0) }

    fun finishWithText(text: String) {
        if (ended) return
        // 连续多笔：交结果给父级后重新聆听下一笔，不关闭；单笔则结束。
        currentOnResult(text)
        if (continuous) {
            ended = false
            recorder.cancel()
            recognitionRound += 1
        } else {
            ended = true
            recorder.cancel() // device result is good enough — discard fallback audio
        }
    }

    fun showError() {
        phase = VoicePhase.Error
        liveText = ""
    }

    fun startServerFallback() {
        if (ended) return
        ended = true
        if (!fallbackAudio) { showError(); return }
        val file = if (recorder.stop()) recorder.outputFile else null
        if (file == null || file.length() == 0L) { showError(); return }
        phase = VoicePhase.Processing
        liveText = ""
        scope.launch {
            val text = transcribeViaServer(api, file)
            file.delete()
            if (!text.isNullOrBlank()) {
                currentOnResult(text)
                if (continuous) {
                    ended = false
                    recognitionRound += 1
                }
            } else showError()
        }
    }

    fun startListen() {
        ended = false
        phase = VoicePhase.Listening
        liveText = ""
        fallbackAudio = recorder.start()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = startServerFallback()
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) startServerFallback() else finishWithText(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                liveText = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            recognizer.startListening(voiceIntent())
        } catch (e: Exception) {
            startServerFallback()
        }
    }

    // Start listening the moment the bar appears — no upload wait, instant feedback.
    LaunchedEffect(Unit) { startListen() }

    // 连续多笔：每记一笔后自动回到聆听，等待下一句。
    LaunchedEffect(recognitionRound) {
        if (recognitionRound > 0) startListen()
    }

    fun onMicTap() {
        when (phase) {
            VoicePhase.Listening -> { phase = VoicePhase.Processing; recognizer.stopListening() }
            VoicePhase.Error -> startListen()
            VoicePhase.Processing -> {}
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { recognizer.cancel() } catch (_: Exception) {}
            try { recognizer.destroy() } catch (_: Exception) {}
            recorder.cancel()
        }
    }

    BackHandler {
        ended = true
        currentOnDismiss()
    }

    val preview = remember(liveText) { VoiceParser.parse(liveText) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 56.dp) // clear the 32dp bottom bar + 8dp margin
            .rinkShadow(RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mic button — tap to end listening, tap again on error to retry
        val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(Motion.DurationBreath), RepeatMode.Reverse),
            label = "pulse"
        )
        val micBg = when (phase) {
            VoicePhase.Listening -> MaterialTheme.colorScheme.primary
            VoicePhase.Processing -> MaterialTheme.colorScheme.surfaceVariant
            VoicePhase.Error -> MaterialTheme.colorScheme.error
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    if (phase == VoicePhase.Listening) {
                        scaleX = pulse
                        scaleY = pulse
                    }
                }
                .clip(CircleShape)
                .background(micBg)
                .clickable(enabled = phase != VoicePhase.Processing) { onMicTap() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = "语音输入",
                tint = if (phase == VoicePhase.Processing) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (phase) {
                    VoicePhase.Listening -> liveText.ifBlank { "正在聆听..." }
                    VoicePhase.Processing -> "正在识别..."
                    VoicePhase.Error -> "未识别到内容，点麦克风重试"
                },
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (phase == VoicePhase.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            if (phase == VoicePhase.Listening && liveText.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = previewText(preview),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "✕",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(8.dp)
                .clickable {
                    ended = true
                    currentOnDismiss()
                }
        )
    }
}

private fun voiceIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
}

private fun previewText(r: VoiceResult): String {
    val parts = mutableListOf<String>()
    r.amount?.let { parts.add("¥" + fmtAmount(it)) }
    r.categoryName?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

private fun fmtAmount(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

private suspend fun transcribeViaServer(api: ApiService?, file: File): String? {
    if (api == null) return null
    return try {
        val part = MultipartBody.Part.createFormData(
            "file",
            file.name,
            file.asRequestBody("audio/mp4".toMediaType())
        )
        val resp = api.transcribe(part)
        if (resp.available && resp.text.isNotBlank()) resp.text else null
    } catch (e: Exception) {
        null
    }
}
