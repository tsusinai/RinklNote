package com.example.rinklnote.data.repository

import androidx.room.withTransaction
import com.example.rinklnote.data.db.AppDatabase
import com.example.rinklnote.data.db.entity.ACCOUNT_BUCKET_NAME
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.db.entity.DailyCategoryAmount
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.data.repository.DailyReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

internal class BillRepositoryImpl(
    private val db: AppDatabase,
    private val onBillMutated: () -> Unit = {}
) : BillRepository {

    private val billDao = db.billDao()
    private val categoryDao = db.categoryDao()
    private val accountDao = db.accountDao()
    private val templateDao = db.billTemplateDao()
    private val budgetDao = db.budgetDao()
    private val chatDao = db.chatMessageDao()

    private val _expenseCategories = MutableStateFlow<List<Category>>(emptyList())
    override val expenseCategories: StateFlow<List<Category>> = _expenseCategories.asStateFlow()

    private val _incomeCategories = MutableStateFlow<List<Category>>(emptyList())
    override val incomeCategories: StateFlow<List<Category>> = _incomeCategories.asStateFlow()

    override val accounts: Flow<List<Account>> = accountDao.observeAll()

    override fun observeAccounts(): Flow<List<Account>> = accountDao.observeAll()

    override fun observeAllBills(): Flow<List<Bill>> = billDao.observeAll()
    override fun observeBillsByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>> =
        billDao.observeByMonth(monthStart, nextMonthStart)

    override fun observeTemplates(): Flow<List<BillTemplate>> = templateDao.observeAll()

    override fun observeBudgets(): Flow<List<Budget>> = budgetDao.observeAll()

    override fun observeChatMessages(): Flow<List<ChatMessage>> = chatDao.observeAll()

    override suspend fun insertChatMessage(message: ChatMessage): Long = chatDao.insert(message)

    override suspend fun countChatMessages(kind: String, since: Long): Long =
        chatDao.countSince(kind, since)

    override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double =
        billDao.getTotalExpense(monthStart, nextMonthStart) ?: 0.0

    override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double =
        billDao.getTotalIncome(monthStart, nextMonthStart) ?: 0.0

    override fun observeTotalExpense(monthStart: Long, nextMonthStart: Long): Flow<Double> =
        billDao.observeTotalExpense(monthStart, nextMonthStart).map { it ?: 0.0 }

    override fun observeTotalIncome(monthStart: Long, nextMonthStart: Long): Flow<Double> =
        billDao.observeTotalIncome(monthStart, nextMonthStart).map { it ?: 0.0 }

    override suspend fun addBill(bill: Bill): Long = db.withTransaction {
        val id = billDao.insert(bill)
        nudgeAccount(bill.accountId, balanceDelta(bill))
        id
    }.also { onBillMutated() }

    override suspend fun updateBill(bill: Bill) = db.withTransaction {
        // 编辑需按「旧账→新账」的差额回补余额；若可能改了账户，则旧账户回滚、新账户应用。
        val old = billDao.getById(bill.id)
        if (old != null) {
            if (old.accountId == bill.accountId) {
                nudgeAccount(bill.accountId, balanceDelta(bill) - balanceDelta(old))
            } else {
                nudgeAccount(old.accountId, -balanceDelta(old))
                nudgeAccount(bill.accountId, balanceDelta(bill))
            }
        }
        billDao.update(bill)
    }.also { onBillMutated() }

    override suspend fun deleteBill(bill: Bill) = db.withTransaction {
        // Soft delete locally — server gets pushed the deletion via SyncManager.
        billDao.softDelete(bill.id, System.currentTimeMillis())
        // 反向回补删除的这笔对该账户余额的影响。
        nudgeAccount(bill.accountId, -balanceDelta(bill))
    }.also { onBillMutated() }

    /** 记账对目标账户余额的增量：支出为负、收入为正。 */
    private fun balanceDelta(bill: Bill): Double =
        if (bill.billType == BillType.EXPENSE) -bill.amount else bill.amount

    /** 把余额增量应用给账户并置 dirty，等待下次全量同步推送。 */
    private suspend fun nudgeAccount(accountId: Long, delta: Double) {
        if (delta == 0.0) return
        val account = accountDao.getById(accountId) ?: return
        accountDao.update(
            account.copy(
                balance = account.balance + delta,
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
        )
    }

    override suspend fun insertAccount(account: Account): Long = accountDao.insert(account)
    override suspend fun updateAccount(account: Account) = accountDao.update(account)
    override suspend fun updateAccountLocal(account: Account) = accountDao.update(account)
    override suspend fun softDeleteAccount(account: Account) =
        accountDao.softDelete(account.id, System.currentTimeMillis())
    override suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long) =
        accountDao.updateServerId(localId, serverId, updatedAt)
    override suspend fun getUnsyncedAccounts(): List<Account> = accountDao.getUnsynced()
    override suspend fun getAccountByServerId(serverId: Long): Account? = accountDao.getByServerId(serverId)
    override suspend fun deleteAccountByServerId(serverId: Long) = accountDao.deleteByServerId(serverId)

    override suspend fun getBudget(monthStart: Long): Budget? = budgetDao.getByMonth(monthStart)

    override suspend fun upsertBudget(budget: Budget) = budgetDao.upsert(budget)

    override suspend fun getUnsyncedBudgets(): List<Budget> = budgetDao.getUnsynced()

    override suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long) =
        budgetDao.updateServerId(localId, serverId, updatedAt)

    override suspend fun deleteBudgetByServerId(serverId: Long) = budgetDao.deleteByServerId(serverId)

    override suspend fun clearLocalData() {
        // Wipe per-user data on logout. Keep categories (shared reference data).
        // ALL per-user rows are removed — the caller (ProfileScreen logout) first does a
        // best-effort push of pending rows to THIS user's server, then calls this. So it is
        // safe to wipe dirty/never-pushed rows here: a cross-user leak would otherwise push
        // a previous user's data onto the next logged-in account.
        billDao.deleteAll()
        budgetDao.deleteAll()
        templateDao.deleteAll()
        chatDao.deleteAll()
        accountDao.deleteAll()
    }

    override suspend fun getDailyReport(dayStart: Long, dayEnd: Long): DailyReport {
        val bills = billDao.getBillsByDay(dayStart, dayEnd)
        val expenseCats = billDao.getDailyExpenseSummary(dayStart, dayEnd)
        val incomeCats = billDao.getDailyIncomeSummary(dayStart, dayEnd)
        val totalExpense = bills.filter { it.billType == BillType.EXPENSE }.sumOf { it.amount }
        val totalIncome = bills.filter { it.billType == BillType.INCOME }.sumOf { it.amount }
        val date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
        return DailyReport(
            date = date,
            totalExpense = totalExpense,
            totalIncome = totalIncome,
            expenseCategories = expenseCats,
            incomeCategories = incomeCats,
            billCount = bills.size
        )
    }

    override suspend fun countUnsynced(): Long =
        billDao.countUnsynced() +
            accountDao.countUnsynced() +
            budgetDao.getUnsynced().size

    override suspend fun getAccountNet(accountId: Long): Double =
        billDao.getAccountNet(accountId) ?: 0.0

    override suspend fun reconcileAccount(account: Account, openingOffset: Double): Account {
        // 期末余额 = 期初偏移(现实里有、账里没的资金) + 该账户账单收支合计。
        // 置 dirty 让下一步同步把校正后的余额推给服务端。
        val updated = account.copy(
            balance = openingOffset + getAccountNet(account.id),
            updatedAt = System.currentTimeMillis(),
            dirty = true
        )
        accountDao.update(updated)
        return updated
    }

    override suspend fun reconcileAllAccounts(): List<Account> {
        val updated = mutableListOf<Account>()
        accountDao.getAllActive().forEach { account ->
            updated += reconcileAccount(account, 0.0)
        }
        return updated
    }

    override suspend fun getSubCategories(parentId: Long): List<SubCategory> =
        categoryDao.getSubCategories(parentId)

    override suspend fun loadReferenceData() {
        _expenseCategories.value = categoryDao.getAllByType("EXPENSE")
        _incomeCategories.value = categoryDao.getAllByType("INCOME")
        // accounts 已改为 Room Flow 暴露（observeAll），无需再写 StateFlow。
    }

    override suspend fun seedIfNeeded() {
        val catCount = categoryDao.count()
        val accCount = accountDao.count()
        if (catCount == 0) seedCategories()
        if (accCount == 0) seedAccounts()
        ensureBucket()
        // 幂等补齐二级分类：全新安装由 seedCategories 建类后经此补齐；
        // 已有安装（类已存在、跳过 seedCategories）也在此补上缺失的二级分类。
        seedSubCategories()
        loadReferenceData()
    }

    /** 确保存在「无账户」桶：全新安装由 seedAccounts 直接建；既有安装把种子「默认」改名为桶。
     *  幂等；找不到「默认」且无桶时静默跳过（默认记账仍回退到首个账户）。 */
    private suspend fun ensureBucket() {
        if (accountDao.getActiveByName(ACCOUNT_BUCKET_NAME) != null) return
        val legacy = accountDao.getActiveByName("默认") ?: return
        accountDao.update(
            legacy.copy(
                name = ACCOUNT_BUCKET_NAME,
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
        )
    }

    private suspend fun seedCategories() {
        val expenseCategories = listOf(
            Category(name = "三餐", iconName = "meals", billType = BillType.EXPENSE),
            Category(name = "日用", iconName = "daily", billType = BillType.EXPENSE),
            Category(name = "交通", iconName = "transport", billType = BillType.EXPENSE),
            Category(name = "学习", iconName = "study", billType = BillType.EXPENSE),
            Category(name = "运动", iconName = "sports", billType = BillType.EXPENSE),
            Category(name = "娱乐", iconName = "entertainment", billType = BillType.EXPENSE),
            Category(name = "网购", iconName = "shopping", billType = BillType.EXPENSE),
        )
        for (c in expenseCategories) categoryDao.insert(c)

        val incomeCategories = listOf(
            Category(name = "工资", iconName = "salary", billType = BillType.INCOME),
            Category(name = "兼职", iconName = "parttime", billType = BillType.INCOME),
            Category(name = "理财", iconName = "finance", billType = BillType.INCOME),
            Category(name = "其他", iconName = "other", billType = BillType.INCOME),
        )
        for (c in incomeCategories) categoryDao.insert(c)
    }

    /** 完整二级分类（FEATURES.md「分类管理 · 首次启动 seed 数据」）。幂等：仅插入缺失项。 */
    private suspend fun seedSubCategories() {
        val subMap = mapOf(
            "三餐" to listOf("早餐", "午餐", "晚餐", "零食"),
            "交通" to listOf("公交", "地铁", "打车", "加油"),
            "日用" to listOf("洗衣", "洗漱", "家居"),
            "学习" to listOf("书籍", "文具", "培训"),
            "运动" to listOf("健身", "跑步", "球类"),
            "娱乐" to listOf("电影", "游戏", "旅游"),
            "网购" to listOf("淘宝", "京东", "快递"),
            "工资" to listOf("基本工资", "奖金", "补贴"),
            "兼职" to listOf("劳务", "项目", "其他"),
            "理财" to listOf("利息", "基金", "股票"),
            "其他" to listOf("红包", "返还", "其他收入")
        )
        val existing = categoryDao.getAllSubCategories()
            .mapTo(mutableSetOf()) { it.parentCategoryId to it.name }
        for ((catName, subNames) in subMap) {
            val catId = categoryDao.getIdByName(catName) ?: continue
            for (subName in subNames) {
                if (catId to subName in existing) continue
                categoryDao.insertSubCategory(SubCategory(name = subName, parentCategoryId = catId))
            }
        }
    }

    private suspend fun seedAccounts() {
        val accounts = listOf(
            Account(name = "微信", iconColor = "#28C145"),
            Account(name = "支付宝", iconColor = "#06B4FD"),
            Account(name = ACCOUNT_BUCKET_NAME, iconColor = "#F97D1D"),
        )
        for (a in accounts) {
            accountDao.insert(a)
        }
    }
}
