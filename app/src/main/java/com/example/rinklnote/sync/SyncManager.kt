package com.example.rinklnote.sync

import com.example.rinklnote.data.db.dao.AccountDao
import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.dao.BillTemplateDao
import com.example.rinklnote.data.db.dao.BudgetDao
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.Source
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.BillDTO
import com.example.rinklnote.data.network.dto.CreateAccountRequest
import com.example.rinklnote.data.network.dto.CreateBillRequest
import com.example.rinklnote.data.network.dto.TemplateDTO
import com.example.rinklnote.data.network.dto.UpdateAccountRequest
import com.example.rinklnote.data.network.dto.UpsertBudgetRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

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
    private val budgetDao: BudgetDao? = null,
    private val accountDao: AccountDao? = null
) {
    // Serialize push/sync so a QuickAdd push and an automatic sync can never run
    // concurrently and double-submit the same bill.
    private val syncMutex = Mutex()

    suspend fun sync(): SyncResult = syncMutex.withLock {
        if (!tokenManager.isLoggedIn()) return SyncResult.NotLoggedIn

        return try {
            // 0. Sync accounts first so the local account↔server-id mapping is in place
            //    before bills are pushed/pulled. The App stores a bill's accountId as the
            //    local Room account id, while the server expects the id of the account row
            //    on ITS side. Without the mapping established first, a push sends a local id
            //    (rejected 400) and a pull can't resolve the server account id it receives
            //    (Room FK violation).
            syncAccounts()

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

                // Upsert changed bills (Room @Upsert handles insert-or-update).
                // Skip any server row that the local device has a dirty (unsynced)
                // edit for — never clobber a local edit that hasn't been pushed yet.
                // baseUpdatedAt records the server version so the next conditional
                // PUT is guarded against concurrent edits.
                val merged = mutableListOf<Bill>()
                for (dto in alive) {
                    val local = billDao.getByServerId(dto.id)
                    if (local != null && local.dirty) continue
                    merged += Bill(
                        amount = dto.amount,
                        billType = BillType.fromValue(dto.billType),
                        categoryId = dto.categoryId,
                        categoryName = dto.categoryName,
                        subCategoryName = dto.subCategoryName,
                        // Server bills carry the server account id; map it back to the
                        // local account id so the Room FK (bills.account_id → accounts.id)
                        // is satisfied.
                        accountId = resolveLocalAccountId(dto.accountId),
                        remark = dto.remark,
                        date = dto.date,
                        createdAt = dto.createdAt,
                        source = Source.fromValue(dto.source),
                        serverId = dto.id,
                        updatedAt = dto.updatedAt,
                        baseUpdatedAt = dto.updatedAt,
                        sortOrder = dto.sortOrder,
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
                // Conditional PUT: the local baseUpdatedAt guards against a version
                // the server already superseded. On 409, re-base on the server's
                // current DTO and replay ONCE — so a lost/late device can't clobber
                // a newer edit.
                val dto = updateWithReplay(bill)
                billDao.updateServerId(bill.id, dto.id, dto.updatedAt ?: dto.createdAt)
                true
            }
            else -> {
                // Create: resolve the local account id to the server account id before
                // uploading, otherwise the server's ownership check rejects it (400).
                val req = mappedBillRequest(bill) ?: return false
                val dto = api.uploadBill(req)
                billDao.updateServerId(bill.id, dto.id, dto.updatedAt ?: dto.createdAt)
                true
            }
        }
    }

    /** Resolve the server account id for a locally-stored bill. The App's Bill stores
     * accountId as the local Room account id; the server expects the id of the account
     * row on ITS side. Returns null when the account has no server id yet (e.g. an
     * unreconciled seeded account) — callers leave the bill for a later sync. */
    private suspend fun mappedBillRequest(bill: Bill): CreateBillRequest? {
        val serverAccountId = accountDao?.getById(bill.accountId)?.serverId ?: return null
        return bill.toRequest().copy(accountId = serverAccountId)
    }

    /** Map a server account id back to the local Room account id (for the pull merge). */
    private suspend fun resolveLocalAccountId(serverAccountId: Long): Long {
        val dao = accountDao ?: return serverAccountId
        return dao.getByServerId(serverAccountId)?.id
            ?: dao.getAllActive().firstOrNull()?.id
            ?: serverAccountId
    }

    private suspend fun updateWithReplay(bill: Bill): BillDTO {
        val serverId = bill.serverId ?: error("bill has no server id")
        val base = mappedBillRequest(bill)?.copy(baseUpdatedAt = bill.baseUpdatedAt)
            ?: error("无法解析账户")
        return try {
            api.updateBill(serverId, base)
        } catch (e: HttpException) {
            if (e.code() == 409) {
                val fresh = api.getBill(serverId)
                api.updateBill(serverId, base.copy(baseUpdatedAt = fresh.updatedAt ?: fresh.createdAt))
            } else {
                throw e
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
                val dto = api.upsertBudget(
                    UpsertBudgetRequest(
                        monthStart = budget.monthStart,
                        amount = budget.amount,
                        periodType = budget.periodType,
                        categoryId = budget.categoryId,
                        subCategoryId = budget.subCategoryId
                    )
                )
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
                    val dto = api.upsertBudget(
                        UpsertBudgetRequest(
                            monthStart = budget.monthStart,
                            amount = budget.amount,
                            periodType = budget.periodType,
                            categoryId = budget.categoryId,
                            subCategoryId = budget.subCategoryId
                        )
                    )
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
                            periodType = dto.periodType,
                            categoryId = dto.categoryId,
                            subCategoryId = dto.subCategoryId,
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
                                periodType = dto.periodType,
                                categoryId = dto.categoryId,
                                subCategoryId = dto.subCategoryId,
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

    /** Upload a single local account (non-blocking, called after create/edit/delete). */
    suspend fun pushAccount(account: Account) = syncMutex.withLock {
        val dao = accountDao ?: return@withLock
        try {
            pushOneAccount(account)
        } catch (_: Exception) {
            // Will be pushed on next full sync
        }
    }

    /** Accounts are few in number → push all unsynced, pull everything, merge by LWW. */
    private suspend fun syncAccounts() {
        val dao = accountDao ?: return

        // Push unsynced (server_id IS NULL OR dirty = 1)
        dao.getUnsynced().forEach { account ->
            try {
                pushOneAccount(account)
            } catch (_: Exception) {}
        }

        // Pull all + last-write-wins merge; never clobber a local dirty row.
        try {
            api.getAccounts().forEach { dto ->
                val serverTime = dto.updatedAt ?: 0L
                var local = dao.getByServerId(dto.id)
                if (local == null) {
                    // Reconcile by name: a local seeded/custom account of the same name that
                    // hasn't been stamped with a server id yet should ADOPT the server id and
                    // data, rather than becoming a duplicate row. This collapses the seed
                    // accounts (local ids 1/2/3) into the per-user server accounts (e.g.
                    // ids 214/215/216) so their local id maps to the right server id.
                    val byName = dao.getByNameActive(dto.name)
                    local = if (byName != null && !byName.dirty) {
                        byName.copy(
                            serverId = dto.id, name = dto.name, balance = dto.balance,
                            iconColor = dto.iconColor, updatedAt = serverTime,
                            deleted = dto.deleted, dirty = false
                        ).also { dao.upsert(it) }
                    } else {
                        dao.upsert(
                            Account(
                                serverId = dto.id, name = dto.name, balance = dto.balance,
                                iconColor = dto.iconColor, updatedAt = serverTime,
                                deleted = dto.deleted, dirty = false
                            )
                        )
                        dao.getByServerId(dto.id)
                    }
                }
                if (local == null) return@forEach
                if (local.dirty) return@forEach
                val localTime = local.updatedAt ?: 0L
                if (serverTime > localTime) {
                    dao.upsert(
                        local.copy(
                            name = dto.name, balance = dto.balance,
                            iconColor = dto.iconColor, updatedAt = serverTime,
                            deleted = dto.deleted, dirty = false
                        )
                    )
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Push a single local account, branching on its state:
     *  - deleted            → DELETE (soft-deleted server-side, hard-delete locally)
     *  - serverId != null   → PUT (update existing)
     *  - otherwise          → POST (create)
     */
    private suspend fun pushOneAccount(account: Account): Boolean {
        val dao = accountDao ?: return true
        return when {
            account.deleted -> {
                val serverId = account.serverId
                if (serverId == null) {
                    dao.hardDeleteById(account.id)
                    return true
                }
                api.deleteAccount(serverId)
                dao.hardDeleteById(account.id)
                true
            }
            account.serverId != null -> {
                val dto = api.updateAccount(
                    account.serverId,
                    UpdateAccountRequest(name = account.name, iconColor = account.iconColor, balance = account.balance)
                )
                dao.updateServerId(account.id, dto.id, dto.updatedAt ?: 0L)
                true
            }
            else -> {
                val dto = api.createAccount(
                    CreateAccountRequest(name = account.name, iconColor = account.iconColor, balance = account.balance)
                )
                dao.updateServerId(account.id, dto.id, dto.updatedAt ?: 0L)
                true
            }
        }
    }
}

private fun Bill.toRequest() = CreateBillRequest(
    amount = amount,
    billType = billType.value,
    categoryId = categoryId,
    categoryName = categoryName,
    subCategoryName = subCategoryName,
    accountId = accountId,
    remark = remark,
    date = date,
    sortOrder = sortOrder
)

private fun TemplateDTO.toEntity() = BillTemplate(
    serverId = this.id, label = this.label, amount = this.amount,
    categoryId = this.categoryId, categoryName = this.categoryName,
    subCategoryName = this.subCategoryName, accountId = this.accountId,
    sortOrder = this.sortOrder
)
