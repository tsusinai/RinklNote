package com.example.rinklnote.sync

import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.dao.BillTemplateDao
import com.example.rinklnote.data.db.dao.BudgetDao
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.CreateBillRequest
import com.example.rinklnote.data.network.dto.TemplateDTO
import com.example.rinklnote.data.network.dto.UpsertBudgetRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class SyncResult {
    data object NotLoggedIn : SyncResult()
    data class Success(val pushed: Int, val pulled: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

class SyncManager(
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val billDao: BillDao,
    private val templateDao: BillTemplateDao? = null,
    private val budgetDao: BudgetDao? = null
) {
    // Serialize push/sync so a QuickAdd push and an automatic sync can never run
    // concurrently and double-submit the same bill.
    private val syncMutex = Mutex()

    suspend fun sync(): SyncResult = syncMutex.withLock {
        if (!tokenManager.isLoggedIn()) return SyncResult.NotLoggedIn

        return try {
            // 1. Push: upload local unsynced bills (create/update/delete branching)
            var pushed = 0
            val unsynced = billDao.getUnsynced()
            for (bill in unsynced) {
                try {
                    if (pushOneBill(bill)) pushed++
                } catch (_: Exception) {
                    // Individual push failure → skip, retry next sync
                }
            }

            // 2. Pull: download server changes
            var pulled = 0
            var lastSync = tokenManager.lastSyncTime.first()
            // Composite cursor (updatedAt, id) — the server returns the tail of each
            // page so we can continue past rows that share the same updatedAt.
            var afterId: Long? = null
            var hasMore = true
            while (hasMore) {
                val response = api.syncBills(
                    after = if (lastSync > 0) lastSync else null,
                    afterId = afterId,
                    limit = 200
                )
                hasMore = response.hasMore

                val (deleted, alive) = response.bills.partition { it.deleted }

                // Delete locally-removed bills
                deleted.forEach { billDao.deleteByServerId(it.id) }

                // Upsert changed bills (Room @Upsert handles insert-or-update)
                val merged = alive.map { dto ->
                    Bill(
                        amount = dto.amount,
                        billType = dto.billType,
                        categoryId = dto.categoryId,
                        categoryName = dto.categoryName,
                        subCategoryName = dto.subCategoryName,
                        accountId = dto.accountId,
                        remark = dto.remark,
                        date = dto.date,
                        createdAt = dto.createdAt,
                        source = dto.source,
                        serverId = dto.id,
                        updatedAt = dto.updatedAt,
                        deleted = false
                    )
                }
                billDao.upsertAll(merged)
                pulled += merged.size
                // Advance to the server-provided composite cursor.
                lastSync = response.nextAfter ?: response.bills.maxOfOrNull { it.updatedAt ?: 0 } ?: lastSync
                afterId = response.nextAfterId
            }

            tokenManager.setLastSyncTime(lastSync)

            // 3. Sync templates (server → local)
            templateDao?.let { dao ->
                try {
                    val serverTemplates = api.getTemplates()
                    dao.deleteAll()
                    if (serverTemplates.isNotEmpty()) {
                        dao.upsertAll(serverTemplates.map { it.toEntity() })
                    }
                } catch (_: Exception) {}
            }

            // 4. Sync budgets (push unsynced, then pull all + LWW merge)
            syncBudgets()

            SyncResult.Success(pushed, pulled)
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "同步失败")
        }
    }

    /**
     * Push a single local bill to the server, branching on its state:
     *  - deleted            → DELETE (soft-deleted server-side, hard-delete locally)
     *  - serverId != null   → PUT (update existing)
     *  - otherwise          → POST (create)
     */
    private suspend fun pushOneBill(bill: Bill): Boolean {
        return when {
            bill.deleted -> {
                val serverId = bill.serverId
                if (serverId == null) {
                    // Never pushed to the server — nothing to soft-delete remotely,
                    // so purge the local tombstone immediately (otherwise it lingers
                    // forever as an unsynced deleted row).
                    billDao.hardDeleteById(bill.id)
                    return true
                }
                api.deleteBill(serverId)
                billDao.hardDeleteById(bill.id)
                true
            }
            bill.serverId != null -> {
                val dto = api.updateBill(bill.serverId, bill.toRequest())
                billDao.updateServerId(bill.id, dto.id, dto.updatedAt ?: dto.createdAt)
                true
            }
            else -> {
                val dto = api.uploadBill(bill.toRequest())
                billDao.updateServerId(bill.id, dto.id, dto.updatedAt ?: dto.createdAt)
                true
            }
        }
    }

    /** Upload a single local bill (non-blocking, called after QuickAdd/edit/delete) */
    suspend fun pushBill(bill: Bill) = syncMutex.withLock {
        try {
            pushOneBill(bill)
        } catch (_: Exception) {
            // Will be pushed on next full sync
        }
    }

    /** Upload a single local budget (non-blocking, called after SetBudget) */
    suspend fun pushBudget(budget: Budget) = syncMutex.withLock {
        val dao = budgetDao ?: return@withLock
        try {
            if (!budget.deleted) {
                val dto = api.upsertBudget(UpsertBudgetRequest(budget.monthStart, budget.amount))
                dao.updateServerId(budget.id, dto.id, dto.updatedAt ?: dto.createdAt)
            }
        } catch (_: Exception) {
            // Will be pushed on next full sync
        }
    }

    /** Budgets are few in number → push all unsynced, pull everything, merge by LWW. */
    private suspend fun syncBudgets() {
        val dao = budgetDao ?: return

        // Push unsynced (server_id IS NULL OR dirty = 1)
        dao.getUnsynced().forEach { budget ->
            try {
                if (!budget.deleted) {
                    val dto = api.upsertBudget(UpsertBudgetRequest(budget.monthStart, budget.amount))
                    dao.updateServerId(budget.id, dto.id, dto.updatedAt ?: dto.createdAt)
                }
            } catch (_: Exception) {}
        }

        // Pull all + last-write-wins merge
        try {
            api.getBudgets().forEach { dto ->
                val serverTime = dto.updatedAt ?: dto.createdAt
                val local = dao.getByServerId(dto.id)
                if (local == null) {
                    dao.upsert(
                        Budget(
                            serverId = dto.id,
                            monthStart = dto.monthStart,
                            amount = dto.amount,
                            updatedAt = serverTime,
                            deleted = dto.deleted,
                            dirty = false
                        )
                    )
                } else {
                    val localTime = local.updatedAt ?: 0L
                    if (serverTime > localTime) {
                        dao.upsert(
                            local.copy(
                                amount = dto.amount,
                                monthStart = dto.monthStart,
                                updatedAt = serverTime,
                                deleted = dto.deleted,
                                dirty = false
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

private fun Bill.toRequest() = CreateBillRequest(
    amount = amount,
    billType = billType,
    categoryId = categoryId,
    categoryName = categoryName,
    subCategoryName = subCategoryName,
    accountId = accountId,
    remark = remark,
    date = date
)

private fun TemplateDTO.toEntity() = BillTemplate(
    serverId = this.id, label = this.label, amount = this.amount,
    categoryId = this.categoryId, categoryName = this.categoryName,
    subCategoryName = this.subCategoryName, accountId = this.accountId,
    sortOrder = this.sortOrder
)
