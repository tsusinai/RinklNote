package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@androidx.compose.runtime.Stable
interface BillRepository {
    val expenseCategories: StateFlow<List<Category>>
    val incomeCategories: StateFlow<List<Category>>
    val accounts: StateFlow<List<Account>>

    fun observeAllBills(): Flow<List<Bill>>
    fun observeBillsByMonth(monthStart: Long, nextMonthStart: Long): Flow<List<Bill>>
    fun observeTemplates(): Flow<List<BillTemplate>>
    fun observeBudgets(): Flow<List<Budget>>

    suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double
    suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double
    suspend fun addBill(bill: Bill): Long
    suspend fun updateBill(bill: Bill)
    suspend fun deleteBill(bill: Bill)
    suspend fun insertAllBills(bills: List<Bill>)
    suspend fun updateAccount(account: Account)
    suspend fun getSubCategories(parentId: Long): List<SubCategory>
    suspend fun getBudget(monthStart: Long): Budget?
    suspend fun upsertBudget(budget: Budget)
    suspend fun getUnsyncedBudgets(): List<Budget>
    suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long)
    suspend fun deleteBudgetByServerId(serverId: Long)
    suspend fun loadReferenceData()
    suspend fun seedIfNeeded()
}
