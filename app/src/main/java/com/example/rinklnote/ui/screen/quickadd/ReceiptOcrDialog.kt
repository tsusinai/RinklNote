package com.example.rinklnote.ui.screen.quickadd

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.rinklnote.util.Money
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 小票 OCR 二级流（快速记账抽屉「拍摄小票」chip 唤起）：
 * 拍照 / 相册选图 → ML Kit 端上识别 → 金额 / 日期 / 商家候选 chips → 点选预填。
 *
 * 隐私：图片只在本机识别（[ReceiptOcrRecognizer]），不上传；拍照产物落在
 * cache/ocr/ 临时文件，会话内被反复覆盖，不上传也不进业务库。
 *
 * 点选即回填（可多次点选、可跨类连选）：金额 → 抽屉金额框；日期 → 本笔记账日
 * （默认今天，识别到票面日期后可选）；商家 → 备注。确定后点「完成」关闭。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReceiptOcrDialog(
    onDismiss: () -> Unit,
    onPickAmount: (String) -> Unit,
    onPickDate: (LocalDate) -> Unit,
    onPickMerchant: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var recognizedText by remember { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // 已点选的候选值（跨类独立记录，驱动 chips 选中态）
    var pickedAmount by remember { mutableStateOf<String?>(null) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    var pickedMerchant by remember { mutableStateOf<String?>(null) }

    // 拍照产物：cache/ocr/receipt.jpg（会话内覆盖），经 FileProvider 出只读 Uri
    val cameraUri = remember {
        val dir = File(context.cacheDir, "ocr").apply { mkdirs() }
        val file = File(dir, "receipt.jpg")
        Uri.fromFile(file).let { _ ->
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }
    }

    fun runRecognize(uri: Uri) {
        processing = true
        failed = false
        scope.launch {
            val text = ReceiptOcrRecognizer.recognize(context, uri)
            processing = false
            if (text.isNullOrBlank()) {
                failed = true
                recognizedText = null
            } else {
                recognizedText = text
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) runRecognize(cameraUri) }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) runRecognize(uri) }

    val candidates = remember(recognizedText) {
        recognizedText?.let { ReceiptOcrParser.extract(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("小票识别", fontSize = 18.sp, fontWeight = FontWeight.Medium) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    processing -> {
                        Text(
                            "识别中…（图片仅在本机识别，不会上传）",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    }

                    candidates == null -> {
                        Text(
                            if (failed) "识别失败，请换张更清晰的照片再试"
                            else "拍一张小票，或在相册选一张截图，自动认出金额和日期。",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (failed) {
                            Text(
                                "小票字迹清晰、光线充足时识别更准。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        OcrCandidateGroup(
                            label = "金额",
                            chips = candidates.amounts.map {
                                Money.toYuanInputString(Money.yuanToMinor(it))
                            },
                            picked = pickedAmount,
                            onPick = { value ->
                                pickedAmount = value
                                onPickAmount(value)
                            }
                        )
                        OcrCandidateGroup(
                            label = "日期",
                            chips = candidates.dates.map { it.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) },
                            picked = pickedDate?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                            onPick = { value ->
                                val date = LocalDate.parse(value)
                                pickedDate = date
                                onPickDate(date)
                            }
                        )
                        OcrCandidateGroup(
                            label = "商家",
                            chips = candidates.merchants,
                            picked = pickedMerchant,
                            onPick = { value ->
                                pickedMerchant = value
                                onPickMerchant(value)
                            }
                        )
                        if (candidates.amounts.isEmpty() && candidates.dates.isEmpty()) {
                            Text(
                                "没认出金额或日期，可以直接关掉手输。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (candidates == null && !processing) {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { takePictureLauncher.launch(cameraUri) }) { Text("拍照") }
                    TextButton(onClick = {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) { Text("相册选图") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (candidates == null) "取消" else "完成") }
        }
    )
}

/** 单类候选组：标签 + 候选 chips（FlowRow 换行），点选回填并高亮选中态。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OcrCandidateGroup(
    label: String,
    chips: List<String>,
    picked: String?,
    onPick: (String) -> Unit
) {
    if (chips.isEmpty()) return
    Column {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            chips.forEach { chip ->
                val selected = picked == chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .clickable { onPick(chip) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = chip,
                        fontSize = 13.sp,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
