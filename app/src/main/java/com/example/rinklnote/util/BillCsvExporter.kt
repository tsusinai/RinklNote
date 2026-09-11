package com.example.rinklnote.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.rinklnote.data.db.entity.Bill
import java.io.File
import java.time.Instant

/**
 * 把全部账单导出为 CSV 并唤起系统分享。
 *
 * - 头部写 UTF-8 BOM（`\uFEFF`），否则 Excel 打开中文会乱码。
 * - 每个字段加引号并转义内部引号（CSV 标准做法）。
 * - 文件落在 `cacheDir/exports/rinklnote.csv`，经 `FileProvider` 以只读方式交给分享目标。
 *
 * 2026-09-11 重构：从 ProfileScreen.kt 移出——纯数据导出逻辑，与「我的」页 UI 无关。
 */
fun exportBillsToCsv(context: Context, bills: List<Bill>) {
    val sb = StringBuilder("\uFEFF")
    sb.appendLine("日期,类型,分类,子分类,金额,备注,来源")
    val zone = bookkeepingZone()
    bills.forEach { b ->
        val date = Instant.ofEpochMilli(b.date).atZone(zone).toLocalDate().toString()
        sb.appendLine(
            listOf(date, b.billType, b.categoryName, b.subCategoryName ?: "", b.amount, b.remark ?: "", b.source)
                .joinToString(",") { "\"" + it.toString().replace("\"", "\"\"") + "\"" }
        )
    }
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "rinklnote.csv").apply { writeText(sb.toString(), Charsets.UTF_8) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "导出账单"))
    } catch (_: Exception) {
        Toast.makeText(context, "未找到可分享的应用", Toast.LENGTH_SHORT).show()
    }
}
