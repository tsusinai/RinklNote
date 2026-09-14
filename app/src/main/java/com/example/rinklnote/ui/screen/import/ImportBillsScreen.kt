package com.example.rinklnote.ui.screen.importbills

/*
 * 注意：目录名为 `ui/screen/import/`，但 `import` 是 Kotlin 硬关键字、不能作包名段，
 * 因此包名取 `importbills`（Kotlin 不强制包名与目录一致）。路由 `bill-import` 由主会话接线。
 */

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.SettingsGroupCard
import com.example.rinklnote.ui.theme.DarkIncomeGreen
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
import java.time.Instant

/** 预览列表最多展示的可导入行数（超出部分给「另有 N 笔」提示）。 */
private const val PREVIEW_LIMIT = 50

/**
 * 「导入账单」整页（路由 `bill-import` 由主会话接线，非 tab 路由 → 底栏自动隐藏）。
 *
 * 三态流程：
 * ① 简介 + 「选择 CSV 文件」（ActivityResult OpenDocument）→
 * ② 预览（总数 / 可导入 / 重复跳过 / 无法识别及原因，账单预览最多 50 行）→「确认导入」→
 * ③ 完成（成功数 / 跳过数）+「完成」返回。
 *
 * 样式沿用 App 既有规范：玻璃卡（15dp 圆角 + rinkShadow + applyCardGlass，经
 * [SettingsGroupCard]）+ 悬浮顶栏（ArrowBack + 20sp Medium 标题）+ 14dp 水平边距。
 */
@Composable
fun ImportBillsScreen(
    viewModel: BillImportViewModel,
    onBack: () -> Unit,
    /** 自选背景照片的 uri（nav 层已整窗铺满时不再自绘背景）；null = 无照片背景。 */
    backgroundUri: String? = null,
    /** nav 层毛玻璃状态：无照片背景时作纯白底的 haze 源；为 null 时退化为纯色背景。 */
    hazeState: HazeState? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 文件选择器：文件管理器对 CSV 的 MIME 标注五花八门（text/csv、octet-stream、vnd.ms-excel
    // 皆有），过滤器收窄反而选不到，故放开为任意文件，格式问题交给解析阶段容错提示。
    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.onEvent(BillImportEvent.PickFile(uri))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景：有照片时由 nav 层整窗铺满；否则纯白底注册为毛玻璃源（与「自定义主题」页一致）。
        if (backgroundUri == null) {
            val hs = hazeState
            if (hs != null) {
                DefaultHazeBackground(hazeState = hs)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                )
            }
        }

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            ImportTopBar(
                hasBackground = backgroundUri != null,
                onBack = {
                    viewModel.onEvent(BillImportEvent.Reset)
                    onBack()
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.errorMessage != null) {
                    item(key = "error") { ErrorCard(state.errorMessage!!) }
                }
                when (state.phase) {
                    ImportPhase.IDLE -> {
                        item(key = "intro") { IntroCard() }
                        item(key = "pick") {
                            Button(
                                onClick = { pickFileLauncher.launch(arrayOf("*/*")) },
                                enabled = !state.parsing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (state.parsing) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("正在解析…", fontSize = 16.sp)
                                } else {
                                    Text("选择 CSV 文件", fontSize = 16.sp)
                                }
                            }
                        }
                    }

                    ImportPhase.PARSED -> {
                        item(key = "summary") { SummaryCard(state) }
                        if (state.importableRows.isNotEmpty()) {
                            item(key = "preview") { PreviewCard(state.importableRows) }
                        }
                        if (state.failedRows.isNotEmpty()) {
                            item(key = "failed") { FailedCard(state.failedRows) }
                        }
                        item(key = "actions") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.onEvent(BillImportEvent.Reset) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("重新选择", fontSize = 16.sp) }
                                Button(
                                    onClick = { viewModel.onEvent(BillImportEvent.ConfirmImport) },
                                    enabled = state.importableCount > 0,
                                    modifier = Modifier.weight(1f)
                                ) { Text("确认导入", fontSize = 16.sp) }
                            }
                        }
                    }

                    ImportPhase.IMPORTING -> {
                        item(key = "importing") { ImportingCard(state.importableCount) }
                    }

                    ImportPhase.DONE -> {
                        item(key = "done") { DoneCard(state) }
                        item(key = "finish") {
                            Button(
                                onClick = {
                                    viewModel.onEvent(BillImportEvent.Reset)
                                    onBack()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("完成", fontSize = 16.sp) }
                        }
                    }
                }
                item(key = "bottom-spacer") {
                    Spacer(modifier = Modifier.navigationBarsPadding().height(48.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

/** 悬浮顶栏：ArrowBack + 居中标题「导入账单」（20sp Medium），配色与「自定义主题」页一致。 */
@Composable
private fun ImportTopBar(hasBackground: Boolean, onBack: () -> Unit) {
    val textColor = if (hasBackground) Color.White else LocalRinklColors.current.topBarTitleColor
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = "导入账单",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

// ---------------------------------------------------------------------------
// ① 简介卡
// ---------------------------------------------------------------------------

@Composable
private fun IntroCard() {
    SettingsGroupCard(title = "如何导入") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            IntroLine("选择「记一笔」导出的 CSV 文件（rinklnote.csv），逐行核对后一次性导入。")
            RinklDivider(modifier = Modifier.padding(vertical = 4.dp))
            IntroLine("表格列：日期、类型、分类、子分类、金额、备注、来源；金额为两位小数的元。")
            IntroLine("分类 / 子分类按名称精确匹配（先子分类后一级），对不上的行会列在「无法识别」里。")
            IntroLine("同日期 + 同金额 + 同分类的账单视为重复，自动跳过（含账本里已有的）。")
            IntroLine("导入后按本地优先同步到服务端；没有账户列的行统一记入「无账户」。")
        }
    }
}

@Composable
private fun IntroLine(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

// ---------------------------------------------------------------------------
// ② 预览：汇总 / 账单预览 / 失败原因
// ---------------------------------------------------------------------------

@Composable
private fun SummaryCard(state: BillImportState) {
    SettingsGroupCard(title = state.fileName ?: "解析结果") {
        SummaryRow("共解析", "${state.totalCount} 行")
        RinklDivider(modifier = Modifier.padding(start = 16.dp))
        SummaryRow("可导入", "${state.importableCount} 条", valueColor = MaterialTheme.colorScheme.primary)
        RinklDivider(modifier = Modifier.padding(start = 16.dp))
        SummaryRow("重复跳过", "${state.duplicateCount} 条")
        RinklDivider(modifier = Modifier.padding(start = 16.dp))
        SummaryRow(
            "无法识别",
            "${state.failedCount} 条",
            valueColor = if (state.failedCount > 0) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 可导入账单预览（最多前 50 行）：日期 + 分类（/子分类）+ 备注 + 带符号金额。 */
@Composable
private fun PreviewCard(rows: List<ImportableRow>) {
    SettingsGroupCard(title = "账单预览") {
        rows.take(PREVIEW_LIMIT).forEachIndexed { index, row ->
            if (index > 0) RinklDivider(modifier = Modifier.padding(start = 16.dp))
            PreviewRow(row)
        }
        if (rows.size > PREVIEW_LIMIT) {
            RinklDivider(modifier = Modifier.padding(start = 16.dp))
            Text(
                text = "另有 ${rows.size - PREVIEW_LIMIT} 笔未展示，导入时全部包含",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun PreviewRow(row: ImportableRow) {
    val bill = row.bill
    val isExpense = bill.billType == BillType.EXPENSE
    val incomeGreen = if (isSystemInDarkTheme()) DarkIncomeGreen else IncomeGreen
    val remark = bill.remark
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = bill.categoryName + (bill.subCategoryName?.let { "/$it" } ?: ""),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = csvDateText(bill.date),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!remark.isNullOrBlank()) {
                Text(
                    text = remark,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            // 缓存金额格式化（重组时不重复计算）；金额为「分」，展示统一走 Money.format。
            text = (if (isExpense) "-" else "+") + Money.format(bill.amountMinor),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isExpense) MaterialTheme.colorScheme.tertiary else incomeGreen
        )
    }
}

/** 失败行原因列表：第 N 行 · 原因。 */
@Composable
private fun FailedCard(rows: List<FailedRow>) {
    SettingsGroupCard(title = "无法识别（${rows.size}）") {
        rows.forEachIndexed { index, row ->
            if (index > 0) RinklDivider(modifier = Modifier.padding(start = 16.dp))
            Text(
                text = "第 ${row.lineNo} 行 · ${row.reason}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ③ 导入中 / 完成
// ---------------------------------------------------------------------------

@Composable
private fun ImportingCard(count: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "正在导入 $count 笔账单…",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DoneCard(state: BillImportState) {
    SettingsGroupCard(title = "导入完成") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "成功导入 ${state.importedCount} 笔",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "重复跳过 ${state.duplicateCount} 条 · 无法识别 ${state.failedCount} 条",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 通用
// ---------------------------------------------------------------------------

@Composable
private fun ErrorCard(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/** 账单日期（当日 0 点时间戳）→ ISO 文本（yyyy-MM-dd，业务时区），便于导入核对。 */
private fun csvDateText(date: Long): String =
    Instant.ofEpochMilli(date).atZone(bookkeepingZone()).toLocalDate().toString()
