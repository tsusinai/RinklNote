package com.example.rinklnote.ui.screen.quickadd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 小票图片 → 文本（ML Kit 中文文本识别，bundled 端上模型）。
 *
 * 隐私口径：**图片全程留在本机**——拍照产物落 cache/ocr/，相册图只经 ContentResolver
 * 读字节流，识别在本端模型上完成，原图与识别原文都不上传、不落业务库。
 *
 * 流程：按目标边长采样解码（省内存）→ 按 EXIF 方向回正 → InputImage → 识别。
 * 识别失败（坏图 / 模型异常）返回 null，由调用方提示重试，不抛异常打断 UI。
 */
object ReceiptOcrRecognizer {

    /** 解码后长边上限（px）：识别精度足够（中文小票 1080px 起可读），再大只耗内存。 */
    private const val MAX_SIDE_PX = 1600

    suspend fun recognize(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = decodeUpright(context, uri) ?: return@withContext null
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        if (cont.isActive) cont.resume(text.text)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            }
        } catch (_: Exception) {
            null
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    /** 采样解码 + EXIF 回正；任何一步失败返回 null。 */
    private fun decodeUpright(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        // 第一遍：只读尺寸，算 inSampleSize
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // EXIF 方向回正（拍照产物常见旋转 90°）
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (rotation == 0f) return decoded
        val matrix = Matrix().apply { postRotate(rotation) }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright != decoded) decoded.recycle()
        return upright
    }
}
