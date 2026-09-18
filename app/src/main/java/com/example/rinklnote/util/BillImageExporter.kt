package com.example.rinklnote.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

/**
 * 分享长图里的一行账单：与页面账单行同构（分类 / 备注 / 金额 / 收支方向）。
 * [amountMinor] 一律为「分」，展示由导出器统一走 [Money.format]，不在调用方拼金额字符串。
 */
data class ShareRow(
    val categoryName: String,
    val remark: String?,
    val amountMinor: Long,
    val isExpense: Boolean
)

/**
 * 当天账单分享长图导出器（纯函数、无 Compose 依赖，可在任意线程调用）。
 *
 * 实现要点：
 * - **不截屏**（Compose/WebView 截屏会受主题与暗色模式影响），而是 `Bitmap.createBitmap` +
 *   Canvas/Paint 手绘：浅灰页面底 + 白色圆角卡，顶部日期标题与笔数、逐行「分类 · 备注 · 金额」、
 *   底部支出/收入/结余合计，页脚品牌「RinklNote 记一笔」。
 * - 颜色为**固定深色系**（不读应用主题），分享到 QQ / 微信后观感恒定；收支两色取
 *   应用默认令牌同值（ExpenseRed #CA3032 / IncomeGreen #04A433）保持视觉延续。
 * - **尺寸 / 密度策略**：画布固定宽 1080px（≈360dp 设计稿 × 3 倍密度），与设备屏幕无关，
 *   各机型输出一致、高分屏足够清晰；高度按内容动态累加，上限 12000px，超限自动截断明细行
 *   并附「另有 N 笔未展示」提示——最坏 1080×12000×4 ≈ 50MB 仅在导出瞬间占用，落盘后立即 recycle。
 * - 金额全部走 [Money]（分 → 「¥1,234.56」，是否带 ¥ 跟随应用内货币符号开关），本文件不手写 ¥。
 * - 输出 PNG 到 `cacheDir/share/`，经 FileProvider（authority = `${applicationId}.fileprovider`，
 *   对应 res/xml/file_paths.xml 的 cache-path `share/`）返回 content:// Uri；失败（含 OOM）返回 null。
 */
object BillImageExporter {

    /** 画布固定宽（px）：≈ 360dp 设计稿 × 3 倍密度，不随设备变化。 */
    private const val CANVAS_WIDTH = 1080

    /** 1dp 对应像素数（= CANVAS_WIDTH / 360），所有尺寸先按 dp 设计再乘它。 */
    private const val PX_PER_DP = CANVAS_WIDTH / 360f

    /** 位图高度上限（px）：超限截断明细行，防极端长图 OOM。 */
    private const val MAX_CANVAS_HEIGHT = 12_000

    /** 明细行展示上限：单日再多也只画前 100 笔，其余以提示行代替。 */
    private const val MAX_ROWS = 100

    // ── 固定深色系配色（分享图不受主题 / 暗色模式影响） ──
    private val PAGE_BG = 0xFFF2F3F5.toInt()      // 页面底：浅灰，衬白色圆角卡
    private val CARD_BG = 0xFFFFFFFF.toInt()      // 白色圆角卡
    private val TITLE_DARK = 0xFF16181C.toInt()   // 标题 / 结余：近黑
    private val BODY_DARK = 0xFF2B2F36.toInt()    // 正文：分类名 / 合计金额
    private val TEXT_GRAY = 0xFF878E99.toInt()    // 次要字：备注 / 标签 / 页脚
    private val LINE_GRAY = 0xFFE8EAED.toInt()    // 分割线：浅发丝灰
    private val EXPENSE_RED = 0xFFCA3032.toInt()  // 支出红（与应用默认 ExpenseRed 同值）
    private val INCOME_GREEN = 0xFF04A433.toInt() // 收入绿（与应用默认 IncomeGreen 同值）

    /** 卡片圆角 / 水平外边距 / 卡内水平内边距（dp）。 */
    private const val CARD_RADIUS_DP = 16f
    private const val SIDE_MARGIN_DP = 16f
    private const val CARD_PAD_H_DP = 20f

    /**
     * 导出当天账单分享长图。
     *
     * @param context 任意 Context（只用 cacheDir 与 FileProvider）
     * @param title 顶部标题（如「9月15日 账单」，由调用方格式化好传入）
     * @param rows 明细行（调用方保证 [ShareRow.amountMinor] 为分）
     * @param totalExpenseMinor 支出合计（分，正数）
     * @param totalIncomeMinor 收入合计（分，正数）
     * @return content:// Uri；失败（IO / OOM 等）返回 null
     */
    fun export(
        context: Context,
        title: String,
        rows: List<ShareRow>,
        totalExpenseMinor: Long,
        totalIncomeMinor: Long
    ): Uri? {
        return try {
            exportInternal(context, title, rows, totalExpenseMinor, totalIncomeMinor)
        } catch (e: OutOfMemoryError) {
            // 极端长图内存不足：宁可放弃本次分享也不让页面崩溃
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun exportInternal(
        context: Context,
        title: String,
        rows: List<ShareRow>,
        totalExpenseMinor: Long,
        totalIncomeMinor: Long
    ): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // 只保留最新一张，缓存目录不累积
        dir.listFiles()?.forEach { it.delete() }

        // 第一遍：纯度量（不建位图），确定总高度、卡片底边与每行位置
        val layout = measureLayout(title, rows)

        // 第二遍：按度量结果实际绘制
        val bitmap = Bitmap.createBitmap(CANVAS_WIDTH, layout.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawLayout(canvas, layout, title, totalExpenseMinor, totalIncomeMinor)

        val stamp = LocalDateTime.now(bookkeepingZone()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "rinklnote-day-$stamp.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    // ===========================================================================
    // 年度账单分享图（Task 2.7）：12 月热力格 + 年总收支 + Top5 分类 + 小盘贺词。
    // 版式与日图同骨架（固定 1080px 宽、固定深色系、量后画两遍），内容量固定
    // （12 格 + 最多 5 行），总高度有界，无需截断逻辑。
    // ===========================================================================

    /** 年度版式的度量结果：所有 y 坐标一次算好，绘制阶段只查表。 */
    private class AnnualLayout(
        val heightPx: Int,
        val cardBottom: Float,
        val titleTop: Float,
        val subtitleTop: Float,
        val headDividerY: Float,
        val totalsTop: Float,
        val totalsDividerY: Float,
        val heatLabelTop: Float,
        val heatTop: Float,
        val heatCellHeight: Float,
        val heatDividerY: Float,
        val topLabelTop: Float,
        val topRowTops: List<Float>,
        val topRowBarWidths: List<Float>,
        val greetingTop: Float,
        val footerTop: Float
    )

    /**
     * 导出年度账单分享图。
     *
     * @param context 任意 Context（只用 cacheDir 与 FileProvider）
     * @param year 年份（标题用）
     * @param stats 年度聚合数据（调用方用 [aggregateAnnualStats] 生成，金额一律分）
     * @return content:// Uri；失败（IO / OOM 等）返回 null
     */
    fun exportAnnual(context: Context, year: Int, stats: AnnualStats): Uri? {
        return try {
            exportAnnualInternal(context, year, stats)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun exportAnnualInternal(context: Context, year: Int, stats: AnnualStats): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }

        val layout = measureAnnualLayout(stats)
        val bitmap = Bitmap.createBitmap(CANVAS_WIDTH, layout.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawAnnualLayout(canvas, layout, year, stats)

        val stamp = LocalDateTime.now(bookkeepingZone()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "rinklnote-year-$year-$stamp.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    private fun measureAnnualLayout(stats: AnnualStats): AnnualLayout {
        val titleP = textPaint(24f, TITLE_DARK, bold = true)
        val subtitleP = textPaint(13f, TEXT_GRAY)
        val labelP = textPaint(15f, TITLE_DARK, bold = true)
        val statLabelP = textPaint(13f, TEXT_GRAY)
        val statValueP = textPaint(18f, BODY_DARK, bold = true)
        val monthP = textPaint(11f, BODY_DARK)
        val monthAmountP = textPaint(9f, BODY_DARK)
        val topNameP = textPaint(14f, BODY_DARK)
        val topAmountP = textPaint(14f, BODY_DARK, bold = true)
        val greetingP = textPaint(14f, BODY_DARK)
        val footerP = textPaint(12f, TEXT_GRAY)

        val hairline = 1f
        val cardTop = 20f * PX_PER_DP
        val cardBottomPad = 24f * PX_PER_DP
        val footerGap = 14f * PX_PER_DP
        val pageBottomPad = 24f * PX_PER_DP
        val contentWidth = CANVAS_WIDTH - 2 * (SIDE_MARGIN_DP + CARD_PAD_H_DP) * PX_PER_DP

        // 热力格：3 行 × 4 列，格宽 = (内容宽 − 3×间距) / 4，格高 64dp
        val heatGap = 10f * PX_PER_DP
        val heatCellW = (contentWidth - 3 * heatGap) / 4f
        val heatCellH = 64f * PX_PER_DP
        // Top5 行：行高 30dp，条形图最大宽 = 内容宽的 45%
        val topRowH = 30f * PX_PER_DP
        val topMaxBar = contentWidth * 0.45f
        val top1 = stats.topCategories.firstOrNull()?.amountMinor ?: 0L
        val topBarWidths = stats.topCategories.map { cat ->
            if (top1 <= 0L) 0f else (cat.amountMinor.toFloat() / top1) * topMaxBar
        }

        var y = cardTop + 24f * PX_PER_DP
        val titleTop = y
        y += lineH(titleP) + 4f * PX_PER_DP
        val subtitleTop = y
        y += lineH(subtitleP) + 16f * PX_PER_DP
        val headDividerY = y + 0.5f
        y += hairline + 16f * PX_PER_DP

        // 年总收支：三列（支出/收入/结余），列高 = 标签行 + 值行
        val totalsTop = y
        y += lineH(statLabelP) + 6f * PX_PER_DP + lineH(statValueP) + 16f * PX_PER_DP
        val totalsDividerY = y + 0.5f
        y += hairline + 16f * PX_PER_DP

        // 12 月热力格
        val heatLabelTop = y
        y += lineH(labelP) + 12f * PX_PER_DP
        val heatTop = y
        y += 3 * heatCellH + 2 * heatGap + 16f * PX_PER_DP
        val heatDividerY = y + 0.5f
        y += hairline + 16f * PX_PER_DP

        // Top5 分类
        val topLabelTop = y
        y += lineH(labelP) + 8f * PX_PER_DP
        val topRowTops = stats.topCategories.map { rowTop ->
            val t = y
            y += topRowH
            t
        }
        y += if (stats.topCategories.isEmpty()) 0f else 8f * PX_PER_DP

        // 小盘贺词（居中）
        y += 12f * PX_PER_DP
        val greetingTop = y
        y += lineH(greetingP) + cardBottomPad
        val cardBottom = y
        val footerTop = cardBottom + footerGap
        val heightPx = (footerTop + lineH(footerP) + pageBottomPad).toInt()

        return AnnualLayout(
            heightPx = heightPx,
            cardBottom = cardBottom,
            titleTop = titleTop,
            subtitleTop = subtitleTop,
            headDividerY = headDividerY,
            totalsTop = totalsTop,
            totalsDividerY = totalsDividerY,
            heatLabelTop = heatLabelTop,
            heatTop = heatTop,
            heatCellHeight = heatCellH,
            heatDividerY = heatDividerY,
            topLabelTop = topLabelTop,
            topRowTops = topRowTops,
            topRowBarWidths = topBarWidths,
            greetingTop = greetingTop,
            footerTop = footerTop
        )
    }

    private fun drawAnnualLayout(canvas: Canvas, layout: AnnualLayout, year: Int, stats: AnnualStats) {
        val sideMargin = SIDE_MARGIN_DP * PX_PER_DP
        val contentLeft = sideMargin + CARD_PAD_H_DP * PX_PER_DP
        val contentRight = CANVAS_WIDTH - sideMargin - CARD_PAD_H_DP * PX_PER_DP
        val contentWidth = contentRight - contentLeft

        // 页面底 + 白色圆角卡
        canvas.drawColor(PAGE_BG)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_BG }
        canvas.drawRoundRect(
            RectF(sideMargin, 20f * PX_PER_DP, CANVAS_WIDTH - sideMargin, layout.cardBottom),
            CARD_RADIUS_DP * PX_PER_DP,
            CARD_RADIUS_DP * PX_PER_DP,
            cardPaint
        )

        // 1px 发丝分割线
        val hair = Paint().apply { color = LINE_GRAY; strokeWidth = 1f; isAntiAlias = false }
        fun hairline(y: Float) = canvas.drawLine(contentLeft, y, contentRight, y, hair)

        // 卡头：标题 + 副标题
        val titleP = textPaint(24f, TITLE_DARK, bold = true)
        canvas.drawText("${year} 年度账单", contentLeft, baseline(layout.titleTop, titleP), titleP)
        val subtitleP = textPaint(13f, TEXT_GRAY)
        canvas.drawText("RinklNote 记一笔 · 小盘陪你回顾这一年", contentLeft, baseline(layout.subtitleTop, subtitleP), subtitleP)
        hairline(layout.headDividerY)

        // 年总收支：三列布局（支出 | 收入 | 结余）
        val statLabelP = textPaint(13f, TEXT_GRAY)
        val statValueP = textPaint(18f, BODY_DARK, bold = true)
        val colW = contentWidth / 3f
        val totals = listOf(
            Triple("支出", Money.formatPlain(stats.totalExpenseMinor), EXPENSE_RED),
            Triple("收入", Money.formatPlain(stats.totalIncomeMinor), INCOME_GREEN),
            Triple(
                "结余",
                (if (stats.netMinor >= 0) "+" else "-") + Money.formatPlain(abs(stats.netMinor)),
                if (stats.netMinor >= 0) INCOME_GREEN else EXPENSE_RED
            )
        )
        totals.forEachIndexed { i, (label, value, color) ->
            val x = contentLeft + colW * i
            statValueP.color = color
            canvas.drawText(label, x, baseline(layout.totalsTop, statLabelP), statLabelP)
            canvas.drawText(value, x, baseline(layout.totalsTop + lineH(statLabelP) + 6f * PX_PER_DP, statValueP), statValueP)
        }
        hairline(layout.totalsDividerY)

        // 12 月热力格：按当月支出 / 最大月支出 映射支出红透明度（0.15~1.0），无支出的月份浅灰
        val heatLabelP = textPaint(15f, TITLE_DARK, bold = true)
        canvas.drawText("月度支出热力", contentLeft, baseline(layout.heatLabelTop, heatLabelP), heatLabelP)
        val heatGap = 10f * PX_PER_DP
        val heatCellW = (contentWidth - 3 * heatGap) / 4f
        val maxMonth = stats.monthlyExpenseMinor.maxOrNull() ?: 0L
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val monthP = textPaint(11f, BODY_DARK, bold = true)
        val monthAmountP = textPaint(9f, BODY_DARK)
        val monthLabels = listOf("1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月")
        for (month in 0 until 12) {
            val row = month / 4
            val col = month % 4
            val left = contentLeft + col * (heatCellW + heatGap)
            val top = layout.heatTop + row * (layout.heatCellHeight + heatGap)
            val value = stats.monthlyExpenseMinor[month]
            val alpha = if (value <= 0L || maxMonth <= 0L) 0x14 else (0x14 + 0xE1 * (value.toFloat() / maxMonth)).toInt().coerceIn(0x14, 0xF5)
            cellPaint.color = (EXPENSE_RED and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawRoundRect(
                RectF(left, top, left + heatCellW, top + layout.heatCellHeight),
                8f * PX_PER_DP, 8f * PX_PER_DP, cellPaint
            )
            // 月名 + 金额（金额超宽省略号）
            val amountText = if (value > 0L) Money.formatPlain(value) else "—"
            monthP.color = BODY_DARK
            canvas.drawText(monthLabels[month], left + 10f * PX_PER_DP, baseline(top + 12f * PX_PER_DP, monthP), monthP)
            monthAmountP.color = if (value > 0L) EXPENSE_RED else TEXT_GRAY
            canvas.drawText(
                ellipsize(amountText, monthAmountP, heatCellW - 20f * PX_PER_DP),
                left + 10f * PX_PER_DP, baseline(top + 12f * PX_PER_DP + lineH(monthP) + 4f * PX_PER_DP, monthAmountP), monthAmountP
            )
        }
        hairline(layout.heatDividerY)

        // Top5 分类：名次 + 分类名 + 占比条 + 金额
        val topLabelP = textPaint(15f, TITLE_DARK, bold = true)
        canvas.drawText("年度支出 Top5 分类", contentLeft, baseline(layout.topLabelTop, topLabelP), topLabelP)
        val topNameP = textPaint(14f, BODY_DARK)
        val topAmountP = textPaint(14f, BODY_DARK, bold = true)
        val rankP = textPaint(12f, TEXT_GRAY, bold = true)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EXPENSE_RED }
        val barTopOffset = 22f * PX_PER_DP
        val barH = 5f * PX_PER_DP
        stats.topCategories.forEachIndexed { i, cat ->
            val top = layout.topRowTops[i]
            rankP.color = TEXT_GRAY
            canvas.drawText("${i + 1}.", contentLeft, baseline(top, topNameP), rankP)
            val rankW = rankP.measureText("${i + 1}.")
            val nameX = contentLeft + rankW + 6f * PX_PER_DP
            canvas.drawText(
                ellipsize(cat.categoryName, topNameP, contentWidth * 0.5f - rankW),
                nameX, baseline(top, topNameP), topNameP
            )
            // 占比条：紧贴分类名下方，宽度按 Top1 归一
            val barW = layout.topRowBarWidths[i]
            if (barW > 0f) {
                canvas.drawRoundRect(
                    RectF(nameX, top + barTopOffset, nameX + barW, top + barTopOffset + barH),
                    barH / 2f, barH / 2f, barPaint
                )
            }
            val amountText = Money.format(cat.amountMinor)
            topAmountP.color = BODY_DARK
            canvas.drawText(amountText, contentRight - topAmountP.measureText(amountText), baseline(top, topAmountP), topAmountP)
        }

        // 小盘贺词（居中，纯中文文案）
        val greetingP = textPaint(14f, BODY_DARK)
        val greeting = annualGreeting(stats)
        canvas.drawText(
            greeting,
            (CANVAS_WIDTH - greetingP.measureText(greeting)) / 2f,
            baseline(layout.greetingTop, greetingP),
            greetingP
        )

        // 页脚品牌
        val footerP = textPaint(12f, TEXT_GRAY)
        val brand = "RinklNote 记一笔"
        canvas.drawText(
            brand,
            (CANVAS_WIDTH - footerP.measureText(brand)) / 2f,
            baseline(layout.footerTop, footerP),
            footerP
        )
    }

    // ---------------------------------------------------------------------------
    // 布局度量（先量后画，保证位图高度一次到位，不建超大图再裁）
    // ---------------------------------------------------------------------------

    /** 度量结果：所有 y 坐标一次算好，绘制阶段只查表，杜绝「量」与「画」两套逻辑漂移。 */
    private class Layout(
        val heightPx: Int,
        val cardBottom: Float,
        val titleTop: Float,
        val countTop: Float,
        val headDividerY: Float,
        val rows: List<ShareRow>,
        val rowTops: List<Float>,
        val hiddenCount: Int,
        val noteTop: Float?,       // 「另有 N 笔未展示」提示行；null = 无截断
        val totalsDividerY: Float,
        val expenseTop: Float,
        val incomeTop: Float,
        val netDividerY: Float,
        val netTop: Float,
        val footerTop: Float
    )

    private fun textPaint(sizeDp: Float, color: Int, bold: Boolean = false): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = sizeDp * PX_PER_DP
            this.color = color
        }

    private fun lineH(p: Paint): Float = p.fontMetrics.descent - p.fontMetrics.ascent

    /** 文本顶部 y → 基线 y（Canvas.drawText 的 y 是基线）。 */
    private fun baseline(top: Float, p: Paint): Float = top - p.fontMetrics.ascent

    /** 按可用宽度末尾加省略号（Canvas 没有 Compose 的 overflow，用 TextUtils 兜底）。 */
    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String =
        if (paint.measureText(text) <= maxWidth) {
            text
        } else {
            TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
        }

    private fun measureLayout(title: String, rows: List<ShareRow>): Layout {
        val titleP = textPaint(20f, TITLE_DARK, bold = true)
        val countP = textPaint(13f, TEXT_GRAY)
        val catP = textPaint(16f, BODY_DARK)
        val remarkP = textPaint(13f, TEXT_GRAY)
        val amountP = textPaint(16f, BODY_DARK, bold = true)
        val labelP = textPaint(14f, TEXT_GRAY)
        val totalP = textPaint(16f, BODY_DARK, bold = true)
        val netLabelP = textPaint(15f, TITLE_DARK, bold = true)
        val netP = textPaint(20f, TITLE_DARK, bold = true)
        val noteP = textPaint(13f, TEXT_GRAY)
        val footerP = textPaint(12f, TEXT_GRAY)

        val cardTop = 20f * PX_PER_DP
        val rowVPad = 12f * PX_PER_DP
        val remarkGap = 2f * PX_PER_DP
        val firstLineH = max(lineH(catP), lineH(amountP))

        /** 单行高度：上内边距 + 首行（分类/金额）+（备注行）+ 下内边距。绘制阶段按同一公式拆解。 */
        fun rowHeight(hasRemark: Boolean): Float =
            rowVPad + firstLineH + (if (hasRemark) remarkGap + lineH(remarkP) else 0f) + rowVPad

        // 尾部（合计块 + 页脚）的固定高度：行循环拿它做高度预算，避免建图后才发现超高
        val hairline = 1f
        val totalsGap = 14f * PX_PER_DP
        val statGap = 10f * PX_PER_DP
        val netGap = 14f * PX_PER_DP
        val cardBottomPad = 24f * PX_PER_DP
        val footerGap = 14f * PX_PER_DP
        val pageBottomPad = 24f * PX_PER_DP
        val noteReserve = lineH(noteP) + 8f * PX_PER_DP
        val tailH = totalsGap + hairline + totalsGap +
            max(lineH(labelP), lineH(totalP)) + statGap +
            max(lineH(labelP), lineH(totalP)) + statGap +
            hairline + netGap +
            max(lineH(netLabelP), lineH(netP)) + cardBottomPad
        val footerBlockH = footerGap + lineH(footerP) + pageBottomPad

        // 卡头：标题 + 笔数 + 发丝分割线
        var y = cardTop + 24f * PX_PER_DP
        val titleTop = y
        y += lineH(titleP) + 4f * PX_PER_DP
        val countTop = y
        y += lineH(countP) + 14f * PX_PER_DP
        val headDividerY = y + 0.5f
        y += hairline + 2f * PX_PER_DP

        // 明细行：行数与总高度双上限，超限的计入 hiddenCount（只计数不占位）
        val visibleRows = ArrayList<ShareRow>(rows.size.coerceAtMost(MAX_ROWS))
        val rowTops = ArrayList<Float>(visibleRows.size)
        var hiddenCount = 0
        for (row in rows) {
            val h = rowHeight(!row.remark.isNullOrBlank())
            if (visibleRows.size >= MAX_ROWS ||
                y + h + noteReserve + tailH + footerBlockH > MAX_CANVAS_HEIGHT
            ) {
                hiddenCount++
                continue
            }
            rowTops.add(y)
            visibleRows.add(row)
            y += h + hairline // 行间发丝分割线占位（最后一行的分割线由合计分割线承担）
        }
        if (rowTops.isNotEmpty()) y -= hairline

        var noteTop: Float? = null
        if (hiddenCount > 0) {
            noteTop = y
            y += noteReserve
        }

        // 合计块：分割线 → 支出 → 收入 → 分割线 → 结余
        y += totalsGap
        val totalsDividerY = y + 0.5f
        y += hairline + totalsGap
        val expenseTop = y
        y += max(lineH(labelP), lineH(totalP)) + statGap
        val incomeTop = y
        y += max(lineH(labelP), lineH(totalP)) + netGap
        val netDividerY = y + 0.5f
        y += hairline + netGap
        val netTop = y
        y += max(lineH(netLabelP), lineH(netP)) + cardBottomPad
        val cardBottom = y

        val footerTop = cardBottom + footerGap
        val heightPx = (footerTop + lineH(footerP) + pageBottomPad).toInt()

        return Layout(
            heightPx = heightPx,
            cardBottom = cardBottom,
            titleTop = titleTop,
            countTop = countTop,
            headDividerY = headDividerY,
            rows = visibleRows,
            rowTops = rowTops,
            hiddenCount = hiddenCount,
            noteTop = noteTop,
            totalsDividerY = totalsDividerY,
            expenseTop = expenseTop,
            incomeTop = incomeTop,
            netDividerY = netDividerY,
            netTop = netTop,
            footerTop = footerTop
        )
    }

    // ---------------------------------------------------------------------------
    // 绘制（只消费 measureLayout 的结果 + 固定常量，不含任何布局判断）
    // ---------------------------------------------------------------------------

    private fun drawLayout(
        canvas: Canvas,
        layout: Layout,
        title: String,
        totalExpenseMinor: Long,
        totalIncomeMinor: Long
    ) {
        val sideMargin = SIDE_MARGIN_DP * PX_PER_DP
        val contentLeft = sideMargin + CARD_PAD_H_DP * PX_PER_DP
        val contentRight = CANVAS_WIDTH - sideMargin - CARD_PAD_H_DP * PX_PER_DP

        // 页面底 + 白色圆角卡
        canvas.drawColor(PAGE_BG)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_BG }
        canvas.drawRoundRect(
            RectF(sideMargin, 20f * PX_PER_DP, CANVAS_WIDTH - sideMargin, layout.cardBottom),
            CARD_RADIUS_DP * PX_PER_DP,
            CARD_RADIUS_DP * PX_PER_DP,
            cardPaint
        )

        // 1px 发丝分割线：关抗锯齿保证锐利
        val hair = Paint().apply { color = LINE_GRAY; strokeWidth = 1f; isAntiAlias = false }
        fun hairline(y: Float) = canvas.drawLine(contentLeft, y, contentRight, y, hair)

        // 卡头：标题 + 笔数 + 分割线
        val titleP = textPaint(20f, TITLE_DARK, bold = true)
        canvas.drawText(
            ellipsize(title, titleP, contentRight - contentLeft),
            contentLeft, baseline(layout.titleTop, titleP), titleP
        )
        val countP = textPaint(13f, TEXT_GRAY)
        canvas.drawText(
            "共 ${layout.rows.size} 笔",
            contentLeft, baseline(layout.countTop, countP), countP
        )
        hairline(layout.headDividerY)

        // 明细行：收支圆点 + 分类/备注（左） · 金额（右），排版镜像首页账单行
        val catP = textPaint(16f, BODY_DARK)
        val remarkP = textPaint(13f, TEXT_GRAY)
        val amountP = textPaint(16f, BODY_DARK, bold = true)
        val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
        val textX = contentLeft + 15f * PX_PER_DP     // 圆点 7dp + 间距 8dp，与页内行一致
        val dotCx = contentLeft + 3.5f * PX_PER_DP
        val firstLineH = max(lineH(catP), lineH(amountP))
        val rowVPad = 12f * PX_PER_DP
        val amountGap = 12f * PX_PER_DP               // 分类与金额之间的最小间距
        layout.rows.forEachIndexed { i, row ->
            val top = layout.rowTops[i]
            // 金额右对齐（符号规则与页内一致：支出 -、收入 +；¥ 由 Money.format 自带）
            val amountText = (if (row.isExpense) "-" else "+") + Money.format(row.amountMinor)
            amountP.color = if (row.isExpense) EXPENSE_RED else INCOME_GREEN
            val amountW = amountP.measureText(amountText)
            canvas.drawText(amountText, contentRight - amountW, baseline(top + rowVPad, amountP), amountP)
            // 圆点（垂直对齐首行文字中心）
            dotP.color = if (row.isExpense) EXPENSE_RED else INCOME_GREEN
            canvas.drawCircle(dotCx, top + rowVPad + firstLineH / 2f, 3.5f * PX_PER_DP, dotP)
            // 分类（给右侧金额留出空间后截断）
            canvas.drawText(
                ellipsize(row.categoryName, catP, contentRight - amountW - amountGap - textX),
                textX, baseline(top + rowVPad, catP), catP
            )
            // 备注（第二行，次要灰）
            if (!row.remark.isNullOrBlank()) {
                val remarkTop = top + rowVPad + firstLineH + 2f * PX_PER_DP
                canvas.drawText(
                    ellipsize(row.remark, remarkP, contentRight - textX),
                    textX, baseline(remarkTop, remarkP), remarkP
                )
            }
            // 行间分割线（最后一行的分割线由合计分割线承担）
            if (i < layout.rows.lastIndex) hairline(layout.rowTops[i + 1] - 0.5f)
        }

        // 截断提示
        if (layout.hiddenCount > 0 && layout.noteTop != null) {
            val noteP = textPaint(13f, TEXT_GRAY)
            canvas.drawText(
                "另有 ${layout.hiddenCount} 笔未展示",
                contentLeft, baseline(layout.noteTop, noteP), noteP
            )
        }

        // 合计块：支出 / 收入 / 结余（结余 = 收入 - 支出，按正负取收支色）
        hairline(layout.totalsDividerY)
        val labelP = textPaint(14f, TEXT_GRAY)
        val totalP = textPaint(16f, BODY_DARK, bold = true)
        canvas.drawText("支出", contentLeft, baseline(layout.expenseTop, labelP), labelP)
        totalP.color = EXPENSE_RED
        val expText = Money.format(totalExpenseMinor)
        canvas.drawText(expText, contentRight - totalP.measureText(expText), baseline(layout.expenseTop, totalP), totalP)

        canvas.drawText("收入", contentLeft, baseline(layout.incomeTop, labelP), labelP)
        totalP.color = INCOME_GREEN
        val incText = Money.format(totalIncomeMinor)
        canvas.drawText(incText, contentRight - totalP.measureText(incText), baseline(layout.incomeTop, totalP), totalP)

        hairline(layout.netDividerY)
        val netLabelP = textPaint(15f, TITLE_DARK, bold = true)
        val netP = textPaint(20f, TITLE_DARK, bold = true)
        val net = totalIncomeMinor - totalExpenseMinor
        val netText = (if (net >= 0) "+" else "-") + Money.format(abs(net))
        netP.color = if (net >= 0) INCOME_GREEN else EXPENSE_RED
        canvas.drawText("结余", contentLeft, baseline(layout.netTop, netLabelP), netLabelP)
        canvas.drawText(netText, contentRight - netP.measureText(netText), baseline(layout.netTop, netP), netP)

        // 页脚品牌（居中，画在卡片外的页面底上）
        val footerP = textPaint(12f, TEXT_GRAY)
        val brand = "RinklNote 记一笔"
        canvas.drawText(
            brand,
            (CANVAS_WIDTH - footerP.measureText(brand)) / 2f,
            baseline(layout.footerTop, footerP),
            footerP
        )
    }
}
