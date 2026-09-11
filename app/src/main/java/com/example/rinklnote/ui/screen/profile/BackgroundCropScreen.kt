package com.example.rinklnote.ui.screen.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 背景选择取景框（裁剪）：图库选图后先进本屏，用户在**屏幕比例**的取景框内
 * 拖动/双指缩放，确认后把框内区域裁剪成图并保存到内部存储。
 * 比例取屏幕宽高比——背景以 ContentScale.Crop 铺满全屏，裁剪结果与显示 1:1 对应。
 *
 * @param imageUri 图库选出的照片（content://，仅会话内可读，须当场裁剪落盘）
 * @param onConfirm 确认后回调，参数为裁剪图在内部存储的绝对路径
 * @param onCancel 取消（或读取失败）时回调
 */
@Composable
fun BackgroundCropScreen(
    imageUri: Uri,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 加载解码（含 EXIF 回正）后的位图；null 表示仍在加载或失败（用 loadFailed 区分）。
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            val decoded = decodeOrientedBitmap(context, imageUri, maxDim = 2048)
            bitmap = decoded
            loadFailed = decoded == null
        }
    }
    var saving by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val screenWpx = with(density) { maxWidth.toPx() }
        val screenHpx = with(density) { maxHeight.toPx() }
        val screenAspect = if (screenHpx > 0) screenWpx / screenHpx else 0.5f

        // 取景框与屏幕同比例：上下给操作栏留空、左右留边；任一方向不够时按另一方向回推。
        val marginV = with(density) { 116.dp.toPx() }
        val marginH = with(density) { 16.dp.toPx() }
        var frameW = (screenHpx - 2 * marginV) * screenAspect
        if (frameW > screenWpx - 2 * marginH) frameW = screenWpx - 2 * marginH
        val frameH = if (screenAspect > 0) frameW / screenAspect else screenHpx - 2 * marginV
        val frameLeft = (screenWpx - frameW) / 2f
        val frameTop = (screenHpx - frameH) / 2f
        val frameRect = Rect(frameLeft, frameTop, frameLeft + frameW, frameTop + frameH)

        val bmp = bitmap
        when {
            bmp != null -> {
                // 初始以「cover」铺满取景框；缩放/平移始终约束在框内。
                val baseScale = max(frameW / bmp.width, frameH / bmp.height)
                var scale by remember(bmp) { mutableFloatStateOf(baseScale) }
                var offset by remember(bmp) { mutableStateOf(Offset.Zero) }

                fun clampOffset(s: Float, o: Offset): Offset {
                    val dispW = bmp.width * s
                    val dispH = bmp.height * s
                    val maxX = max(0f, (dispW - frameW) / 2f)
                    val maxY = max(0f, (dispH - frameH) / 2f)
                    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                }

                // 图片绘制层：drawImage 按目标矩形缩放，与裁剪计算共用同一套坐标。
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bmp) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(baseScale, baseScale * 8f)
                                offset = clampOffset(scale, offset + pan)
                            }
                        }
                ) {
                    val dispW = bmp.width * scale
                    val dispH = bmp.height * scale
                    val imageLeft = frameLeft + frameW / 2f + offset.x - dispW / 2f
                    val imageTop = frameTop + frameH / 2f + offset.y - dispH / 2f
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = IntOffset(imageLeft.roundToInt(), imageTop.roundToInt()),
                        dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt())
                    )
                }

                // 暗色遮罩挖孔：整屏半透明黑，框内 BlendMode.Clear 挖空（offscreen 保证混合在层内完成）。
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                ) {
                    drawRect(color = Color.Black.copy(alpha = 0.62f), size = size)
                    drawRect(
                        color = Color.Black,
                        topLeft = frameRect.topLeft,
                        size = frameRect.size,
                        blendMode = BlendMode.Clear
                    )
                }

                // 取景框标：四角 L 形白色括角 + 淡三分线。
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val corner = 28.dp.toPx()
                    val stroke = 2.5.dp.toPx()
                    val inset = stroke / 2f
                    val c = Color.White
                    fun bracket(hx: Boolean, hy: Boolean) {
                        val px = if (hx) frameRect.right else frameRect.left
                        val py = if (hy) frameRect.bottom else frameRect.top
                        val sx = if (hx) -1f else 1f
                        val sy = if (hy) -1f else 1f
                        drawLine(
                            c,
                            Offset(px + sx * inset, py + sy * corner),
                            Offset(px + sx * inset, py + sy * inset),
                            stroke,
                            StrokeCap.Round
                        )
                        drawLine(
                            c,
                            Offset(px + sx * inset, py + sy * inset),
                            Offset(px + sx * corner, py + sy * inset),
                            stroke,
                            StrokeCap.Round
                        )
                    }
                    bracket(false, false); bracket(true, false)
                    bracket(false, true); bracket(true, true)
                    val grid = Color.White.copy(alpha = 0.18f)
                    for (i in 1..2) {
                        val fx = frameRect.left + frameW * i / 3f
                        val fy = frameRect.top + frameH * i / 3f
                        drawLine(grid, Offset(fx, frameRect.top), Offset(fx, frameRect.bottom), 1f)
                        drawLine(grid, Offset(frameRect.left, fy), Offset(frameRect.right, fy), 1f)
                    }
                }

                // 顶部操作栏：取消 / 标题 / 确认。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, start = 8.dp, end = 8.dp)
                ) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) { Text("取消", color = Color.White) }
                    Text(
                        "调整背景位置",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    Button(
                        onClick = {
                            if (saving) return@Button
                            saving = true
                            scope.launch {
                                val path = withContext(Dispatchers.IO) {
                                    cropAndSave(context, bmp, scale, offset, frameRect)
                                        ?: saveFallback(context, imageUri)
                                }
                                saving = false
                                if (path != null) onConfirm(path) else onCancel()
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) { Text(if (saving) "保存中…" else "确认") }
                }

                if (saving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = Color.White) }
                }
            }

            loadFailed -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(300.dp))
                    Text("图片读取失败，请换一张重试", color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onCancel) { Text("返回", color = Color.White) }
                }
            }

            else -> {
                // 加载中。
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

/** 兜底：裁剪失败时把原图解码后直接落盘（等同于旧的直接拷贝行为）；失败返回 null。 */
private fun saveFallback(context: Context, uri: Uri): String? {
    return try {
        val bmp = decodeOrientedBitmap(context, uri, maxDim = 4096) ?: return null
        val dir = java.io.File(context.filesDir, "backgrounds").apply { mkdirs() }
        val file = java.io.File(dir, "bg_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}

/** 把取景框内区域按当前变换裁剪成图并保存 JPEG；失败返回 null。 */private fun cropAndSave(
    context: Context,
    bmp: Bitmap,
    scale: Float,
    offset: Offset,
    frameRect: Rect
): String? {
    return try {
        val dispW = bmp.width * scale
        val dispH = bmp.height * scale
        val imageLeft = frameRect.center.x + offset.x - dispW / 2f
        val imageTop = frameRect.center.y + offset.y - dispH / 2f
        // 框矩形 → 源位图坐标（除以总缩放），并夹回位图范围。
        val srcLeft = (max(0f, (frameRect.left - imageLeft) / scale)).roundToInt()
        val srcTop = (max(0f, (frameRect.top - imageTop) / scale)).roundToInt()
        val srcRight = (min(bmp.width.toFloat(), (frameRect.right - imageLeft) / scale)).roundToInt()
        val srcBottom = (min(bmp.height.toFloat(), (frameRect.bottom - imageTop) / scale)).roundToInt()
        val w = srcRight - srcLeft
        val h = srcBottom - srcTop
        if (w <= 0 || h <= 0) return null
        val cropped = Bitmap.createBitmap(bmp, srcLeft, srcTop, w, h)
        val dir = java.io.File(context.filesDir, "backgrounds").apply { mkdirs() }
        val file = java.io.File(dir, "bg_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}

/** 解码照片并按 EXIF 回正、限制最大边长（防止 OOM）；失败返回 null。 */
private fun decodeOrientedBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    return try {
        // 1) 只读边界求采样率。注意：inJustDecodeBounds=true 时 decodeStream 恒返回 null（仅填充
        //    outWidth/outHeight），不能用其返回值判失败；以 outWidth 有效性为准。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        // 2) 实际解码
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        // 3) EXIF 回正（Coil 显示背景时会处理 EXIF，这里必须保持一致）
        val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation == 0f) raw
        else Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height,
            Matrix().apply { postRotate(rotation) }, true
        )
    } catch (_: Exception) {
        null
    }
}
