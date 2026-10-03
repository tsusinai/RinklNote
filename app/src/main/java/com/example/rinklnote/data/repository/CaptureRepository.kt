package com.example.rinklnote.data.repository

import androidx.room.withTransaction
import com.example.rinklnote.data.db.AppDatabase
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillCapture
import com.example.rinklnote.data.db.entity.BillCaptureStates
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.Source
import com.example.rinklnote.util.PayNotificationParser
import com.example.rinklnote.util.VoiceParser
import com.example.rinklnote.util.bookkeepingZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * 自动记账捕获管线（2026-10-03 方案 C「全自动 + 管家对账」）。
 *
 * NotificationListener 捕到的支付通知在此完成：解析（[PayNotificationParser]，纯本地）
 * → 捕获级去重（同包名同金额 2 分钟窗口）→ 账单级去重置换（±5 分钟同金额 + 商户相似）
 * → 按模式落账：
 *  - SILENT：直接入账（bills.source = AUTO，dirty 随同步上送，服务端暮检按此口径对账）；
 *  - CONFIRM：挂 PENDING 捕获，系统通知「记上 / 忽略」一键处置，或进自动记账页处理。
 *
 * 隐私口径：通知原文只进端内解析与 bill_captures 台账，不参与同步、不上送 LLM。
 */
interface CaptureRepository {

    /** 捕获管线结果。 */
    sealed interface CaptureOutcome {
        /** 捕获级去重命中（同一笔的重复通知），静默丢弃。 */
        data object Duplicate : CaptureOutcome

        /** 解析不出金额：不落台账（与既有「通用记一笔入口」行为互补）。 */
        data object Unmatched : CaptureOutcome

        /** 没有任何可用账户，无法入账。 */
        data object NoAccount : CaptureOutcome

        /** 已入账（[merged] = true 表示命中去重、并入已有账单而非新建）。 */
        data class Booked(
            val captureId: Long,
            val billId: Long,
            val merged: Boolean,
            val amountMinor: Long,
            val merchant: String?
        ) : CaptureOutcome

        /** 确认模式：挂起待用户一键确认。 */
        data class Pending(val captureId: Long, val amountMinor: Long, val merchant: String?) : CaptureOutcome
    }

    /** 捕获一条支付通知（在监听服务的协程作用域里调用）。 */
    suspend fun capture(packageName: String, title: String?, text: String?, occurredAt: Long): CaptureOutcome

    /** 确认模式：把 PENDING 捕获落账。 */
    suspend fun confirmCapture(captureId: Long)

    /** 确认模式：忽略 PENDING 捕获。 */
    suspend fun ignoreCapture(captureId: Long)

    /** 对账底座：待确认清单 / 最近捕获记录。 */
    fun observePending(): Flow<List<BillCapture>>
    fun observeRecent(): Flow<List<BillCapture>>

    /** 渐进信任统计（suggestSilent 用）。 */
    data class TrustStats(val captureBooked: Int, val autoTotal: Int, val autoKept: Int)

    suspend fun trustStats(): TrustStats
}

class CaptureRepositoryImpl(
    private val db: AppDatabase,
    private val settingsManager: SettingsManager
) : CaptureRepository {

    override suspend fun capture(
        packageName: String,
        title: String?,
        text: String?,
        occurredAt: Long
    ): CaptureRepository.CaptureOutcome {
        val parsed = PayNotificationParser.parse(title, text)
        val amountMinor = parsed.amountMinor

        // 解析不出金额：不落台账（服务侧仍会弹通用「记一笔」入口，不丢线索）
        if (!parsed.matched || amountMinor == null || amountMinor <= 0) {
            return CaptureRepository.CaptureOutcome.Unmatched
        }

        // 捕获级去重：同包名 + 同金额 2 分钟内已有非忽略捕获 → 同一笔的重复通知
        val dup = db.billCaptureDao().findDuplicateCapture(
            packageName, amountMinor, occurredAt - CAPTURE_DUP_WINDOW_MS, occurredAt + CAPTURE_DUP_WINDOW_MS
        )
        if (dup != null) return CaptureRepository.CaptureOutcome.Duplicate

        val now = System.currentTimeMillis()

        // 账单级去重置换：±5 分钟同金额 + 商户相似的既有账单 → 不新建，记 MERGED 留痕
        val candidates = db.billDao().findByAmountInWindow(
            amountMinor, occurredAt - BILL_DUP_WINDOW_MS, occurredAt + BILL_DUP_WINDOW_MS
        )
        val matched = candidates.firstOrNull { isMerchantSimilar(it.remark, parsed.merchant) }

        val mode = settingsManager.autoCaptureMode.first()
        return db.withTransaction {
            if (matched != null) {
                val captureId = insertCapture(packageName, title, text, parsed, occurredAt, BillCaptureStates.MERGED, matched.id, now)
                CaptureRepository.CaptureOutcome.Booked(captureId, matched.id, merged = true, amountMinor, parsed.merchant)
            } else {
                when (mode) {
                    SettingsManager.AUTO_CAPTURE_MODE_SILENT -> {
                        val captureId = insertCapture(packageName, title, text, parsed, occurredAt, BillCaptureStates.PENDING, null, now)
                        val capture = db.billCaptureDao().getById(captureId) ?: error("capture missing")
                        val billId = bookCapture(capture)
                        CaptureRepository.CaptureOutcome.Booked(captureId, billId, merged = false, amountMinor, parsed.merchant)
                    }
                    else -> {
                        val captureId = insertCapture(packageName, title, text, parsed, occurredAt, BillCaptureStates.PENDING, null, now)
                        CaptureRepository.CaptureOutcome.Pending(captureId, amountMinor, parsed.merchant)
                    }
                }
            }
        }
    }

    override suspend fun confirmCapture(captureId: Long) {
        val capture = db.billCaptureDao().getById(captureId) ?: return
        if (capture.state != BillCaptureStates.PENDING) return
        db.withTransaction {
            val billId = bookCapture(capture)
            db.billCaptureDao().update(capture.copy(state = BillCaptureStates.BOOKED, billId = billId))
        }
    }

    override suspend fun ignoreCapture(captureId: Long) {
        val capture = db.billCaptureDao().getById(captureId) ?: return
        if (capture.state != BillCaptureStates.PENDING) return
        db.billCaptureDao().update(capture.copy(state = BillCaptureStates.IGNORED))
    }

    override fun observePending(): Flow<List<BillCapture>> = db.billCaptureDao().observePending()

    override fun observeRecent(): Flow<List<BillCapture>> = db.billCaptureDao().observeRecent()

    override suspend fun trustStats(): CaptureRepository.TrustStats =
        CaptureRepository.TrustStats(
            captureBooked = db.billCaptureDao().countBooked(),
            autoTotal = db.billDao().countAutoTotal(),
            autoKept = db.billDao().countAutoKept()
        )

    // ── 落账共用 ──

    /**
     * 把捕获落成账单（source = AUTO，dirty = true 随同步上送）。
     * 分类：关键词命中 → 该分类（类型以分类表为权威，收入关键词命中「工资」类自动纠正方向）；
     * 未命中 → 支出落「三餐」（与服务端未知分类口径一致）、收入落「其他」收入分类。
     */
    private suspend fun bookCapture(capture: BillCapture): Long {
        val body = listOfNotNull(capture.notifTitle, capture.notifText, capture.merchant)
            .joinToString(" ")
        val amountMinor = capture.amountMinor ?: error("capture without amount")
        val account = db.accountDao().getAllActive().firstOrNull()
            ?: throw IllegalStateException("自动记账失败：没有可用账户")

        val inferredName = VoiceParser.inferCategory(body)
        val category = inferredName?.let { db.categoryDao().getByName(it) }
            ?: if (capture.billType == BillType.INCOME.value) {
                db.categoryDao().getByName(INCOME_FALLBACK_CATEGORY)
                    ?: db.categoryDao().getAllByType(BillType.INCOME.value).firstOrNull()
            } else {
                db.categoryDao().getAllByType(BillType.EXPENSE.value).firstOrNull()
            }
            ?: throw IllegalStateException("自动记账失败：没有可用分类")

        // 分类表的 billType 为权威口径（收入关键词命中「工资」类时自动纠正方向）
        val billType = category.billType
        val bill = Bill(
            amountMinor = amountMinor,
            billType = billType,
            categoryId = category.id,
            categoryName = category.name,
            accountId = account.id,
            remark = capture.merchant,
            date = Instant.ofEpochMilli(capture.occurredAt).atZone(bookkeepingZone())
                .toLocalDate().atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli(),
            createdAt = capture.occurredAt,
            updatedAt = capture.occurredAt,
            source = Source.AUTO,
            dirty = true
        )
        return db.billDao().insert(bill)
    }

    private suspend fun insertCapture(
        packageName: String,
        title: String?,
        text: String?,
        parsed: PayNotificationParser.PayNotification,
        occurredAt: Long,
        state: String,
        billId: Long?,
        now: Long
    ): Long {
        val id = db.billCaptureDao().insert(
            BillCapture(
                packageName = packageName,
                notifTitle = title,
                notifText = text,
                amountMinor = parsed.amountMinor,
                billType = parsed.billType,
                merchant = parsed.merchant,
                occurredAt = occurredAt,
                state = state,
                billId = billId,
                createdAt = now
            )
        )
        // 顺手清 30 天前的旧台账（低频捕获，无独立调度）
        db.billCaptureDao().deleteOlderThan(now - PRUNE_WINDOW_MS)
        return id
    }

    companion object {
        /** 捕获级去重窗口：同包名同金额 2 分钟视为同一笔。 */
        const val CAPTURE_DUP_WINDOW_MS = 2 * 60_000L

        /** 账单级去重窗口：±5 分钟同金额即候选。 */
        const val BILL_DUP_WINDOW_MS = 5 * 60_000L

        /** 台账保留窗口：30 天。 */
        const val PRUNE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        /** 收入无关键词时的兜底分类（种子里的收入「其他」）。 */
        const val INCOME_FALLBACK_CATEGORY = "其他"

        /**
         * 商户相似度（纯函数，可单测）：双方都非空时按互相包含判等；任一为空视为无法证伪 → 相似。
         * 通知里的商户常带省略后缀（「全家便利店（徐汇店）」vs 手记「全家」），包含即算同一商户。
         */
        fun isMerchantSimilar(a: String?, b: String?): Boolean {
            val x = a?.trim().orEmpty()
            val y = b?.trim().orEmpty()
            if (x.isEmpty() || y.isEmpty()) return true
            return x.contains(y) || y.contains(x)
        }

        /**
         * 渐进信任判定（纯函数，可单测）：确认模式累计自动入账 ≥10 笔且保留率 ≥90% 时，
         * 才建议解锁静默模式。保留率分母为 0（尚无 AUTO 账单）不推荐。
         */
        fun shouldSuggestSilent(stats: CaptureRepository.TrustStats): Boolean {
            if (stats.captureBooked < 10) return false
            if (stats.autoTotal <= 0) return false
            return stats.autoKept * 10 >= stats.autoTotal * 9
        }
    }
}
