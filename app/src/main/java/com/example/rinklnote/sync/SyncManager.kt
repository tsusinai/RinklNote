package com.example.rinklnote.sync

import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.dao.BillTemplateDao
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.CreateBillRequest
import com.example.rinklnote.data.network.dto.TemplateDTO
import kotlinx.coroutines.flow.first

sealed class SyncResult {
    data object NotLoggedIn : SyncResult()
    data class Success(val pushed: Int, val pulled: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

class SyncManager(
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val billDao: BillDao,
    private val templateDao: BillTemplateDao? = null
) {
    suspend fun sync(): SyncResult {
        if (!tokenManager.isLoggedIn()) return SyncResult.NotLoggedIn

        return try {
            // 1. Push: upload local unsynced bills
            var pushed = 0
            val unsynced = billDao.getUnsynced()
            for (bill in unsynced) {
                try {
                    val dto = api.uploadBill(CreateBillRequest(
                        amount = bill.amount,
                        billType = bill.billType,
                        categoryId = bill.categoryId,
                        categoryName = bill.categoryName,
                        subCategoryName = bill.subCategoryName,
                        accountId = bill.accountId,
                        remark = bill.remark,
                        date = bill.date
                    ))
                    billDao.updateServerId(bill.id, dto.id, dto.updatedAt ?: dto.createdAt)
                    pushed++
                } catch (_: Exception) {
                    // Individual push failure → skip, retry next sync
                }
            }

            // 2. Pull: download server changes
            var pulled = 0
            var lastSync = tokenManager.lastSyncTime.first()
            var hasMore = true
            while (hasMore) {
                val response = api.syncBills(
                    after = if (lastSync > 0) lastSync else null,
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
                lastSync = response.serverTime
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

            SyncResult.Success(pushed, pulled)
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "同步失败")
        }
    }

    /** Upload a single local bill (non-blocking, called after QuickAdd) */
    suspend fun pushBill(bill: Bill) {
        try {
            val dto = api.uploadBill(CreateBillRequest(
                amount = bill.amount,
                billType = bill.billType,
                categoryId = bill.categoryId,
                categoryName = bill.categoryName,
                subCategoryName = bill.subCategoryName,
                accountId = bill.accountId,
                remark = bill.remark,
                date = bill.date
            ))
            billDao.updateServerId(bill.id, dto.id, dto.updatedAt ?: dto.createdAt)
        } catch (_: Exception) {
            // Will be pushed on next full sync
        }
    }
}

private fun TemplateDTO.toEntity() = BillTemplate(
    serverId = this.id, label = this.label, amount = this.amount,
    categoryId = this.categoryId, categoryName = this.categoryName,
    subCategoryName = this.subCategoryName, accountId = this.accountId,
    sortOrder = this.sortOrder
)
