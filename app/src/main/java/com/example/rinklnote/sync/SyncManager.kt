package com.example.rinklnote.sync

import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import kotlinx.coroutines.flow.first

sealed class SyncResult {
    data object NotLoggedIn : SyncResult()
    data class Success(val count: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

class SyncManager(
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val billDao: BillDao
) {
    suspend fun sync(): SyncResult {
        if (!tokenManager.isLoggedIn()) return SyncResult.NotLoggedIn

        return try {
            val lastSync = tokenManager.lastSyncTime.first()
            val response = api.syncBills(after = if (lastSync > 0) lastSync else null)

            val bills = response.bills.map { dto ->
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
                    serverId = dto.id
                )
            }

            billDao.insertAll(bills)
            tokenManager.setLastSyncTime(response.serverTime)
            SyncResult.Success(bills.size)
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "同步失败")
        }
    }
}
