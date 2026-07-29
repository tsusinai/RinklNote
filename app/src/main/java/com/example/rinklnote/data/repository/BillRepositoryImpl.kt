package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.AppDatabase
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BillRepositoryImpl(db: AppDatabase) : BillRepository {

    private val billDao = db.billDao()
    private val categoryDao = db.categoryDao()
    private val accountDao = db.accountDao()

    private val _expenseCategories = MutableStateFlow<List<Category>>(emptyList())
    override val expenseCategories: StateFlow<List<Category>> = _expenseCategories.asStateFlow()

    private val _incomeCategories = MutableStateFlow<List<Category>>(emptyList())
    override val incomeCategories: StateFlow<List<Category>> = _incomeCategories.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    override val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    override fun observeAllBills(): Flow<List<Bill>> = billDao.observeAll()
    override fun observeBillsByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>> =
        billDao.observeByMonth(monthStart, nextMonthStart)

    override suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double =
        billDao.getTotalExpense(monthStart, nextMonthStart) ?: 0.0

    override suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double =
        billDao.getTotalIncome(monthStart, nextMonthStart) ?: 0.0

    override suspend fun addBill(bill: Bill): Long = billDao.insert(bill)

    override suspend fun insertAllBills(bills: List<Bill>) { billDao.insertAll(bills) }

    override suspend fun updateAccount(account: Account) = accountDao.update(account)

    override suspend fun getSubCategories(parentId: Long): List<SubCategory> =
        categoryDao.getSubCategories(parentId)

    override suspend fun loadReferenceData() {
        _expenseCategories.value = categoryDao.getAllByType("EXPENSE")
        _incomeCategories.value = categoryDao.getAllByType("INCOME")
        _accounts.value = accountDao.getAll()
    }

    override suspend fun seedIfNeeded() {
        val catCount = categoryDao.count()
        val accCount = accountDao.count()
        if (catCount > 0 && accCount > 0) {
            loadReferenceData()
            return
        }
        if (catCount == 0) seedCategories()
        if (accCount == 0) seedAccounts()
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
        for (c in expenseCategories) {
            val categoryId = categoryDao.insert(c)
            when (c.name) {
                "三餐" -> listOf("早餐", "午餐", "晚餐", "零食").forEach {
                    categoryDao.insertSubCategory(SubCategory(name = it, parentCategoryId = categoryId))
                }
                "交通" -> listOf("公交", "地铁", "打车", "加油").forEach {
                    categoryDao.insertSubCategory(SubCategory(name = it, parentCategoryId = categoryId))
                }
                "娱乐" -> listOf("电影", "游戏", "旅游").forEach {
                    categoryDao.insertSubCategory(SubCategory(name = it, parentCategoryId = categoryId))
                }
            }
        }

        val incomeCategories = listOf(
            Category(name = "工资", iconName = "salary", billType = "INCOME"),
            Category(name = "兼职", iconName = "parttime", billType = "INCOME"),
            Category(name = "理财", iconName = "finance", billType = "INCOME"),
            Category(name = "其他", iconName = "other", billType = "INCOME"),
        )
        for (c in incomeCategories) {
            categoryDao.insert(c)
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
