package com.example.rinklnote.sync

import com.example.rinklnote.data.db.dao.AccountDao
import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.dao.BillTemplateDao
import com.example.rinklnote.data.db.dao.BudgetDao
import com.example.rinklnote.data.db.dao.ChallengeDao
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Challenge
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
import com.example.rinklnote.data.network.dto.UpsertChallengeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException

sealed class SyncResult {
    data object NotLoggedIn : SyncResult()
    data class Success(val pushed: Int, val pulled: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

/**
 * 同步状态（体验优化 Task 1）：SyncManager 暴露的可观察状态流，供个人资料页与
 * 结构化反馈收集展示。**只描述最近一次同步/推送的结果**，不参与、也不改变任何
 * 同步协议行为（请求顺序 / LWW / 软删除 / 乐观锁均维持原样）。
 */
sealed class SyncStatus {
    /** 空闲：启动后还没有产生过同步结果（重启后回落到这里，可配 TokenManager 持久化的失败原因还原展示）。 */
    data object Idle : SyncStatus()

    /** 同步中（全量同步或单条推送在途）。 */
    data object Syncing : SyncStatus()

    /** 已同步：完成时间 + 本次上传/下载数（单条推送时 pulled = 0）。 */
    data class Synced(val time: Long, val pushed: Int, val pulled: Int) : SyncStatus()

    /** 待重试：本地待上传条数 + 用户可读的失败原因（数据仍完整保留在本机）。 */
    data class RetryPending(val pendingCount: Int, val reason: String, val time: Long) : SyncStatus()

    /** 需要登录：未登录或登录已过期（HTTP 401）。 */
    data object NeedLogin : SyncStatus()
}

class SyncManager(
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val billDao: BillDao,
    private val templateDao: BillTemplateDao? = null,
    private val budgetDao: BudgetDao? = null,
    private val accountDao: AccountDao? = null,
    private val challengeDao: ChallengeDao? = null
) {
    // Serialize push/sync so a QuickAdd push and an automatic sync can never run
    // concurrently and double-submit the same bill.
    private val syncMutex = Mutex()

    /** 可收集的同步状态流（只读）。状态只描述结果，见 [SyncStatus] 注释。 */
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun sync(): SyncResult = syncMutex.withLock {
        if (!tokenManager.isLoggedIn()) {
            // 未登录：不发任何请求；状态置为「需要登录」供个人资料页/反馈层消费。
            _status.value = SyncStatus.NeedLogin
            return SyncResult.NotLoggedIn
        }
        // 状态只描述进度，不改变下方任何请求顺序与冲突处理逻辑。
        _status.value = SyncStatus.Syncing
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
                deleted.forEach { dto ->
                    // 撤销竞态防护：本地行若已撤销删除（deleted=0、dirty=1），保留本地新意图，
                    // 下次推送会以 PUT 恢复服务端；服务端墓碑不得把刚恢复的账单再删掉。
                    // 本地行不存在或仍是墓碑时维持原行为（清理本地行/陈旧墓碑）。
                    val local = billDao.getByServerId(dto.id)
                    if (local == null || local.deleted) billDao.deleteByServerId(dto.id)
                }

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
                        amountMinor = dto.resolvedAmountMinor,
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
                        // 经纬度：服务端有值才带（老账单/旧服务端为 null，本地保持未打点）。
                        latitude = dto.latitude,
                        longitude = dto.longitude,
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

            // 5. Sync challenges (push unsynced, then pull all + LWW merge)
            syncChallenges()

            // 体验审查修复（P1）：单条推送失败此前被静默吞掉，流程仍会走到「已同步」，
            // 用户会看到已同步但本地还压着待上传数据。全部阶段结束后核对本地待上传
            // 余量：仍有余量就如实呈现「待重试」（含条数），并把返回值改为 Error。
            val pending = pendingUploadCount()
            if (pending > 0) {
                val reason = "有 $pending 条数据尚未上传，已保留在本机"
                tokenManager.setSyncFailure(reason)
                _status.value = SyncStatus.RetryPending(pending, reason, System.currentTimeMillis())
                SyncResult.Error(reason)
            } else {
                SyncResult.Success(pushed, pulled).also {
                    // 成功即清除持久化的失败原因（TokenManager），让重启后的状态卡不再误报待重试。
                    tokenManager.clearSyncFailure()
                    _status.value = SyncStatus.Synced(System.currentTimeMillis(), pushed, pulled)
                }
            }
        } catch (e: Exception) {
            val message = readableError(e)
            // 失败原因持久化（仅时间 + 用户可读文案，绝不写入 token 或账单内容）。
            tokenManager.setSyncFailure(message)
            _status.value = if (e is HttpException && e.code() == 401) {
                SyncStatus.NeedLogin
            } else {
                SyncStatus.RetryPending(pendingUploadCount(), message, System.currentTimeMillis())
            }
            SyncResult.Error(message)
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
                    // forever as an unsynced deleted row). 条件清除：推送在途时用户可能
                    // 已撤销删除，只清仍是墓碑的行，不碰刚恢复的账单。
                    billDao.hardDeleteTombstoneById(bill.id)
                    return true
                }
                api.deleteBill(serverId)
                // 撤销竞态防护：api.deleteBill 在途期间用户可能已撤销（deleted=0、dirty=1），
                // 这里只物理清除仍是墓碑的行，避免把刚恢复的账单再次删掉。
                billDao.hardDeleteTombstoneById(bill.id)
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

    /**
     * Upload a single local bill (non-blocking, called after QuickAdd/edit/delete).
     *
     * 返回推送结果并同步驱动 [status]（成功 / 待重试 / 需要登录），供「已同步到云端 /
     * 同步失败仍保留本机」等结构化反馈判定使用——判定口径与状态流同源，不另起一套。
     */
    suspend fun pushBill(bill: Bill): SyncResult = syncMutex.withLock {
        if (!tokenManager.isLoggedIn()) return@withLock SyncResult.NotLoggedIn
        _status.value = SyncStatus.Syncing
        try {
            if (pushOneBill(bill)) {
                succeedPush(pushed = 1)
            } else {
                // 账户尚未解析出 server_id：本条保留在本机等下次同步（原语义不变）。
                failPush("账户尚未同步，稍后自动重试")
            }
        } catch (e: Exception) {
            failPush(readableError(e), e)
        }
    }

    /** Upload a single local budget (non-blocking, called after SetBudget/delete). */
    suspend fun pushBudget(budget: Budget) = syncMutex.withLock {
        if (!tokenManager.isLoggedIn()) return@withLock Unit
        _status.value = SyncStatus.Syncing
        try {
            pushOneBudget(budget)
            succeedPush(pushed = 1)
        } catch (e: Exception) {
            failPush(readableError(e), e)
        }
    }

    /**
     * Push one budget, branching on its state (must be called while holding [syncMutex]):
     *  - deleted   → DELETE server-side (soft), then hard-delete the local tombstone
     *  - otherwise → PUT upsert, then record the server id
     */
    private suspend fun pushOneBudget(budget: Budget) {
        val dao = budgetDao ?: return
        if (budget.deleted) {
            val serverId = budget.serverId
            if (serverId == null) {
                // Never pushed to the server — nothing to soft-delete remotely,
                // so purge the local tombstone immediately (otherwise it lingers
                // forever as an unsynced deleted row).
                dao.hardDeleteById(budget.id)
                return
            }
            api.deleteBudget(serverId)
            dao.hardDeleteById(budget.id)
            return
        }
        val dto = api.upsertBudget(
            UpsertBudgetRequest(
                monthStart = budget.monthStart,
                amountMinor = budget.amountMinor,
                periodType = budget.periodType,
                categoryId = budget.categoryId,
                subCategoryId = budget.subCategoryId
            )
        )
        dao.updateServerId(budget.id, dto.id, dto.updatedAt ?: dto.createdAt)
    }

    /** Budgets are few in number → push all unsynced, pull everything, merge by LWW. */
    private suspend fun syncBudgets() {
        val dao = budgetDao ?: return

        // Push unsynced (server_id IS NULL OR dirty = 1)
        dao.getUnsynced().forEach { budget ->
            try {
                pushOneBudget(budget)
            } catch (_: Exception) {}
        }

        // Pull all + last-write-wins merge
        try {
            api.getBudgets().forEach { dto ->
                if (dto.deleted) {
                    // 服务端已删除：本地直接清掉对应行（与账单行为一致，不留陈旧墓碑）。
                    dao.deleteByServerId(dto.id)
                    return@forEach
                }
                val serverTime = dto.updatedAt ?: dto.createdAt
                val local = dao.getByServerId(dto.id)
                if (local == null) {
                    dao.upsert(
                        Budget(
                            serverId = dto.id,
                            monthStart = dto.monthStart,
                            amountMinor = dto.resolvedAmountMinor,
                            periodType = dto.periodType,
                            categoryId = dto.categoryId,
                            subCategoryId = dto.subCategoryId,
                            updatedAt = serverTime,
                            deleted = false,
                            dirty = false
                        )
                    )
                } else {
                    val localTime = local.updatedAt ?: 0L
                    if (serverTime > localTime) {
                        dao.upsert(
                            local.copy(
                                amountMinor = dto.resolvedAmountMinor,
                                monthStart = dto.monthStart,
                                periodType = dto.periodType,
                                categoryId = dto.categoryId,
                                subCategoryId = dto.subCategoryId,
                                updatedAt = serverTime,
                                deleted = false,
                                dirty = false
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /** Upload a single local challenge (non-blocking, called after create/adjust). */
    suspend fun pushChallenge(challenge: Challenge) = syncMutex.withLock {
        if (!tokenManager.isLoggedIn()) return@withLock Unit
        _status.value = SyncStatus.Syncing
        try {
            pushOneChallenge(challenge)
            succeedPush(pushed = 1)
        } catch (e: Exception) {
            failPush(readableError(e), e)
        }
    }

    /**
     * Push one challenge（须持有 [syncMutex] 调用）。v1 无删除端点：
     * 本地软删墓碑不推送（无删除 UI，正常不会产生），留待 pull 对账；
     * 其余一律 PUT upsert —— 服务端 LWW 旧写被拒时仍返回现行行（200），
     * 拿返回 DTO 回写 server_id 即可，无需重放。
     */
    private suspend fun pushOneChallenge(challenge: Challenge) {
        val dao = challengeDao ?: return
        if (challenge.deleted) return
        val dto = api.upsertChallenge(
            UpsertChallengeRequest(
                type = challenge.type,
                periodStart = challenge.periodStart,
                goal = challenge.goal,
                status = challenge.status,
                // 客户端行时间戳参与服务端 LWW 比较。
                updatedAt = challenge.updatedAt
            )
        )
        dao.updateServerId(challenge.id, dto.id, dto.updatedAt ?: dto.createdAt)
    }

    /** 挑战数量少 → 推送全部未同步行，再全量拉取按 LWW 合并。 */
    private suspend fun syncChallenges() {
        val dao = challengeDao ?: return

        // Push unsynced (server_id IS NULL OR dirty = 1)
        dao.getUnsynced().forEach { challenge ->
            try {
                pushOneChallenge(challenge)
            } catch (_: Exception) {}
        }

        // Pull all + last-write-wins merge
        try {
            api.getChallenges().forEach { dto ->
                if (dto.deleted) {
                    // 服务端已删除：本地直接清掉对应行（与预算行为一致，不留陈旧墓碑）。
                    dao.deleteByServerId(dto.id)
                    return@forEach
                }
                val serverTime = dto.updatedAt ?: dto.createdAt
                val local = dao.getByServerId(dto.id)
                if (local == null) {
                    dao.upsert(
                        Challenge(
                            serverId = dto.id,
                            type = dto.type,
                            periodStart = dto.periodStart,
                            goal = dto.goal,
                            status = dto.status,
                            updatedAt = serverTime,
                            deleted = false,
                            dirty = false
                        )
                    )
                } else {
                    // 本地 dirty 行尚未推送，绝不被服务端数据覆盖（与账户合并同规则）。
                    if (local.dirty) return@forEach
                    val localTime = local.updatedAt ?: 0L
                    if (serverTime > localTime) {
                        dao.upsert(
                            local.copy(
                                type = dto.type,
                                periodStart = dto.periodStart,
                                goal = dto.goal,
                                status = dto.status,
                                updatedAt = serverTime,
                                deleted = false,
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
        if (!tokenManager.isLoggedIn()) return@withLock Unit
        _status.value = SyncStatus.Syncing
        try {
            pushOneAccount(account)
            succeedPush(pushed = 1)
        } catch (e: Exception) {
            failPush(readableError(e), e)
        }
    }

    // ── 同步状态辅助（体验优化 Task 1）：只写状态流与持久化失败原因，不参与同步逻辑 ──

    /**
     * 最近一次推送成功：本条结果记 Success（结构化反馈针对本条账单），但状态卡要如实 ——
     * 本地仍压着其他待上传数据时呈现「待重试」（体验审查 P1：不误报「已同步」）；
     * 待上传清零才清除持久化失败原因并报已同步。
     */
    private suspend fun succeedPush(pushed: Int): SyncResult {
        val pending = pendingUploadCount()
        if (pending > 0) {
            _status.value = SyncStatus.RetryPending(
                pending, "仍有 $pending 条数据待上传", System.currentTimeMillis()
            )
            return SyncResult.Success(pushed, pulled = 0)
        }
        tokenManager.clearSyncFailure()
        _status.value = SyncStatus.Synced(System.currentTimeMillis(), pushed, pulled = 0)
        return SyncResult.Success(pushed, pulled = 0)
    }

    /** 最近一次推送失败：写状态并持久化用户可读原因（数据仍完整保留在本机等重试）。 */
    private suspend fun failPush(reason: String, error: Exception? = null): SyncResult {
        tokenManager.setSyncFailure(reason)
        _status.value = if (error is HttpException && error.code() == 401) {
            SyncStatus.NeedLogin
        } else {
            SyncStatus.RetryPending(pendingUploadCount(), reason, System.currentTimeMillis())
        }
        return SyncResult.Error(reason)
    }

    /** 本地待上传条数（账单/账户/预算/挑战合计），仅用于状态展示；单个来源查询异常按 0 兜底。 */
    private suspend fun pendingUploadCount(): Int {
        var count = runCatching { billDao.countUnsynced() }.getOrDefault(0L)
        accountDao?.let { count += runCatching { it.countUnsynced() }.getOrDefault(0L) }
        budgetDao?.let { count += runCatching { it.getUnsynced().size }.getOrDefault(0) }
        challengeDao?.let { count += runCatching { it.getUnsynced().size }.getOrDefault(0) }
        return count.toInt()
    }

    /** 把异常翻译成用户可读的失败原因（只用于展示与持久化，不参与同步流程）。 */
    private fun readableError(e: Exception): String = when {
        e is HttpException -> when (e.code()) {
            401 -> "登录已过期，请重新登录"
            in 500..599 -> "服务器暂时不可用（HTTP ${e.code()}）"
            else -> "网络请求失败（HTTP ${e.code()}）"
        }
        e is IOException -> "网络不可用，请检查网络后重试"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "同步失败"
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
                            serverId = dto.id, name = dto.name, balanceMinor = dto.resolvedBalanceMinor,
                            iconColor = dto.iconColor, iconKey = dto.resolvedIconKey, updatedAt = serverTime,
                            deleted = dto.deleted, dirty = false
                        ).also { dao.upsert(it) }
                    } else {
                        dao.upsert(
                            Account(
                                serverId = dto.id, name = dto.name, balanceMinor = dto.resolvedBalanceMinor,
                                iconColor = dto.iconColor, iconKey = dto.resolvedIconKey, updatedAt = serverTime,
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
                            name = dto.name, balanceMinor = dto.resolvedBalanceMinor,
                            iconColor = dto.iconColor, iconKey = dto.resolvedIconKey, updatedAt = serverTime,
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
                    UpdateAccountRequest(
                        name = account.name,
                        iconColor = account.iconColor,
                        iconKey = account.iconKey,
                        balanceMinor = account.balanceMinor
                    )
                )
                dao.updateServerId(account.id, dto.id, dto.updatedAt ?: 0L)
                true
            }
            else -> {
                val dto = api.createAccount(
                    CreateAccountRequest(
                        name = account.name,
                        iconColor = account.iconColor,
                        iconKey = account.iconKey,
                        balanceMinor = account.balanceMinor
                    )
                )
                dao.updateServerId(account.id, dto.id, dto.updatedAt ?: 0L)
                true
            }
        }
    }
}

private fun Bill.toRequest() = CreateBillRequest(
    amountMinor = amountMinor,
    billType = billType.value,
    categoryId = categoryId,
    categoryName = categoryName,
    subCategoryName = subCategoryName,
    accountId = accountId,
    remark = remark,
    date = date,
    sortOrder = sortOrder,
    // 经纬度：仅打点账单有值；null 上传即服务端存 NULL（PUT 全量替换语义）。
    latitude = latitude,
    longitude = longitude
)

private fun TemplateDTO.toEntity() = BillTemplate(
    serverId = this.id, label = this.label, amountMinor = this.resolvedAmountMinor,
    categoryId = this.categoryId, categoryName = this.categoryName,
    subCategoryName = this.subCategoryName, accountId = this.accountId,
    sortOrder = this.sortOrder
)
