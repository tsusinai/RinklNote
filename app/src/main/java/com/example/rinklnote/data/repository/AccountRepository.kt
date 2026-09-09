package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.entity.Account
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun observeAccounts(): Flow<List<Account>>
    suspend fun insertAccount(account: Account): Long
    suspend fun updateAccount(account: Account)
    suspend fun updateAccountLocal(account: Account)
    suspend fun softDeleteAccount(account: Account)
    suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long)
    suspend fun getUnsyncedAccounts(): List<Account>
    suspend fun getAccountByServerId(serverId: Long): Account?
    suspend fun deleteAccountByServerId(serverId: Long)
    suspend fun getAccountNet(accountId: Long): Double
    suspend fun reconcileAccount(account: Account, openingOffset: Double): Account
    suspend fun reconcileAllAccounts(): List<Account>
}