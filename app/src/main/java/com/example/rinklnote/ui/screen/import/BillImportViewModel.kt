package com.example.rinklnote.ui.screen.importbills

/*
 * 注意：目录名为 `ui/screen/import/`，但 `import` 是 Kotlin 硬关键字、不能作包名段，
 * 因此包名取 `importbills`（Kotlin 不强制包名与目录一致）。
 * 主会话接线 `bill-import` 路由时，请 import 本包下的类：
 *   import com.example.rinklnote.ui.screen.importbills.BillImportViewModel
 *   import com.example.rinklnote.ui.screen.importbills.ImportBillsScreen
 */

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.db.entity.isBucket
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.Source
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

// ---------------------------------------------------------------------------
// 状态与事件
// ---------------------------------------------------------------------------

/** 导入流程四态：未开始 → 已解析待确认 → 导入中 → 完成。 */
enum class ImportPhase { IDLE, PARSED, IMPORTING, DONE }

/** 预览列表里的一行可导入账单（bill 已装配好分类/账户 id、当日 0 点时间戳与整数分金额）。 */
@Immutable
data class ImportableRow(
    /** CSV 里的行号（含表头从 1 计，与文本编辑器里看到的一致）。 */
    val lineNo: Int,
    val bill: Bill
)

/** 无法识别行：行号 + 失败原因（分类/账户/日期/金额等）。 */
@Immutable
data class FailedRow(
    val lineNo: Int,
    val reason: String
)

/** 导入页状态（金额一律整数分）。 */
@Immutable
data class BillImportState(
    val phase: ImportPhase = ImportPhase.IDLE,
    /** IDLE 阶段读取/解析文件中（按钮转圈、防重复点击）。 */
    val parsing: Boolean = false,
    /** 所选文件名（读不到就空，UI 显示兜底文案）。 */
    val fileName: String? = null,
    /** CSV 数据行总数（不含表头与空行）。 */
    val totalCount: Int = 0,
    /** 可导入行（预览列表最多展示前 50 行，由 UI 截断）。 */
    val importableRows: List<ImportableRow> = emptyList(),
    /** 无法识别行及原因。 */
    val failedRows: List<FailedRow> = emptyList(),
    /** 重复跳过数（文件内重复 + 与账本已有账单重复）。 */
    val duplicateCount: Int = 0,
    /** 完成态：成功导入条数。 */
    val importedCount: Int = 0,
    /** 整页级错误（读文件失败 / 表头不合规 / 落库失败），非空时顶部展示错误卡。 */
    val errorMessage: String? = null
) {
    val importableCount: Int get() = importableRows.size
    val failedCount: Int get() = failedRows.size
    /** 完成态的「跳过数」= 重复 + 无法识别，与总数对账。 */
    val skippedCount: Int get() = duplicateCount + failedCount
}

sealed interface BillImportEvent {
    /** 选定 CSV 文件：IO 协程里读取并解析，产出预览。 */
    data class PickFile(val uri: Uri) : BillImportEvent

    /** 确认导入：可导入行整批落库（dirty=1，走 SyncManager 既有推送）。 */
    data object ConfirmImport : BillImportEvent

    /** 重置回「未开始」（清空解析结果与错误）。 */
    data object Reset : BillImportEvent
}

// ---------------------------------------------------------------------------
// CSV 列定义（与 util/BillCsvExporter 的导出格式完全对齐，保证导入导出可回环）
// ---------------------------------------------------------------------------

/**
 * CSV 列。导出器写出的表头是「日期,类型,分类,子分类,金额,备注,来源」；
 * 「账户」是宽松扩展列（导出器不写，手工 CSV 若带此列则按名匹配账户）。
 */
internal enum class CsvColumn(val header: String, val required: Boolean) {
    DATE("日期", true),
    TYPE("类型", false),
    CATEGORY("分类", true),
    SUB_CATEGORY("子分类", false),
    AMOUNT("金额", true),
    REMARK("备注", false),
    SOURCE("来源", false),
    ACCOUNT("账户", false)
}

/**
 * 表头 → 列序号映射。缺任一必需列返回 null（整份文件判为格式不符）。
 * 兼容首字符残留的 UTF-8 BOM 与单元格前后空白。
 */
internal fun mapHeader(headerFields: List<String>): Map<CsvColumn, Int>? {
    val cells = headerFields.map { it.trim().trimStart('\uFEFF') }
    val map = mutableMapOf<CsvColumn, Int>()
    for (col in CsvColumn.entries) {
        val index = cells.indexOfFirst { it == col.header }
        if (index >= 0) map[col] = index
    }
    val missing = CsvColumn.entries.filter { it.required && it !in map }
    return if (missing.isEmpty()) map else null
}

/** 纯解析结果（不依赖 Android），便于独立单测。 */
internal data class CsvParseResult(
    val importable: List<ImportableRow>,
    val failed: List<FailedRow>,
    /** 文件内重复 + 与账本重复的总数。 */
    val duplicates: Int,
    /** 数据行总数（不含表头与空行）。 */
    val totalCount: Int
)

/**
 * 把整份 CSV 文本拆成「记录」列表：按换行切分，但**引号内的换行保留**——
 * 导出器的「备注」字段被双引号包裹且可能含换行，按行硬切会把一条记录断成两截。
 */
internal fun splitCsvRecords(text: String): List<String> {
    val body = text.trimStart('\uFEFF')
    val records = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    for (c in body) {
        if (c == '"') inQuotes = !inQuotes
        if (!inQuotes && (c == '\n' || c == '\r')) {
            records.add(sb.toString())
            sb.clear()
        } else {
            sb.append(c)
        }
    }
    if (sb.isNotEmpty()) records.add(sb.toString())
    return records
}

/**
 * 单条记录 → 字段：支持双引号包裹与「""」转义（与 BillCsvExporter 的写法互逆）。
 */
internal fun splitCsvLine(record: String): List<String> {
    val fields = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < record.length) {
        val c = record[i]
        when {
            inQuotes -> when {
                c == '"' && i + 1 < record.length && record[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = false
                else -> sb.append(c)
            }
            c == '"' -> inQuotes = true
            c == ',' -> { fields.add(sb.toString()); sb.clear() }
            else -> sb.append(c)
        }
        i++
    }
    fields.add(sb.toString())
    return fields
}

/** 日期列解析：优先标准 ISO（与导出格式一致）；容忍手改的 yyyy-M-d / yyyy/M/d / yyyy.M.d。 */
internal fun parseCsvDate(text: String): LocalDate? {
    val s = text.trim()
    if (s.isEmpty()) return null
    return try {
        LocalDate.parse(s)
    } catch (_: Exception) {
        val m = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$""").find(s) ?: return null
        runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull()
    }
}

/**
 * 金额列解析 → 整数分。导出格式是「两位小数的元」（如 12.30 / -12.30），
 * 这里以 Money.parseMinor 收口（最多 2 位小数、纯数字），另容忍货币符号与千分位。
 */
internal fun parseCsvAmount(text: String): Long? {
    val s = text.trim().replace(",", "").replace("¥", "").replace("￥", "")
    if (s.isEmpty()) return null
    val negative = s.startsWith("-")
    val abs = s.trimStart('+', '-')
    if (abs.isEmpty()) return null
    val absMinor = Money.parseMinor(abs) ?: return null
    return if (negative) -absMinor else absMinor
}

/**
 * 类型列 → BillType：兼容导出格式（EXPENSE/INCOME，忽略大小写）与手写中文（支出/收入）；
 * 空或未知返回 null，由分类自身类型推断。
 */
internal fun parseCsvBillType(text: String): BillType? = when (text.trim().uppercase()) {
    "EXPENSE", "支出" -> BillType.EXPENSE
    "INCOME", "收入" -> BillType.INCOME
    else -> null
}

/**
 * 解析整份 CSV → 可导入行 + 失败行 + 重复数。纯函数，不依赖 Android。
 *
 * 容错约定：任何一行异常都不会让整体崩溃，只会记为失败行并给出原因。
 *
 * 重复判定（简化规则，有意为之）：**同日期 + 同金额（分）+ 同分类名** 即视为重复跳过——
 * 不比对备注/子分类/账户，宁可少导也不重复导。文件内相邻重复直接剔除；
 * 与账本已有的重复由调用方经 [removeExisting] 二次过滤。
 */
internal fun parseCsvBills(
    records: List<String>,
    columns: Map<CsvColumn, Int>,
    categories: List<Category>,
    subCategories: List<SubCategory>,
    accounts: List<Account>
): CsvParseResult {
    val catById = categories.associateBy { it.id }
    val catsByName = categories.groupBy { it.name }
    // 二级名 → 挂在可确认父级下的二级分类自身（同名二级可能挂在多个父级下，如「加油」同时在交通/汽车），
    // 匹配时用一级名收敛；一级名缺省则取第一个（按 id 序，确定性）。
    val subsByName: Map<String, List<Category>> = subCategories
        .mapNotNull { sub ->
            catById[sub.parentCategoryId]?.let { parent -> sub to parent }
        }
        .groupBy({ it.first.name }, { it.second })

    /** 兜底账户：缺省「默认」；老安装已把「默认」改名「无账户」桶，故再退到桶，最后退到首个账户。 */
    val defaultAccount = accounts.firstOrNull { it.name == "默认" }
        ?: accounts.firstOrNull { it.isBucket() }
        ?: accounts.firstOrNull()

    val importable = mutableListOf<ImportableRow>()
    val failed = mutableListOf<FailedRow>()
    val seenKeys = mutableSetOf<String>()
    var duplicates = 0
    var total = 0

    // 第 0 条是表头；行号含表头从 1 计，与用户在文本编辑器里看到的行号一致。
    for ((index, record) in records.withIndex()) {
        if (index == 0) continue
        val fields = splitCsvLine(record)
        if (fields.all { it.trim().isEmpty() }) continue // 空行不计
        total++
        val lineNo = index + 1

        fun cell(col: CsvColumn): String =
            columns[col]?.let { fields.getOrNull(it)?.trim().orEmpty() } ?: ""

        try {
            // ── 日期 ──
            val dateText = cell(CsvColumn.DATE)
            val date = parseCsvDate(dateText)
            if (date == null) {
                failed += FailedRow(lineNo, if (dateText.isEmpty()) "日期为空" else "日期无法识别：$dateText")
                continue
            }

            // ── 金额 ──
            val amountText = cell(CsvColumn.AMOUNT)
            val parsedAmount = parseCsvAmount(amountText)
            if (amountText.isEmpty()) {
                failed += FailedRow(lineNo, "金额为空")
                continue
            }
            if (parsedAmount == null) {
                failed += FailedRow(lineNo, "金额无法识别：$amountText")
                continue
            }
            if (parsedAmount == 0L) {
                failed += FailedRow(lineNo, "金额为 0，无法记账")
                continue
            }
            val amountMinor = parsedAmount

            // ── 分类：先按「子分类 + 一级名」匹配二级，再退到按一级名匹配 ──
            val catText = cell(CsvColumn.CATEGORY)
            val subText = cell(CsvColumn.SUB_CATEGORY)
            var matched: Category? = null
            var subName: String? = null
            if (subText.isNotEmpty()) {
                val parents = subsByName[subText].orEmpty()
                val narrowed = if (catText.isEmpty()) parents else parents.filter { it.name == catText }
                narrowed.firstOrNull()?.let { parent ->
                    matched = parent
                    subName = subText
                }
            }
            if (matched == null && catText.isNotEmpty()) {
                val candidates = catsByName[catText].orEmpty()
                val typeHint = parseCsvBillType(cell(CsvColumn.TYPE))
                // 同名分类可能支出/收入各一条：类型列能对上时优先按类型挑。
                matched = typeHint?.let { t -> candidates.firstOrNull { it.billType == t } }
                    ?: candidates.firstOrNull()
            }
            if (matched == null) {
                val name = listOf(catText, subText).filter { it.isNotEmpty() }.joinToString("/")
                    .ifEmpty { "（空）" }
                failed += FailedRow(lineNo, "分类无法识别：$name（需与「分类管理」里的名称一致）")
                continue
            }
            val category = matched!!

            // ── 账户 ──
            val accountText = cell(CsvColumn.ACCOUNT)
            val account: Account
            if (accountText.isNotEmpty()) {
                // 显式给了账户列：按名精确匹配，对不上算失败行（账户名问题要报告给用户）。
                val matchedAccount = accounts.firstOrNull { it.name == accountText }
                if (matchedAccount == null) {
                    failed += FailedRow(lineNo, "账户无法识别：$accountText")
                    continue
                }
                account = matchedAccount
            } else {
                // 导出格式没有账户列：统一落兜底账户（默认 → 无账户桶 → 首个账户）。
                val fallback = defaultAccount
                if (fallback == null) {
                    failed += FailedRow(lineNo, "库内无可用账户")
                    continue
                }
                account = fallback
            }

            // ── 类型：类型列仅作同名分类消歧；bill.billType 始终跟随分类自身类型，
            //    否则图表/预算/账户净额的收支口径会错位。──
            val remark = cell(CsvColumn.REMARK).ifBlank { null }
            val source = Source.entries.firstOrNull { it.value.equals(cell(CsvColumn.SOURCE), ignoreCase = true) }
                ?: Source.APP

            val bill = Bill(
                amountMinor = amountMinor,
                billType = category.billType,
                categoryId = category.id,
                categoryName = category.name,
                subCategoryName = subName,
                accountId = account.id,
                remark = remark,
                // 只存「当日 0 点」作为天分组键，与记账口径一致（业务时区 Asia/Shanghai）。
                date = date.atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli(),
                source = source
                // id/serverId/sortOrder/createdAt/updatedAt/dirty 由 BillRepository.importBills 统一盖章。
            )

            // 文件内查重：同日期 + 同金额 + 同分类名（简化规则见函数注释）。
            val key = "${bill.date}|${bill.amountMinor}|${bill.categoryName}"
            if (!seenKeys.add(key)) {
                duplicates++
                continue
            }
            importable += ImportableRow(lineNo, bill)
        } catch (e: Exception) {
            // 任何单行意外异常都不崩溃：整行计入失败并带原因。
            failed += FailedRow(lineNo, "解析异常：${e.message ?: "未知错误"}")
        }
    }
    return CsvParseResult(importable, failed, duplicates, total)
}

/**
 * 与账本查重：对候选行应用同一套简化规则（同日期 + 同金额 + 同分类名），
 * 命中账本已有账单即剔除。返回保留行与剔除数。
 */
internal fun removeExisting(
    candidates: List<ImportableRow>,
    existing: List<Bill>
): Pair<List<ImportableRow>, Int> {
    val keys = existing.mapTo(mutableSetOf()) { "${it.date}|${it.amountMinor}|${it.categoryName}" }
    val kept = mutableListOf<ImportableRow>()
    var removed = 0
    for (row in candidates) {
        val key = "${row.bill.date}|${row.bill.amountMinor}|${row.bill.categoryName}"
        // set.add 返回 false = 账本里已有同键账单 → 判为重复。
        if (keys.add(key)) kept += row else removed++
    }
    return kept to removed
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/**
 * CSV 导入账单 ViewModel：选文件（IO 解析）→ 预览确认 → 整批落库。
 * 落库走 [BillRepository.importBills]（事务批量插入，dirty=1），
 * 同步交给 SyncManager 既有推送逻辑，本 VM 不直接调 SyncManager。
 */
class BillImportViewModel(
    appContext: Context,
    private val repository: BillRepository
) : ViewModel() {

    private val contentResolver = appContext.contentResolver

    private val _state = MutableStateFlow(BillImportState())
    val state: StateFlow<BillImportState> = _state.asStateFlow()

    fun onEvent(event: BillImportEvent) {
        when (event) {
            is BillImportEvent.PickFile -> pickFile(event.uri)
            BillImportEvent.ConfirmImport -> confirmImport()
            BillImportEvent.Reset -> _state.value = BillImportState()
        }
    }

    /** 选定文件：IO 协程读取 + 解析 + 与账本查重，产出预览。 */
    private fun pickFile(uri: Uri) {
        val current = _state.value
        if (current.phase == ImportPhase.IMPORTING || current.parsing) return // 导入/解析中不接受新文件
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(parsing = true, errorMessage = null) }
            runCatching { parseAndDedup(uri) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            parsing = false,
                            phase = ImportPhase.PARSED,
                            fileName = result.fileName,
                            totalCount = result.parsed.totalCount,
                            importableRows = result.parsed.importable,
                            failedRows = result.parsed.failed,
                            duplicateCount = result.parsed.duplicates
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            parsing = false,
                            phase = ImportPhase.IDLE,
                            errorMessage = "读取文件失败：${e.message ?: "未知错误"}"
                        )
                    }
                }
        }
    }

    private data class ParsedFile(val fileName: String?, val parsed: CsvParseResult)

    /** 读取文本 → 表头校验 → 逐行解析 → 与账本查重。任何异常抛给调用方转成整页错误。 */
    private suspend fun parseAndDedup(uri: Uri): ParsedFile {
        val text = contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: throw IllegalStateException("无法读取所选文件")

        val records = splitCsvRecords(text)
        if (records.isEmpty()) throw IllegalStateException("文件为空")
        val columns = mapHeader(splitCsvLine(records[0]))
            ?: throw IllegalArgumentException("表头缺少必需列（日期/分类/金额），请使用「记一笔」导出的 CSV 格式")

        val parsed = parseCsvBills(
            records = records,
            columns = columns,
            categories = repository.getAllCategories(),
            subCategories = repository.getAllSubCategories(),
            accounts = repository.getActiveAccounts()
        )

        // 与账本查重：按候选日期取当天已有账单，套用同一套「同日+同额+同分类」简化规则。
        val dayMs = 24L * 60 * 60 * 1000
        val existing = parsed.importable
            .map { it.bill.date }
            .distinct()
            .flatMap { day -> repository.getBillsByDay(day, day + dayMs) }
        val (kept, dupWithDb) = removeExisting(parsed.importable, existing)

        return ParsedFile(
            fileName = queryDisplayName(uri),
            parsed = parsed.copy(importable = kept, duplicates = parsed.duplicates + dupWithDb)
        )
    }

    /** 确认导入：整批落库（仓库内盖章 dirty=1），成功后进完成态。 */
    private fun confirmImport() {
        val current = _state.value
        if (current.phase != ImportPhase.PARSED || current.importableRows.isEmpty()) return
        _state.update { it.copy(phase = ImportPhase.IMPORTING) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.importBills(current.importableRows.map { it.bill }) }
                .onSuccess { count ->
                    _state.update { it.copy(phase = ImportPhase.DONE, importedCount = count) }
                }
                .onFailure { e ->
                    // 落库失败回预览态，错误卡提示（事务回滚，不会出现半批脏数据）。
                    _state.update {
                        it.copy(
                            phase = ImportPhase.PARSED,
                            errorMessage = "导入失败：${e.message ?: "未知错误"}"
                        )
                    }
                }
        }
    }

    /** 读所选文件的显示名（失败忽略，UI 用兜底文案）。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** 主会话接线用：BillImportViewModel.Factory(context.applicationContext, app.repository)。 */
    class Factory(
        private val context: Context,
        private val repository: BillRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BillImportViewModel(context.applicationContext, repository) as T
    }
}
