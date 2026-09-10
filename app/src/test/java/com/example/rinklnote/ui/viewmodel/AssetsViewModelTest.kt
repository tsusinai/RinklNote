package com.example.rinklnote.ui.viewmodel

import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.repository.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 资产页 ViewModel characterization test（superpowers Task 1）。
 * 六事件（AddAccount/RenameAccount/ChangeBalance/DeleteAccount/ReconcileAccount/ReconcileAll）
 * 的落库契约：dirty 标记 + 正确字段 + repository 方法调用。
 * syncManager=null 不验证 pushAccount（属集成层，与 BudgetViewModelTest 同边界）。
 * 重构 UI 期间必须保持全绿（回归守护）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeAccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeAccountRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVM(): AssetsViewModel {
        val vm = AssetsViewModel(repo, syncManager = null)
        advanceUntilIdle()
        return vm
    }

    private fun account(id: Long, name: String, balance: Double) =
        Account(id = id, name = name, balance = balance, iconColor = "#28C145", updatedAt = 0L, dirty = false)

    @Test
    fun `state accounts come from observeAccounts flow`() = runTest(dispatcher) {
        repo.accounts.value = listOf(account(1, "微信", 100.0))
        val vm = newVM()
        assertEquals(1, vm.state.value.accounts.size)
        assertEquals("微信", vm.state.value.accounts[0].name)
    }

    @Test
    fun `addAccount inserts with dirty true and correct fields`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(AssetsEvent.AddAccount("支付宝", "#06B4FD", 500.0))
        advanceUntilIdle()
        assertEquals(1, repo.inserted.size)
        val a = repo.inserted.last()
        assertEquals("支付宝", a.name)
        assertEquals(500.0, a.balance, 0.0001)
        assertEquals("#06B4FD", a.iconColor)
        assertTrue(a.dirty)
        assertFalse(a.deleted)
    }

    @Test
    fun `renameAccount updates local with dirty true and new name`() = runTest(dispatcher) {
        repo.accounts.value = listOf(account(1, "微信", 100.0))
        val vm = newVM()
        val orig = vm.state.value.accounts.first()
        vm.onEvent(AssetsEvent.RenameAccount(orig, "零钱"))
        advanceUntilIdle()
        assertEquals(1, repo.updatedLocal.size)
        val u = repo.updatedLocal.last()
        assertEquals("零钱", u.name)
        assertTrue(u.dirty)
        assertEquals(1L, u.id)
    }

    @Test
    fun `changeBalance updates local with dirty true and new balance`() = runTest(dispatcher) {
        repo.accounts.value = listOf(account(1, "微信", 100.0))
        val vm = newVM()
        val orig = vm.state.value.accounts.first()
        vm.onEvent(AssetsEvent.ChangeBalance(orig, 250.0))
        advanceUntilIdle()
        assertEquals(1, repo.updatedLocal.size)
        val u = repo.updatedLocal.last()
        assertEquals(250.0, u.balance, 0.0001)
        assertTrue(u.dirty)
    }

    @Test
    fun `deleteAccount soft deletes the account`() = runTest(dispatcher) {
        repo.accounts.value = listOf(account(1, "微信", 100.0))
        val vm = newVM()
        val orig = vm.state.value.accounts.first()
        vm.onEvent(AssetsEvent.DeleteAccount(orig))
        advanceUntilIdle()
        assertEquals(1, repo.softDeleted.size)
        assertEquals(1L, repo.softDeleted.last().id)
    }

    @Test
    fun `reconcileAccount delegates to repository with opening offset`() = runTest(dispatcher) {
        repo.accounts.value = listOf(account(1, "微信", 100.0))
        val vm = newVM()
        val orig = vm.state.value.accounts.first()
        vm.onEvent(AssetsEvent.ReconcileAccount(orig, 50.0))
        advanceUntilIdle()
        assertEquals(1, repo.reconciled.size)
        val (acc, offset) = repo.reconciled.last()
        assertEquals(1L, acc.id)
        assertEquals(50.0, offset, 0.0001)
    }

    @Test
    fun `reconcileAll calls reconcileAllAccounts on repository`() = runTest(dispatcher) {
        val vm = newVM()
        vm.onEvent(AssetsEvent.ReconcileAll)
        advanceUntilIdle()
        assertTrue(repo.reconcileAllCalled)
    }

    // ---------- fake ----------

    private class FakeAccountRepository : AccountRepository {
        val accounts = MutableStateFlow<List<Account>>(emptyList())
        val inserted = mutableListOf<Account>()
        val updatedLocal = mutableListOf<Account>()
        val softDeleted = mutableListOf<Account>()
        val reconciled = mutableListOf<Pair<Account, Double>>()
        var reconcileAllCalled = false

        override fun observeAccounts(): Flow<List<Account>> = accounts
        override suspend fun insertAccount(account: Account): Long {
            inserted += account
            return inserted.size.toLong()
        }
        override suspend fun updateAccount(account: Account) {}
        override suspend fun updateAccountLocal(account: Account) { updatedLocal += account }
        override suspend fun softDeleteAccount(account: Account) { softDeleted += account }
        override suspend fun markAccountSynced(localId: Long, serverId: Long, updatedAt: Long) {}
        override suspend fun getUnsyncedAccounts(): List<Account> = emptyList()
        override suspend fun getAccountByServerId(serverId: Long): Account? = null
        override suspend fun deleteAccountByServerId(serverId: Long) {}
        override suspend fun getAccountNet(accountId: Long): Double = 0.0
        override suspend fun reconcileAccount(account: Account, openingOffset: Double): Account {
            reconciled += account to openingOffset
            return account
        }
        override suspend fun reconcileAllAccounts(): List<Account> {
            reconcileAllCalled = true
            return emptyList()
        }
    }
}
