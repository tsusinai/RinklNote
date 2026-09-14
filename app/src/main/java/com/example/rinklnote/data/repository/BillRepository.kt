package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.DailyCategoryAmount
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.data.db.entity.SubCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 日报聚合结果：金额一律为「分」（minor unit）。 */
data class DailyReport(
    val date: String,
    val totalExpense: Long,
    val totalIncome: Long,
    val expenseCategories: List<DailyCategoryAmount>,
    val incomeCategories: List<DailyCategoryAmount>,
    val billCount: Int
)

@androidx.compose.runtime.Stable
interface BillRepository {
    val expenseCategories: StateFlow<List<Category>>
    val incomeCategories: StateFlow<List<Category>>
    val accounts: Flow<List<Account>>

    fun observeAllBills(): Flow<List<Bill>>
    fun observeBillsByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>>
    fun observeTemplates(): Flow<List<BillTemplate>>
    fun observeBudgets(): Flow<List<Budget>>
    fun observeChatMessages(): Flow<List<ChatMessage>>

    suspend fun insertChatMessage(message: ChatMessage): Long
    suspend fun countChatMessages(kind: String, since: Long): Long

    // 金额一律为「分」（minor unit）
    suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Long
    suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Long
    fun observeTotalExpense(monthStart: Long, nextMonthStart: Long): Flow<Long>
    fun observeTotalIncome(monthStart: Long, nextMonthStart: Long): Flow<Long>
    suspend fun addBill(bill: Bill): Long
    suspend fun updateBill(bill: Bill)
    suspend fun deleteBill(bill: Bill)
    /** 拖动重排：同日新序整批落库（VM 已统一盖章 dirty/updatedAt/sortOrder）。 */
    suspend fun reorderBills(bills: List<Bill>)

    // ── CSV 导入（ui/screen/import 的 BillImportViewModel 使用）──
    // 这几个方法带默认空实现：既有测试里的 FakeBillRepository 无需逐个补实现即可编译；
    // 真正实现见 BillRepositoryImpl（事务批量插入 + 账户余额联动）。
    /**
     * 批量导入账单：统一盖章 dirty=1 / createdAt / updatedAt 后整批插入（单事务，失败全回滚），
     * 并按账户归集回补余额增量（支出减、收入加）。server_id 置空 → SyncManager 既有推送自动上传。
     * @return 成功插入条数
     */
    suspend fun importBills(bills: List<Bill>): Int = 0
    /** 全部一级分类（支出 + 收入）快照，供导入预检按名匹配。 */
    suspend fun getAllCategories(): List<Category> = emptyList()
    /** 全部活跃账户快照，供导入预检按名匹配 / 取兜底账户。 */
    suspend fun getActiveAccounts(): List<Account> = emptyList()
    /** 某天（[dayStart, dayEnd)）未删除账单，供导入按「同日 + 同额 + 同分类」查重。 */
    suspend fun getBillsByDay(dayStart: Long, dayEnd: Long): List<Bill> = emptyList()


    // Accounts — reactive source + per-user CRUD/sync
    fun observeAccounts(): Flow<List<Account>>
    suspend fun insertAccount(account: Account): Long
    suspend fun updateAccount(account: Account)
    suspend fun updateAccountLocal(account: Account)
    suspend fun softDeleteAccount(account: Account)
    suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long)
    suspend fun getUnsyncedAccounts(): List<Account>
    suspend fun getAccountByServerId(serverId: Long): Account?
    suspend fun deleteAccountByServerId(serverId: Long)

    suspend fun getSubCategories(parentId: Long): List<SubCategory>
    suspend fun getAllSubCategories(): List<SubCategory>
    suspend fun getBudget(monthStart: Long): Budget?
    suspend fun upsertBudget(budget: Budget)
    suspend fun getUnsyncedBudgets(): List<Budget>
    suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long)
    suspend fun deleteBudgetByServerId(serverId: Long)
    suspend fun clearLocalData()
    suspend fun loadReferenceData()
    suspend fun seedIfNeeded()

    // Unsynced (pending push) count, used by logout's push-first-then-wipe.
    suspend fun getDailyReport(dayStart: Long, dayEnd: Long): DailyReport

    suspend fun countUnsynced(): Long

    // Balance reconciliation against each account's own record net (income − expense)，单位：分。
    suspend fun getAccountNet(accountId: Long): Long
    suspend fun reconcileAccount(account: Account, openingOffset: Long): Account
    suspend fun reconcileAllAccounts(): List<Account>
}
