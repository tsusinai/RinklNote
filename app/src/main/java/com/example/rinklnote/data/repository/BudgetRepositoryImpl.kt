package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.AppDatabase
import com.example.rinklnote.data.db.entity.Budget
import kotlinx.coroutines.flow.Flow

internal class BudgetRepositoryImpl(
    private val db: AppDatabase
) : BudgetRepository {

    private val budgetDao = db.budgetDao()

    override fun observeBudgets(): Flow<List<Budget>> = budgetDao.observeAll()

    override suspend fun getBudget(monthStart: Long): Budget? = budgetDao.getByMonth(monthStart)

    override suspend fun upsertBudget(budget: Budget) = budgetDao.upsert(budget)

    override suspend fun getUnsyncedBudgets(): List<Budget> = budgetDao.getUnsynced()

    override suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long) {
        budgetDao.updateServerId(localId, serverId, updatedAt)
    }

    override suspend fun deleteBudgetByServerId(serverId: Long) = budgetDao.deleteByServerId(serverId)
}