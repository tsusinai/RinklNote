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
    val createdAt: Long
)

@Serializable
data class SyncResponse(
    val bills: List<BillDTO>,
    val serverTime: Long
)

@Serializable
data class CategoryDTO(
    val id: Long,
    val name: String,
    val iconName: String,
    val billType: String
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
        val now = System.currentTimeMillis()
        val todayStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

        val category = if (categoryName != null) {
            transaction {
                CategoriesTable.selectAll()
                    .where { (CategoriesTable.name eq categoryName) and (CategoriesTable.billType eq "EXPENSE") }
                    .singleOrNull()
            }
        } else null

        val catId: Long
        val catName: String
        if (category != null) {
            catId = category[CategoriesTable.id]
            catName = category[CategoriesTable.name]
        } else {
            val defaultCat = transaction {
                CategoriesTable.selectAll()
                    .where { CategoriesTable.billType eq "EXPENSE" }
                    .orderBy(CategoriesTable.id)
                    .first()
            }
            catId = defaultCat[CategoriesTable.id]
            catName = defaultCat[CategoriesTable.name]
        }

        val account = transaction {
            AccountsTable.selectAll().orderBy(AccountsTable.id).first()
        }

        val billId = transaction {
            BillsTable.insert {
                it[BillsTable.userId] = userId
                it[BillsTable.amount] = amount
                it[BillsTable.billType] = "EXPENSE"
                it[BillsTable.categoryId] = catId
                it[BillsTable.categoryName] = catName
                it[BillsTable.accountId] = account[AccountsTable.id]
                it[BillsTable.remark] = remark
                it[BillsTable.date] = todayStart
                it[BillsTable.billSource] = source
                it[BillsTable.createdAt] = now
            } get BillsTable.id
        }

        return BillDTO(
            id = billId,
            amount = amount,
            billType = "EXPENSE",
            categoryId = catId,
            categoryName = catName,
            subCategoryName = null,
            accountId = account[AccountsTable.id],
            remark = remark,
            date = todayStart,
            source = source,
            createdAt = now
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
            } get BillsTable.id
        }

        return BillDTO(
            id = billId, amount = amount, billType = billType,
            categoryId = categoryId, categoryName = categoryName,
            subCategoryName = subCategoryName, accountId = accountId,
            remark = remark, date = billDate, source = "WEB", createdAt = now
        )
    }

    fun syncBills(userId: Long, after: Long?): SyncResponse {
        val now = System.currentTimeMillis()
        val bills = transaction {
            val query = BillsTable.selectAll()
                .where { BillsTable.userId eq userId }
                .orderBy(BillsTable.id, SortOrder.ASC)

            if (after != null && after > 0) {
                query.andWhere { BillsTable.id greater after }
            }

            query.map {
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
                    createdAt = it[BillsTable.createdAt]
                )
            }
        }
        return SyncResponse(bills = bills, serverTime = now)
    }

    fun seedIfNeeded() {
        transaction {
            if (CategoriesTable.selectAll().empty()) {
                seedCategories()
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
        CategoriesTable.selectAll().map {
            CategoryDTO(
                id = it[CategoriesTable.id],
                name = it[CategoriesTable.name],
                iconName = it[CategoriesTable.iconName],
                billType = it[CategoriesTable.billType]
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
