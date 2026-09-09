package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.AppDatabase
import com.example.rinklnote.data.db.entity.Account
import kotlinx.coroutines.flow.Flow

internal class AccountRepositoryImpl(
    private val db: AppDatabase
) : AccountRepository {

    private val accountDao = db.accountDao()

    override fun observeAccounts(): Flow<List<Account>> = accountDao.observeAll()

    override suspend fun insertAccount(account: Account): Long = accountDao.insert(account)

    override suspend fun updateAccount(account: Account) = accountDao.update(account)

    override suspend fun updateAccountLocal(account: Account) = accountDao.update(account)

    override suspend fun softDeleteAccount(account: Account) {
        accountDao.update(
            account.copy(
                deleted = true,
                dirty = true,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long) {
        accountDao.updateServerId(localId, serverId, updatedAt)
    }

    override suspend fun getUnsyncedAccounts(): List<Account> = accountDao.getUnsynced()

    override suspend fun getAccountByServerId(serverId: Long): Account? = accountDao.getByServerId(serverId)

    override suspend fun deleteAccountByServerId(serverId: Long) = accountDao.deleteByServerId(serverId)

    override suspend fun getAccountNet(accountId: Long): Double = db.billDao().getAccountNet(accountId) ?: 0.0

    override suspend fun reconcileAccount(account: Account, openingOffset: Double): Account {
        val net = db.billDao().getAccountNet(account.id) ?: 0.0
        val updated = account.copy(
            balance = openingOffset + net,
            updatedAt = System.currentTimeMillis(),
            dirty = true
        )
        accountDao.update(updated)
        return updated
    }

    override suspend fun reconcileAllAccounts(): List<Account> {
        val updated = mutableListOf<Account>()
        accountDao.getAllActive().forEach { account ->
            updated += reconcileAccount(account, 0.0)
        }
        return updated
    }
}