package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.entity.Budget
import kotlinx.coroutines.flow.Flow

interface BudgetRepository {
    fun observeBudgets(): Flow<List<Budget>>
    suspend fun getBudget(monthStart: Long): Budget?
    suspend fun upsertBudget(budget: Budget)
    suspend fun getUnsyncedBudgets(): List<Budget>
    suspend fun markBudgetSynced(localId: Long, serverId: Long, updatedAt: Long)
    suspend fun deleteBudgetByServerId(serverId: Long)
}