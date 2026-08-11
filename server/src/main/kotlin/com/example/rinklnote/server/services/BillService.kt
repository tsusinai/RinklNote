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
    val deleted: Boolean = false
)

@Serializable
data class SyncResponse(
    val bills: List<BillDTO>,
    val serverTime: Long,
    val hasMore: Boolean = false
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
    val balance: Double,
    val iconColor: String
)

class BillService {
    fun createBill(
        userId: Long,
        amount: Double,
        categoryName: String?,
        remark: String?,
        source: String = "QQ"
    ): BillDTO {
        require(amount > 0 && amount.isFinite()) { "金额必须大于0" }
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

        val account = transaction {
            AccountsTable.selectAll().orderBy(AccountsTable.id).firstOrNull()
                ?: throw IllegalStateException("No account found")
        }

        val billId = transaction {
            BillsTable.insert {
                it[BillsTable.userId] = userId
                it[BillsTable.amount] = amount
                it[BillsTable.billType] = billType
                it[BillsTable.categoryId] = catId
                it[BillsTable.categoryName] = catName
                it[BillsTable.accountId] = account[AccountsTable.id]
                it[BillsTable.remark] = sanitizedRemark
                it[BillsTable.date] = todayStart
                it[BillsTable.billSource] = source
                it[BillsTable.createdAt] = now
                it[BillsTable.updatedAt] = now
            } get BillsTable.id
        }

        return BillDTO(
            id = billId, amount = amount, billType = billType,
            categoryId = catId, categoryName = catName,
            subCategoryName = null, accountId = account[AccountsTable.id],
            remark = sanitizedRemark, date = todayStart, source = source,
            createdAt = now, updatedAt = now
        )
    }

    fun createWebBill(
        userId: Long,
        amount: Double,
        billType: String,
        categoryId: Long,
        categoryName: String,
        subCategoryName: String?,
        accountId: Long,
        remark: String?,
        date: Long?
    ): BillDTO {
        val now = System.currentTimeMillis()
        val billDate = date ?: LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

        val billId = transaction {
            BillsTable.insert {
                it[BillsTable.userId] = userId
                it[BillsTable.amount] = amount
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
            } get BillsTable.id
        }

        return BillDTO(
            id = billId, amount = amount, billType = billType,
            categoryId = categoryId, categoryName = categoryName,
            subCategoryName = subCategoryName, accountId = accountId,
            remark = remark, date = billDate, source = "WEB",
            createdAt = now, updatedAt = now
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

    fun syncBills(userId: Long, after: Long?, limit: Int = 200): SyncResponse {
        val now = System.currentTimeMillis()
        val bills = transaction {
            val query = BillsTable.selectAll()
                .where { BillsTable.userId eq userId }
                .orderBy(BillsTable.updatedAt, SortOrder.ASC)

            if (after != null && after > 0) {
                query.andWhere { BillsTable.updatedAt greater after }
            }

            query.limit(limit + 1).map {
                BillDTO(
                    id = it[BillsTable.id],
                    amount = it[BillsTable.amount],
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
                    deleted = it[BillsTable.deleted]
                )
            }
        }
        val hasMore = bills.size > limit
        return SyncResponse(bills = bills.take(limit), serverTime = now, hasMore = hasMore)
    }

    fun seedIfNeeded() {
        transaction {
            if (CategoriesTable.selectAll().empty()) {
                seedCategories()
            }
            if (SubCategoriesTable.selectAll().empty()) {
                seedSubCategories()
            }
            if (AccountsTable.selectAll().empty()) {
                seedAccounts()
            }
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
            "娱乐" to listOf("电影", "游戏", "旅游")
        )
        for ((catName, subNames) in subMap) {
            val catId = CategoriesTable.selectAll()
                .where { CategoriesTable.name eq catName }
                .singleOrNull()?.get(CategoriesTable.id) ?: continue
            for (subName in subNames) {
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
            Triple("默认", "#F97D1D", 0.0)
        )
        accounts.forEach { (name, color, balance) ->
            AccountsTable.insert {
                it[AccountsTable.name] = name
                it[AccountsTable.iconColor] = color
                it[AccountsTable.balance] = balance
            }
        }
    }

    fun getCategories(): List<CategoryDTO> = transaction {
        CategoriesTable.selectAll()
            .orderBy(CategoriesTable.billType to SortOrder.ASC, CategoriesTable.id to SortOrder.ASC)
            .map { row ->
                CategoryDTO(
                    id = row[CategoriesTable.id],
                    name = row[CategoriesTable.name],
                    iconName = row[CategoriesTable.iconName],
                    billType = row[CategoriesTable.billType],
                    subCategories = SubCategoriesTable.selectAll()
                        .where { SubCategoriesTable.parentCategoryId eq row[CategoriesTable.id] }
                        .orderBy(SubCategoriesTable.id to SortOrder.ASC)
                        .map {
                            SubCategoryDTO(
                                id = it[SubCategoriesTable.id],
                                name = it[SubCategoriesTable.name],
                                parentCategoryId = it[SubCategoriesTable.parentCategoryId]
                            )
                        }
                )
            }
    }

    fun getAccounts(): List<AccountDTO> = transaction {
        AccountsTable.selectAll().map {
            AccountDTO(
                id = it[AccountsTable.id],
                name = it[AccountsTable.name],
                balance = it[AccountsTable.balance],
                iconColor = it[AccountsTable.iconColor]
            )
        }
    }
}
