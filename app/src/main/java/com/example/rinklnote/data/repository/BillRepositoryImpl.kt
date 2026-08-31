package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.AppDatabase
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.data.db.entity.SubCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BillRepositoryImpl(db: AppDatabase) : BillRepository {

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

    override suspend fun addBill(bill: Bill): Long = billDao.insert(bill)

    override suspend fun updateBill(bill: Bill) = billDao.update(bill)

    override suspend fun deleteBill(bill: Bill) {
        // Soft delete locally — server gets pushed the deletion via SyncManager.
        billDao.softDelete(bill.id, System.currentTimeMillis())
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
        // Only server-synced rows are removed — never-pushed bills/accounts survive
        // logout so they are not lost and get pushed after the next login.
        billDao.deleteSynced()
        budgetDao.deleteAll()
        templateDao.deleteAll()
        chatDao.deleteAll()
        accountDao.deleteSyncedClean()
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
        // 幂等补齐二级分类：全新安装由 seedCategories 建类后经此补齐；
        // 已有安装（类已存在、跳过 seedCategories）也在此补上缺失的二级分类。
        seedSubCategories()
        loadReferenceData()
    }

    private suspend fun seedCategories() {
        val expenseCategories = listOf(
            Category(name = "三餐", iconName = "meals", billType = "EXPENSE"),
            Category(name = "日用", iconName = "daily", billType = "EXPENSE"),
            Category(name = "交通", iconName = "transport", billType = "EXPENSE"),
            Category(name = "学习", iconName = "study", billType = "EXPENSE"),
            Category(name = "运动", iconName = "sports", billType = "EXPENSE"),
            Category(name = "娱乐", iconName = "entertainment", billType = "EXPENSE"),
            Category(name = "网购", iconName = "shopping", billType = "EXPENSE"),
        )
        for (c in expenseCategories) categoryDao.insert(c)

        val incomeCategories = listOf(
            Category(name = "工资", iconName = "salary", billType = "INCOME"),
            Category(name = "兼职", iconName = "parttime", billType = "INCOME"),
            Category(name = "理财", iconName = "finance", billType = "INCOME"),
            Category(name = "其他", iconName = "other", billType = "INCOME"),
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
            Account(name = "默认", iconColor = "#F97D1D"),
        )
        for (a in accounts) {
            accountDao.insert(a)
        }
    }
}
