package com.example.rinklnote.server.services

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.example.rinklnote.server.tables.*
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class BillDTO(
    val id: Long,
    // 新字段（分，权威值）；旧客户端请忽略。
    val amountMinor: Long,
    // 旧字段，仅供旧客户端，勿用；值 = Money.fromMinor(amountMinor)。
    val amount: Double,
    val billType: String,
    val categoryId: Long,
    val categoryName: String,
    val subCategoryName: String?,
    val accountId: Long,
    val remark: String?,
    val date: Long,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deleted: Boolean = false,
    // 同日内显式排序名次（App 端拖动重排）；null = 未排序。
    val sortOrder: Long? = null
)

@Serializable
data class SyncResponse(
    val bills: List<BillDTO>,
    val serverTime: Long,
    val hasMore: Boolean = false,
    // Composite cursor (updatedAt, id) for the last returned bill — clients echo
    // these back on the next page to avoid duplicate/skip when two bills share
    // the same updatedAt across a page boundary.
    val nextAfter: Long? = null,
    val nextAfterId: Long? = null
)

@Serializable
data class SubCategoryDTO(
    val id: Long,
    val name: String,
    val parentCategoryId: Long
)

@Serializable
data class CategoryDTO(
    val id: Long,
    val name: String,
    val iconName: String,
    val billType: String,
    val subCategories: List<SubCategoryDTO> = emptyList()
)

@Serializable
data class AccountDTO(
    val id: Long,
    val name: String,
    val balanceMinor: Long,
    // 旧字段，仅供旧客户端，勿用；值 = Money.fromMinor(balanceMinor)。
    val balance: Double,
    val iconColor: String,
    val updatedAt: Long = 0,
    val deleted: Boolean = false
)

class BillService {
    fun createBill(
        userId: Long,
        amountMinor: Long,
        categoryName: String?,
        remark: String?,
        source: String = "QQ"
    ): BillDTO {
        require(amountMinor > 0) { "金额必须大于0" }
        val sanitizedRemark = remark?.take(500)

        val now = System.currentTimeMillis()
        val todayStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

        // Match category by name, supporting both EXPENSE and INCOME
        val category = if (categoryName != null) {
            transaction {
                CategoriesTable.selectAll()
                    .where { CategoriesTable.name eq categoryName }
                    .singleOrNull()
            }
        } else null

        val catId: Long
        val catName: String
        val billType: String
        if (category != null) {
            catId = category[CategoriesTable.id]
            catName = category[CategoriesTable.name]
            billType = category[CategoriesTable.billType] // Use category's type (EXPENSE or INCOME)
        } else {
            val defaultCat = transaction {
                CategoriesTable.selectAll()
                    .where { CategoriesTable.billType eq "EXPENSE" }
                    .orderBy(CategoriesTable.id)
                    .firstOrNull()
                    ?: throw IllegalStateException("No default category found")
            }
            catId = defaultCat[CategoriesTable.id]
            catName = defaultCat[CategoriesTable.name]
            billType = "EXPENSE"
        }

        val accountId = accountIdFor(userId)

        val billId = transaction {
            BillsTable.insert {
                it[BillsTable.userId] = userId
                it[BillsTable.amountMinor] = amountMinor
                it[BillsTable.amount] = Money.fromMinor(amountMinor)
                it[BillsTable.billType] = billType
                it[BillsTable.categoryId] = catId
                it[BillsTable.categoryName] = catName
                it[BillsTable.accountId] = accountId
                it[BillsTable.remark] = sanitizedRemark
                it[BillsTable.date] = todayStart
                it[BillsTable.billSource] = source
                it[BillsTable.createdAt] = now
                it[BillsTable.updatedAt] = now
            } get BillsTable.id
        }

        return BillDTO(
            id = billId, amountMinor = amountMinor, amount = Money.fromMinor(amountMinor), billType = billType,
            categoryId = catId, categoryName = catName,
            subCategoryName = null, accountId = accountId,
            remark = sanitizedRemark, date = todayStart, source = source,
            createdAt = now, updatedAt = now
        )
    }

    fun createWebBill(
        userId: Long,
        amountMinor: Long,
        billType: String,
        categoryId: Long,
        categoryName: String,
        subCategoryName: String?,
        accountId: Long,
        remark: String?,
        date: Long?,
        sortOrder: Long? = null
    ): BillDTO {
        require(amountMinor > 0) { "金额必须大于0" }
        require(billType == "EXPENSE" || billType == "INCOME") { "账单类型不合法" }
        val ownsAccount = transaction {
            AccountsTable.selectAll()
                .where { (AccountsTable.id eq accountId) and (AccountsTable.userId eq userId) }
                .any()
        }
        require(ownsAccount) { "账户不存在" }
        val now = System.currentTimeMillis()
        val billDate = date ?: LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

        val billId = transaction {
            BillsTable.insert {
                it[BillsTable.userId] = userId
                it[BillsTable.amountMinor] = amountMinor
                it[BillsTable.amount] = Money.fromMinor(amountMinor)
                it[BillsTable.billType] = billType
                it[BillsTable.categoryId] = categoryId
                it[BillsTable.categoryName] = categoryName
                it[BillsTable.subCategoryName] = subCategoryName
                it[BillsTable.accountId] = accountId
                it[BillsTable.remark] = remark
                it[BillsTable.date] = billDate
                it[BillsTable.billSource] = "WEB"
                it[BillsTable.createdAt] = now
                it[BillsTable.updatedAt] = now
                it[BillsTable.sortOrder] = sortOrder
            } get BillsTable.id
        }

        return BillDTO(
            id = billId, amountMinor = amountMinor, amount = Money.fromMinor(amountMinor), billType = billType,
            categoryId = categoryId, categoryName = categoryName,
            subCategoryName = subCategoryName, accountId = accountId,
            remark = remark, date = billDate, source = "WEB",
            createdAt = now, updatedAt = now, sortOrder = sortOrder
        )
    }

    fun deleteBill(billId: Long, userId: Long): Boolean {
        val now = System.currentTimeMillis()
        return transaction {
            val updated = BillsTable.update({
                (BillsTable.id eq billId) and (BillsTable.userId eq userId)
            }) {
                it[deleted] = true
                it[updatedAt] = now
            }
            updated > 0
        }
    }

    fun syncBills(userId: Long, after: Long? = null, afterId: Long? = null, limit: Int = 200): SyncResponse {
        val now = System.currentTimeMillis()
        val page = transaction {
            val query = BillsTable.selectAll()
                .where { BillsTable.userId eq userId }
                .orderBy(BillsTable.updatedAt to SortOrder.ASC, BillsTable.id to SortOrder.ASC)

            if (after != null && after > 0) {
                if (afterId != null) {
                    // Keyset cursor: strictly after (updatedAt, id). Handles concurrent
                    // writes that bump several rows to the same updatedAt.
                    query.andWhere {
                        (BillsTable.updatedAt greater after) or
                            ((BillsTable.updatedAt eq after) and (BillsTable.id greater afterId))
                    }
                } else {
                    query.andWhere { BillsTable.updatedAt greater after }
                }
            }

            query.limit(limit + 1).map {
                BillDTO(
                    id = it[BillsTable.id],
                    amountMinor = it[BillsTable.amountMinor] ?: 0L,
                    amount = Money.fromMinor(it[BillsTable.amountMinor] ?: 0L),
                    billType = it[BillsTable.billType],
                    categoryId = it[BillsTable.categoryId],
                    categoryName = it[BillsTable.categoryName],
                    subCategoryName = it[BillsTable.subCategoryName],
                    accountId = it[BillsTable.accountId],
                    remark = it[BillsTable.remark],
                    date = it[BillsTable.date],
                    source = it[BillsTable.billSource],
                    createdAt = it[BillsTable.createdAt],
                    updatedAt = it[BillsTable.updatedAt],
                    deleted = it[BillsTable.deleted],
                    sortOrder = it[BillsTable.sortOrder]
                )
            }
        }
        val hasMore = page.size > limit
        val pageBills = page.take(limit)
        val last = pageBills.lastOrNull()
        return SyncResponse(
            bills = pageBills,
            serverTime = now,
            hasMore = hasMore,
            nextAfter = last?.updatedAt,
            nextAfterId = last?.id
        )
    }

    /**
     * All non-deleted bills for a user, page-fetched with the composite cursor so
     * insight queries never truncate at 200 rows or include soft-deleted bills.
     */
    fun allBills(userId: Long): List<BillDTO> {
        var after: Long? = null
        var afterId: Long? = null
        val result = mutableListOf<BillDTO>()
        do {
            val page = syncBills(userId, after, afterId, 200)
            result += page.bills.filter { !it.deleted }
            after = page.nextAfter
            afterId = page.nextAfterId
        } while (page.hasMore && after != null)
        return result
    }

    // ── 每用户账户 ──────────────────────────────────────────────

    private fun ResultRow.toAccountDto() = AccountDTO(
        id = this[AccountsTable.id],
        name = this[AccountsTable.name],
        balanceMinor = this[AccountsTable.balanceMinor] ?: 0L,
        balance = Money.fromMinor(this[AccountsTable.balanceMinor] ?: 0L),
        iconColor = this[AccountsTable.iconColor],
        updatedAt = this[AccountsTable.updatedAt],
        deleted = this[AccountsTable.deleted]
    )

    /** 按 (user_id,name) 幂等补默认 3 账户：缺哪个名补哪个，自定义账户共存。 */
    fun ensureDefaultAccounts(userId: Long) {
        val now = System.currentTimeMillis()
        transaction {
            val existing = AccountsTable.selectAll()
                .where { AccountsTable.userId eq userId }
                .map { it[AccountsTable.name] }
                .toSet()
            listOf(
                Triple("微信", "#28C145", 0.0),
                Triple("支付宝", "#06B4FD", 0.0),
                Triple("无账户", "#F97D1D", 0.0)
            ).forEach { (name, color, balance) ->
                if (name in existing) return@forEach
                AccountsTable.insert {
                    it[AccountsTable.userId] = userId
                    it[AccountsTable.name] = name
                    it[AccountsTable.iconColor] = color
                    it[AccountsTable.balanceMinor] = Money.toMinor(balance)
                    it[AccountsTable.balance] = balance
                    it[AccountsTable.updatedAt] = now
                }
            }
        }
    }

    fun accountsFor(userId: Long): List<AccountDTO> {
        ensureDefaultAccounts(userId)
        return transaction {
            AccountsTable.selectAll()
                .where { (AccountsTable.userId eq userId) and (AccountsTable.deleted eq false) }
                .orderBy(AccountsTable.id)
                .map { it.toAccountDto() }
        }
    }

    fun createAccount(userId: Long, name: String, iconColor: String, balanceMinor: Long): AccountDTO {
        require(name.isNotBlank()) { "账户名不能为空" }
        require(balanceMinor >= 0) { "余额不能为负" }
        // (user_id, name) 唯一索引：同名账户对同一用户不可重复（含默认账户名）
        val exists = transaction {
            AccountsTable.selectAll()
                .where { (AccountsTable.userId eq userId) and (AccountsTable.name eq name) and (AccountsTable.deleted eq false) }
                .any()
        }
        require(!exists) { "账户已存在" }
        val now = System.currentTimeMillis()
        val id = transaction {
            AccountsTable.insert {
                it[AccountsTable.userId] = userId
                it[AccountsTable.name] = name
                it[AccountsTable.iconColor] = iconColor
                it[AccountsTable.balanceMinor] = balanceMinor
                it[AccountsTable.balance] = Money.fromMinor(balanceMinor)
                it[AccountsTable.updatedAt] = now
            } get AccountsTable.id
        }
        return AccountDTO(id, name, balanceMinor, Money.fromMinor(balanceMinor), iconColor, now, false)
    }

    fun renameAccount(id: Long, userId: Long, name: String, iconColor: String): AccountDTO? = transaction {
        val row = AccountsTable.selectAll()
            .where { (AccountsTable.id eq id) and (AccountsTable.userId eq userId) }
            .singleOrNull() ?: return@transaction null
        // 不与同用户其他未删账户重名
        val dup = AccountsTable.selectAll()
            .where { (AccountsTable.userId eq userId) and (AccountsTable.name eq name) and (AccountsTable.deleted eq false) and (AccountsTable.id neq id) }
            .any()
        require(!dup) { "账户已存在" }
        val now = System.currentTimeMillis()
        AccountsTable.update({ AccountsTable.id eq id }) {
            it[AccountsTable.name] = name
            it[AccountsTable.iconColor] = iconColor
            it[AccountsTable.updatedAt] = now
        }
        row.toAccountDto().copy(name = name, iconColor = iconColor, updatedAt = now)
    }

    fun deleteAccount(id: Long, userId: Long): Boolean {
        val now = System.currentTimeMillis()
        return transaction {
            val updated = AccountsTable.update({
                (AccountsTable.id eq id) and (AccountsTable.userId eq userId)
            }) {
                it[deleted] = true
                it[updatedAt] = now
            }
            updated > 0
        }
    }

    /** 当前用户首个未删除账户 id；无则先播种默认。 */
    fun accountIdFor(userId: Long): Long {
        ensureDefaultAccounts(userId)
        return transaction {
            AccountsTable.selectAll()
                .where { (AccountsTable.userId eq userId) and (AccountsTable.deleted eq false) }
                .orderBy(AccountsTable.id)
                .first()[AccountsTable.id]
        }
    }

    fun seedIfNeeded() {
        transaction {
            if (CategoriesTable.selectAll().empty()) {
                seedCategories()
            }
            if (AccountsTable.selectAll().empty()) {
                seedAccounts()
            }
            // 幂等补齐二级分类：每次启动检查缺失项；既有库（子分类表非空时旧逻辑会跳过）也补全
            seedSubCategories()
        }
    }

    private fun seedCategories() {
        val expenseCategories = listOf(
            "三餐" to "meals",
            "日用" to "daily",
            "交通" to "transport",
            "学习" to "study",
            "运动" to "sports",
            "娱乐" to "entertainment",
            "网购" to "shopping"
        )
        expenseCategories.forEach { (name, icon) ->
            CategoriesTable.insert {
                it[CategoriesTable.name] = name
                it[CategoriesTable.iconName] = icon
                it[CategoriesTable.billType] = "EXPENSE"
            }
        }

        val incomeCategories = listOf(
            "工资" to "salary",
            "兼职" to "parttime",
            "理财" to "finance",
            "其他" to "other"
        )
        incomeCategories.forEach { (name, icon) ->
            CategoriesTable.insert {
                it[CategoriesTable.name] = name
                it[CategoriesTable.iconName] = icon
                it[CategoriesTable.billType] = "INCOME"
            }
        }
    }

    private fun seedSubCategories() {
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
        val existing = SubCategoriesTable.selectAll()
            .map { it[SubCategoriesTable.parentCategoryId] to it[SubCategoriesTable.name] }
            .toSet()
        for ((catName, subNames) in subMap) {
            val catId = CategoriesTable.selectAll()
                .where { CategoriesTable.name eq catName }
                .singleOrNull()?.get(CategoriesTable.id) ?: continue
            for (subName in subNames) {
                if (catId to subName in existing) continue
                SubCategoriesTable.insert {
                    it[SubCategoriesTable.name] = subName
                    it[SubCategoriesTable.parentCategoryId] = catId
                }
            }
        }
    }

    private fun seedAccounts() {
        val accounts = listOf(
            Triple("微信", "#28C145", 0.0),
            Triple("支付宝", "#06B4FD", 0.0),
            Triple("无账户", "#F97D1D", 0.0)
        )
        accounts.forEach { (name, color, balance) ->
            AccountsTable.insert {
                it[AccountsTable.name] = name
                it[AccountsTable.iconColor] = color
                it[AccountsTable.balanceMinor] = Money.toMinor(balance)
                it[AccountsTable.balance] = balance
            }
        }
    }

    data class MonthStats(
        val totalExpenseMinor: Long,
        val totalIncomeMinor: Long,
        val topExpenseCategories: List<Pair<String, Long>>,
        // 日报需要总笔数与收入分类；收入侧此前是空实现，见 InsightService.dailyReport。
        val topIncomeCategories: List<Pair<String, Long>> = emptyList(),
        val billCount: Long = 0
    )

    /**
     * Aggregates a single month with SQL SUM/GROUP BY instead of loading every
     * bill into memory. Used by the insight endpoints.
     */
    fun monthlyStats(userId: Long, monthStart: Long, nextMonthStart: Long): MonthStats = transaction {
        val totalExpense = BillsTable.select(BillsTable.amountMinor.sum())
            .where {
                (BillsTable.userId eq userId) and
                    (BillsTable.deleted eq false) and
                    (BillsTable.billType eq "EXPENSE") and
                    (BillsTable.date greaterEq monthStart) and
                    (BillsTable.date less nextMonthStart)
            }
            .first()[BillsTable.amountMinor.sum()] ?: 0L
        val totalIncome = BillsTable.select(BillsTable.amountMinor.sum())
            .where {
                (BillsTable.userId eq userId) and
                    (BillsTable.deleted eq false) and
                    (BillsTable.billType eq "INCOME") and
                    (BillsTable.date greaterEq monthStart) and
                    (BillsTable.date less nextMonthStart)
            }
            .first()[BillsTable.amountMinor.sum()] ?: 0L
        val topCategories = BillsTable.select(BillsTable.categoryName, BillsTable.amountMinor.sum())
            .where {
                (BillsTable.userId eq userId) and
                    (BillsTable.deleted eq false) and
                    (BillsTable.billType eq "EXPENSE") and
                    (BillsTable.date greaterEq monthStart) and
                    (BillsTable.date less nextMonthStart)
            }
            .groupBy(BillsTable.categoryName)
            .orderBy(BillsTable.amountMinor.sum() to SortOrder.DESC)
            .limit(5)
            .map { it[BillsTable.categoryName] to (it[BillsTable.amountMinor.sum()] ?: 0L) }

        val topIncomeCategories = BillsTable.select(BillsTable.categoryName, BillsTable.amountMinor.sum())
            .where {
                (BillsTable.userId eq userId) and
                    (BillsTable.deleted eq false) and
                    (BillsTable.billType eq "INCOME") and
                    (BillsTable.date greaterEq monthStart) and
                    (BillsTable.date less nextMonthStart)
            }
            .groupBy(BillsTable.categoryName)
            .orderBy(BillsTable.amountMinor.sum() to SortOrder.DESC)
            .limit(5)
            .map { it[BillsTable.categoryName] to (it[BillsTable.amountMinor.sum()] ?: 0L) }
        // 用 Query.count() 而不是手工 select(id.count())：后者要构造两次等价表达式，
        // 依赖 Exposed 表达式相等语义才能从结果行取值；count() 直接走聚合计数，无此隐患。
        val billCount = BillsTable.selectAll()
            .where {
                (BillsTable.userId eq userId) and
                    (BillsTable.deleted eq false) and
                    (BillsTable.date greaterEq monthStart) and
                    (BillsTable.date less nextMonthStart)
            }
            .count()

        // 整数分聚合已无浮点漂移，无需再四舍五入。
        MonthStats(
            totalExpense,
            totalIncome,
            topCategories,
            topIncomeCategories,
            billCount
        )
    }

    fun getCategories(): List<CategoryDTO> = transaction {
        // Load all subcategories in one query and group by parent — avoids the
        // per-category SELECT that was the N+1 here.
        val subGroups = SubCategoriesTable.selectAll()
            .orderBy(SubCategoriesTable.id to SortOrder.ASC)
            .groupBy { it[SubCategoriesTable.parentCategoryId] }
            .mapValues { (_, rows) ->
                rows.map {
                    SubCategoryDTO(
                        id = it[SubCategoriesTable.id],
                        name = it[SubCategoriesTable.name],
                        parentCategoryId = it[SubCategoriesTable.parentCategoryId]
                    )
                }
            }
        CategoriesTable.selectAll()
            .orderBy(CategoriesTable.billType to SortOrder.ASC, CategoriesTable.id to SortOrder.ASC)
            .map { row ->
                CategoryDTO(
                    id = row[CategoriesTable.id],
                    name = row[CategoriesTable.name],
                    iconName = row[CategoriesTable.iconName],
                    billType = row[CategoriesTable.billType],
                    subCategories = subGroups[row[CategoriesTable.id]] ?: emptyList()
                )
            }
    }

    fun updateAccountBalance(id: Long, balanceMinor: Long, userId: Long): AccountDTO? = transaction {
        val row = AccountsTable.selectAll()
            .where { (AccountsTable.id eq id) and (AccountsTable.userId eq userId) }
            .singleOrNull()
            ?: return@transaction null
        val now = System.currentTimeMillis()
        AccountsTable.update({ AccountsTable.id eq id }) {
            it[AccountsTable.balanceMinor] = balanceMinor
            it[AccountsTable.balance] = Money.fromMinor(balanceMinor)
            it[AccountsTable.updatedAt] = now
        }
        row.toAccountDto().copy(balanceMinor = balanceMinor, balance = Money.fromMinor(balanceMinor), updatedAt = now)
    }

    fun getBill(id: Long, userId: Long): BillDTO? = transaction {
        BillsTable.selectAll()
            .where { (BillsTable.id eq id) and (BillsTable.userId eq userId) }
            .singleOrNull()?.toBillDto()
    }

    private fun ResultRow.toBillDto() = BillDTO(
        id = this[BillsTable.id],
        amountMinor = this[BillsTable.amountMinor] ?: 0L,
        amount = Money.fromMinor(this[BillsTable.amountMinor] ?: 0L),
        billType = this[BillsTable.billType],
        categoryId = this[BillsTable.categoryId],
        categoryName = this[BillsTable.categoryName],
        subCategoryName = this[BillsTable.subCategoryName],
        accountId = this[BillsTable.accountId],
        remark = this[BillsTable.remark],
        date = this[BillsTable.date],
        source = this[BillsTable.billSource],
        createdAt = this[BillsTable.createdAt],
        updatedAt = this[BillsTable.updatedAt],
        deleted = this[BillsTable.deleted],
        sortOrder = this[BillsTable.sortOrder]
    )
}
