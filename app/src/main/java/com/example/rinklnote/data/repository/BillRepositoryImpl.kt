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

    override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Long =
        billDao.getTotalExpense(monthStart, nextMonthStart) ?: 0L

    override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Long =
        billDao.getTotalIncome(monthStart, nextMonthStart) ?: 0L

    override fun observeTotalExpense(monthStart: Long, nextMonthStart: Long): Flow<Long> =
        billDao.observeTotalExpense(monthStart, nextMonthStart).map { it ?: 0L }

    override fun observeTotalIncome(monthStart: Long, nextMonthStart: Long): Flow<Long> =
        billDao.observeTotalIncome(monthStart, nextMonthStart).map { it ?: 0L }

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


    override suspend fun reorderBills(bills: List<Bill>) = db.withTransaction {
        // 仅调序：金额/账户不变，无需触碰余额；VM 已统一盖章 dirty/updatedAt/sortOrder。
        bills.forEach { billDao.update(it) }
    }

    // ── CSV 导入 ──

    override suspend fun getAllCategories(): List<Category> =
        categoryDao.getAllByType("EXPENSE") + categoryDao.getAllByType("INCOME")

    override suspend fun getActiveAccounts(): List<Account> = accountDao.getAllActive()

    override suspend fun getBillsByDay(dayStart: Long, dayEnd: Long): List<Bill> =
        billDao.getBillsByDay(dayStart, dayEnd)

    /**
     * 批量导入账单（CSV 导入落库）：
     * - 与 [addBill] 相同的语义：本地插入即待同步（dirty=1 且 server_id 为空，
     *   SyncManager 既有推送逻辑会自动上传，这里不直接调 SyncManager）；
     * - 账户余额按收支方向回补（支出减、收入加），与记账入口保持一致；
     * - 金额/日期/分类/账户已由 VM 解析装配好，这里统一盖章 createdAt / updatedAt / dirty，
     *   并把 id / server_id / sort_order 清零，避免调用方漏填带入脏值；
     * - 单事务整批：中途失败全部回滚，不会出现半批脏数据；余额增量按账户归集，只 nudge 一次。
     */
    override suspend fun importBills(bills: List<Bill>): Int {
        if (bills.isEmpty()) return 0
        val now = System.currentTimeMillis()
        return db.withTransaction {
            val deltaByAccount = mutableMapOf<Long, Long>()
            var inserted = 0
            for (bill in bills) {
                billDao.insert(
                    bill.copy(
                        id = 0,
                        serverId = null,
                        sortOrder = null,
                        createdAt = now,
                        updatedAt = now,
                        dirty = true
                    )
                )
                val delta = balanceDelta(bill)
                deltaByAccount[bill.accountId] = (deltaByAccount[bill.accountId] ?: 0L) + delta
                inserted++
            }
            deltaByAccount.forEach { (accountId, delta) -> nudgeAccount(accountId, delta) }
            inserted
        }.also { onBillMutated() }
    }


    /** 记账对目标账户余额的增量：支出为负、收入为正。 */
    private fun balanceDelta(bill: Bill): Long =
        if (bill.billType == BillType.EXPENSE) -bill.amountMinor else bill.amountMinor

    /** 把余额增量应用给账户并置 dirty，等待下次全量同步推送。 */
    private suspend fun nudgeAccount(accountId: Long, delta: Long) {
        if (delta == 0L) return
        val account = accountDao.getById(accountId) ?: return
        accountDao.update(
            account.copy(
                balanceMinor = account.balanceMinor + delta,
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
        val totalExpense = bills.filter { it.billType == BillType.EXPENSE }.sumOf { it.amountMinor }
        val totalIncome = bills.filter { it.billType == BillType.INCOME }.sumOf { it.amountMinor }
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

    override suspend fun getAccountNet(accountId: Long): Long =
        billDao.getAccountNet(accountId) ?: 0L

    override suspend fun reconcileAccount(account: Account, openingOffset: Long): Account {
        // 期末余额 = 期初偏移(现实里有、账里没的资金) + 该账户账单收支合计。
        // 置 dirty 让下一步同步把校正后的余额推给服务端。
        val updated = account.copy(
            balanceMinor = openingOffset + getAccountNet(account.id),
            updatedAt = System.currentTimeMillis(),
            dirty = true
        )
        accountDao.update(updated)
        return updated
    }

    override suspend fun reconcileAllAccounts(): List<Account> {
        val updated = mutableListOf<Account>()
        accountDao.getAllActive().forEach { account ->
            updated += reconcileAccount(account, 0L)
        }
        return updated
    }

    override suspend fun getSubCategories(parentId: Long): List<SubCategory> =
        categoryDao.getSubCategories(parentId)

    override suspend fun getAllSubCategories(): List<SubCategory> =
        categoryDao.getAllSubCategories()

    override suspend fun loadReferenceData() {
        _expenseCategories.value = categoryDao.getAllByType("EXPENSE")
        _incomeCategories.value = categoryDao.getAllByType("INCOME")
        // accounts 已改为 Room Flow 暴露（observeAll），无需再写 StateFlow。
    }

    override suspend fun seedIfNeeded() {
        val accCount = accountDao.count()
        // 幂等补齐一级分类：insert 走 IGNORE + (name, bill_type) 唯一索引，已存在的自动跳过。
        // 老安装升级后在此补上新增分类，id 按列表顺序追加续排，与服务端 seed 顺序逐字一致。
        seedCategories()
        if (accCount == 0) seedAccounts()
        ensureBucket()
        // 幂等补齐二级分类：全新安装由 seedCategories 建类后经此补齐；
        // 已有安装也在此补上缺失的二级分类（含老分类追加的新子项）。
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

    /** 完整一级分类（FEATURES.md「分类管理 · 首次启动 seed 数据」）。
     *  顺序即 id 顺序，必须与服务端 BillService.seedCategories() 逐字一致；只允许追加，不许重排。 */
    private suspend fun seedCategories() {
        val expenseCategories = listOf(
            Category(name = "三餐", iconName = "meals", billType = BillType.EXPENSE),
            Category(name = "日用", iconName = "daily", billType = BillType.EXPENSE),
            Category(name = "交通", iconName = "transport", billType = BillType.EXPENSE),
            Category(name = "学习", iconName = "study", billType = BillType.EXPENSE),
            Category(name = "运动", iconName = "sports", billType = BillType.EXPENSE),
            Category(name = "娱乐", iconName = "entertainment", billType = BillType.EXPENSE),
            Category(name = "网购", iconName = "shopping", billType = BillType.EXPENSE),
            Category(name = "医疗", iconName = "medical", billType = BillType.EXPENSE),
            Category(name = "居家", iconName = "home", billType = BillType.EXPENSE),
            Category(name = "人情", iconName = "social", billType = BillType.EXPENSE),
            Category(name = "宠物", iconName = "pet", billType = BillType.EXPENSE),
            Category(name = "美妆个护", iconName = "beauty", billType = BillType.EXPENSE),
            Category(name = "服饰", iconName = "clothing", billType = BillType.EXPENSE),
            Category(name = "母婴", iconName = "baby", billType = BillType.EXPENSE),
            Category(name = "汽车", iconName = "car", billType = BillType.EXPENSE),
            Category(name = "数码", iconName = "digital", billType = BillType.EXPENSE),
            Category(name = "保险", iconName = "insurance", billType = BillType.EXPENSE),
            Category(name = "旅行", iconName = "travel", billType = BillType.EXPENSE),
        )
        for (c in expenseCategories) categoryDao.insert(c)

        val incomeCategories = listOf(
            Category(name = "工资", iconName = "salary", billType = BillType.INCOME),
            Category(name = "兼职", iconName = "parttime", billType = BillType.INCOME),
            Category(name = "理财", iconName = "finance", billType = BillType.INCOME),
            Category(name = "其他", iconName = "other", billType = BillType.INCOME),
            Category(name = "报销", iconName = "reimburse", billType = BillType.INCOME),
            Category(name = "二手转卖", iconName = "resale", billType = BillType.INCOME),
            Category(name = "红包礼金", iconName = "redpacket", billType = BillType.INCOME),
        )
        for (c in incomeCategories) categoryDao.insert(c)
    }

    /** 完整二级分类（FEATURES.md「分类管理 · 首次启动 seed 数据」）。幂等：仅插入缺失项。
     *  顺序即 id 顺序，必须与服务端 BillService.seedSubCategories() 逐字一致；只允许追加，不许重排
     *  （budgets.subCategoryId 引用此 id，重排会让既有预算错位）。 */
    private suspend fun seedSubCategories() {
        val subMap = mapOf(
            "三餐" to listOf("早餐", "午餐", "晚餐", "零食", "外卖", "饮品"),
            "交通" to listOf("公交", "地铁", "打车", "加油", "停车费", "火车机票", "共享单车"),
            "日用" to listOf("洗衣", "洗漱", "家居", "纸品清洁"),
            "学习" to listOf("书籍", "文具", "培训", "考试", "课程"),
            "运动" to listOf("健身", "跑步", "球类"),
            "娱乐" to listOf("电影", "游戏", "旅游", "演出", "KTV"),
            "网购" to listOf("淘宝", "京东", "快递"),
            "医疗" to listOf("门诊", "药品", "体检", "口腔", "眼镜"),
            "居家" to listOf("房租", "房贷", "物业", "水电燃气", "宽带"),
            "人情" to listOf("红包礼金", "礼物", "请客", "随礼"),
            "宠物" to listOf("粮食", "医疗", "用品", "洗护"),
            "美妆个护" to listOf("护肤彩妆", "理发美发", "美容"),
            "服饰" to listOf("衣裤", "鞋帽", "配饰"),
            "母婴" to listOf("奶粉尿布", "玩具", "早教"),
            "汽车" to listOf("加油", "保养维修", "保险", "洗车"),
            "数码" to listOf("手机电脑", "配件", "软件会员"),
            "保险" to listOf("社保商保", "车险"),
            "旅行" to listOf("机票火车", "酒店", "景点门票"),
            "工资" to listOf("基本工资", "奖金", "补贴"),
            "兼职" to listOf("劳务", "项目", "其他"),
            "理财" to listOf("利息", "基金", "股票"),
            "其他" to listOf("红包", "返还", "其他收入"),
            "报销" to listOf("差旅报销", "日常报销"),
            "二手转卖" to listOf("闲置出售", "回款"),
            "红包礼金" to listOf("收红包", "压岁钱", "礼金")
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
            Account(name = "微信", iconColor = "#28C145", iconKey = "WECHAT"),
            Account(name = "支付宝", iconColor = "#06B4FD", iconKey = "ALIPAY"),
            Account(name = ACCOUNT_BUCKET_NAME, iconColor = "#F97D1D", iconKey = "OTHER"),
        )
        for (a in accounts) {
            accountDao.insert(a)
        }
    }
}
