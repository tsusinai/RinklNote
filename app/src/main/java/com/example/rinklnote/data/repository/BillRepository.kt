package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.data.db.entity.SubCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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

    suspend fun getTotalExpense(monthStart: Long, nextMonthStart: Long): Double
    suspend fun getTotalIncome(monthStart: Long, nextMonthStart: Long): Double
    suspend fun addBill(bill: Bill): Long
    suspend fun updateBill(bill: Bill)
    suspend fun deleteBill(bill: Bill)

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
    suspend fun getBudget(monthStart: Long): Budget?
    suspend fun upsertBudget(budget: Budget)
    suspend fun getUnsyncedBudgets(): List<Budget>
    suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long)
    suspend fun deleteBudgetByServerId(serverId: Long)
    suspend fun clearLocalData()
    suspend fun loadReferenceData()
    suspend fun seedIfNeeded()

    // Unsynced (pending push) count, used by logout's push-first-then-wipe.
    suspend fun countUnsynced(): Long

    // Balance reconciliation against each account's own record net (income − expense).
    suspend fun getAccountNet(accountId: Long): Double
    suspend fun reconcileAccount(account: Account, openingOffset: Double): Account
    suspend fun reconcileAllAccounts(): List<Account>
}
