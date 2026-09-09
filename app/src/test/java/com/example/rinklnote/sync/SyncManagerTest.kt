package com.example.rinklnote.sync

import com.example.rinklnote.data.db.dao.AccountDao
import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.dao.BudgetDao
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.AccountDTO
import com.example.rinklnote.data.network.dto.BillDTO
import com.example.rinklnote.data.network.dto.BudgetDTO
import com.example.rinklnote.data.network.dto.CreateAccountRequest
import com.example.rinklnote.data.network.dto.CreateBillRequest
import com.example.rinklnote.data.network.dto.SyncResponse
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response

/**
 * JVM unit tests for SyncManager (Phase 4 + Phase 1 regressions):
 * tombstone local-delete, remote delete, cursor pagination, pull-merge,
 * and budget last-write-wins merge.
 */
class SyncManagerTest {

    private lateinit var api: ApiService
    private lateinit var tokenManager: TokenManager
    private lateinit var billDao: BillDao
    private lateinit var budgetDao: BudgetDao
    private lateinit var accountDao: AccountDao
    private lateinit var manager: SyncManager

    @Before
    fun setUp() {
        api = mock()
        tokenManager = mock()
        billDao = mock()
        budgetDao = mock()
        accountDao = mock()
        manager = SyncManager(api, tokenManager, billDao, templateDao = null, budgetDao = budgetDao, accountDao = accountDao)
        whenever(tokenManager.lastSyncTime).thenReturn(flowOf(0L))
    }

    private suspend fun stubLoggedIn(loggedIn: Boolean) {
        whenever(tokenManager.isLoggedIn()).thenReturn(loggedIn)
    }

    /**
     * Mockito returns null for un-stubbed suspend functions that return List, which
     * NPEs the forEach loops in sync(). Provide the account-sync empties by default.
     */
    private suspend fun stubAccountSyncDefaults() {
        whenever(accountDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getAccounts()).thenReturn(emptyList())
    }

    private fun bill(id: Long, deleted: Boolean = false, serverId: Long? = null) = Bill(
        id = id, amount = 10.0, billType = BillType.EXPENSE, categoryId = 1,
        categoryName = "三餐", accountId = 1, date = 100, createdAt = 100,
        deleted = deleted, serverId = serverId
    )

    private fun billDTO(id: Long, updatedAt: Long, deleted: Boolean = false) = BillDTO(
        id = id, amount = 10.0, billType = "EXPENSE", categoryId = 1,
        categoryName = "三餐", accountId = 1, date = 100, source = "APP",
        createdAt = 100, updatedAt = updatedAt, deleted = deleted
    )

    @Test
    fun `sync returns NotLoggedIn and does not touch the api`() = runTest {
        stubLoggedIn(false)
        assertEquals(SyncResult.NotLoggedIn, manager.sync())
        verify(api, never()).syncBills(any(), any(), any())
    }

    @Test
    fun `tombstone with no server id hard-deletes locally without any api call`() = runTest {
        val b = bill(id = 7, deleted = true, serverId = null)
        manager.pushBill(b)
        verify(billDao).hardDeleteById(7)
        verify(api, never()).deleteBill(any())
        verify(api, never()).uploadBill(any())
        verify(api, never()).updateBill(any(), any())
    }

    @Test
    fun `deleted bill with server id deletes remotely and locally`() = runTest {
        val b = bill(id = 7, deleted = true, serverId = 99)
        manager.pushBill(b)
        verify(api).deleteBill(99)
        verify(billDao).hardDeleteById(7)
    }

    @Test
    fun `new bill uploads and stamps the returned server id`() = runTest {
        val b = bill(id = 7, deleted = false, serverId = null)
        // Local account id=1 must resolve to a server id before upload.
        whenever(accountDao.getById(1L)).thenReturn(
            Account(id = 1, name = "微信", balance = 0.0, iconColor = "#000000", serverId = 99)
        )
        whenever(api.uploadBill(any())).thenReturn(
            BillDTO(id = 500, amount = 10.0, billType = "EXPENSE", categoryId = 1,
                categoryName = "三餐", accountId = 1, date = 100, source = "APP",
                createdAt = 100, updatedAt = 1000)
        )
        manager.pushBill(b)
        verify(api).uploadBill(any())
        verify(billDao).updateServerId(7, 500, 1000)
    }

    @Test
    fun `sync pages through the composite cursor until hasMore is false`() = runTest {
        stubLoggedIn(true)
        stubAccountSyncDefaults()
        whenever(billDao.getUnsynced()).thenReturn(emptyList())
        whenever(budgetDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getBudgets()).thenReturn(emptyList())

        val page1 = SyncResponse(
            bills = listOf(billDTO(1, 100)), serverTime = 1000,
            hasMore = true, nextAfter = 1000, nextAfterId = 5
        )
        val page2 = SyncResponse(
            bills = listOf(billDTO(2, 200)), serverTime = 2000,
            hasMore = false, nextAfter = 2000, nextAfterId = 9
        )
        whenever(api.syncBills(after = null, afterId = null, limit = 200)).thenReturn(page1)
        whenever(api.syncBills(after = 1000, afterId = 5, limit = 200)).thenReturn(page2)

        val result = manager.sync()

        assertEquals(SyncResult.Success(0, 2), result)
        verify(api).syncBills(after = null, afterId = null, limit = 200)
        verify(api).syncBills(after = 1000, afterId = 5, limit = 200)
        verify(billDao, times(2)).upsertAll(any())
        verify(tokenManager).setLastSyncTime(2000)
    }

    @Test
    fun `sync removes server-side deleted bills locally`() = runTest {
        stubLoggedIn(true)
        stubAccountSyncDefaults()
        whenever(billDao.getUnsynced()).thenReturn(emptyList())
        whenever(budgetDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getBudgets()).thenReturn(emptyList())
        whenever(api.syncBills(after = null, afterId = null, limit = 200))
            .thenReturn(SyncResponse(listOf(billDTO(9, 900, deleted = true)), serverTime = 1000))

        manager.sync()

        verify(billDao).deleteByServerId(9)
        // alive list is empty → sync still calls upsertAll with an empty batch
        verify(billDao).upsertAll(emptyList())
    }

    @Test
    fun `budget merge overwrites local when the server copy is newer`() = runTest {
        stubLoggedIn(true)
        stubAccountSyncDefaults()
        whenever(billDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.syncBills(after = null, afterId = null, limit = 200))
            .thenReturn(SyncResponse(emptyList(), serverTime = 1000))
        whenever(budgetDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getBudgets()).thenReturn(
            listOf(BudgetDTO(id = 10, monthStart = 100, amount = 200.0, createdAt = 1, updatedAt = 999))
        )
        val local = Budget(serverId = 10, monthStart = 100, amount = 100.0, updatedAt = 500)
        whenever(budgetDao.getByServerId(10)).thenReturn(local)

        manager.sync()

        verify(budgetDao).upsert(
            Budget(serverId = 10, monthStart = 100, amount = 200.0, updatedAt = 999, deleted = false, dirty = false)
        )
    }

    @Test
    fun `budget merge keeps local when the local copy is newer`() = runTest {
        stubLoggedIn(true)
        stubAccountSyncDefaults()
        whenever(billDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.syncBills(after = null, afterId = null, limit = 200))
            .thenReturn(SyncResponse(emptyList(), serverTime = 1000))
        whenever(budgetDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getBudgets()).thenReturn(
            listOf(BudgetDTO(id = 10, monthStart = 100, amount = 200.0, createdAt = 1, updatedAt = 500))
        )
        val local = Budget(serverId = 10, monthStart = 100, amount = 100.0, updatedAt = 1000)
        whenever(budgetDao.getByServerId(10)).thenReturn(local)

        manager.sync()

        verify(budgetDao, never()).upsert(any())
    }

    @Test
    fun `account create pushes and stamps the returned server id`() = runTest {
        stubLoggedIn(true)
        val newAcct = Account(id = 5, name = "招商", balance = 0.0, iconColor = "#123456")
        whenever(accountDao.getUnsynced()).thenReturn(listOf(newAcct))
        whenever(api.createAccount(any())).thenReturn(
            AccountDTO(id = 500, name = "招商", balance = 0.0, iconColor = "#123456", updatedAt = 2000)
        )
        whenever(billDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.syncBills(after = null, afterId = null, limit = 200))
            .thenReturn(SyncResponse(emptyList(), serverTime = 0))
        whenever(budgetDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getBudgets()).thenReturn(emptyList())
        whenever(api.getAccounts()).thenReturn(emptyList())

        val result = manager.sync()
        assertTrue("sync returned ${result}", result is SyncResult.Success)

        verify(api).createAccount(CreateAccountRequest(name = "招商", iconColor = "#123456", balance = 0.0))
        verify(accountDao).updateServerId(5, 500, 2000)
    }

    @Test
    fun `account pull LWW never overwrites a local dirty row`() = runTest {
        stubLoggedIn(true)
        // Local account 501 is dirty (unsynced edit) and older than the server copy.
        val dirtyLocal = Account(id = 6, serverId = 501, name = "余额宝", balance = 100.0, iconColor = "#000000", updatedAt = 3000, dirty = true)
        whenever(accountDao.getUnsynced()).thenReturn(emptyList())
        whenever(billDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.syncBills(any(), any(), any())).thenReturn(SyncResponse(emptyList(), serverTime = 0))
        whenever(budgetDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getBudgets()).thenReturn(emptyList())
        whenever(accountDao.getByServerId(501L)).thenReturn(dirtyLocal)
        whenever(api.getAccounts()).thenReturn(
            listOf(AccountDTO(id = 501, name = "余额宝", balance = 999.0, iconColor = "#000000", updatedAt = 4000))
        )

        manager.sync()

        // Server copy is newer but the local row is dirty → never clobbered.
        verify(accountDao, never()).upsert(any())
    }

    @Test
    fun `bill pull skips a local dirty row`() = runTest {
        stubLoggedIn(true)
        stubAccountSyncDefaults()
        whenever(billDao.getUnsynced()).thenReturn(emptyList())
        whenever(budgetDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getBudgets()).thenReturn(emptyList())
        whenever(api.getAccounts()).thenReturn(emptyList())
        whenever(api.syncBills(after = null, afterId = null, limit = 200)).thenReturn(
            SyncResponse(listOf(billDTO(9, 900)), serverTime = 1000)
        )
        val dirtyLocal = bill(id = 9, serverId = 9).copy(dirty = true)
        whenever(billDao.getByServerId(9L)).thenReturn(dirtyLocal)

        manager.sync()

        // The local dirty invoice is preserved — the server row is not upserted.
        verify(billDao).upsertAll(argThat<List<Bill>> { bills -> bills.none { b -> b.serverId == 9L } })
    }

    @Test
    fun `bill update on 409 re-bases and replays exactly once`() = runTest {
        val b = bill(id = 7, deleted = false, serverId = 99).copy(baseUpdatedAt = 1000)
        whenever(accountDao.getById(1L)).thenReturn(
            Account(id = 1, name = "微信", balance = 0.0, iconColor = "#000000", serverId = 99)
        )
        val ok = BillDTO(id = 99, amount = 10.0, billType = "EXPENSE", categoryId = 1,
            categoryName = "三餐", accountId = 1, date = 100, source = "APP",
            createdAt = 100, updatedAt = 2000)
        val conflict = HttpException(Response.error<Any>(409, "conflict".toResponseBody()))
        whenever(api.updateBill(any(), any())).thenThrow(conflict).thenReturn(ok)
        whenever(api.getBill(99L)).thenReturn(ok)

        manager.pushBill(b)

        verify(api).updateBill(eq(99L), argThat<CreateBillRequest> { req -> req.baseUpdatedAt == 1000L })
        verify(api).getBill(99L)
        verify(api).updateBill(eq(99L), argThat<CreateBillRequest> { req -> req.baseUpdatedAt == 2000L })
        verify(billDao).updateServerId(7, 99, 2000)
    }

    @Test
    fun `bill push maps local account id to the server account id`() = runTest {
        val b = bill(id = 7, deleted = false, serverId = null)
        // Local account id=1 maps to the server account id=214 (per-user global ids).
        whenever(accountDao.getById(1L)).thenReturn(
            Account(id = 1, name = "微信", balance = 0.0, iconColor = "#000000", serverId = 214)
        )
        whenever(api.uploadBill(any())).thenReturn(
            BillDTO(id = 500, amount = 10.0, billType = "EXPENSE", categoryId = 1,
                categoryName = "三餐", accountId = 214, date = 100, source = "APP",
                createdAt = 100, updatedAt = 1000)
        )
        manager.pushBill(b)
        // The wire payload must carry the server account id, not the local Room id.
        verify(api).uploadBill(argThat<CreateBillRequest> { req -> req.accountId == 214L })
        verify(billDao).updateServerId(7, 500, 1000)
    }

    @Test
    fun `bill push leaves unsyncable bill when no account server id yet`() = runTest {
        val b = bill(id = 7, deleted = false, serverId = null)
        // No server id for the account yet (unreconciled seed) → skip upload, keep it local.
        whenever(accountDao.getById(1L)).thenReturn(
            Account(id = 1, name = "微信", balance = 0.0, iconColor = "#000000", serverId = null)
        )
        manager.pushBill(b)
        verify(api, never()).uploadBill(any())
        verify(billDao, never()).updateServerId(any(), any(), any())
    }

    @Test
    fun `bill pull maps server account id to local account id`() = runTest {
        stubLoggedIn(true)
        stubAccountSyncDefaults()
        whenever(billDao.getUnsynced()).thenReturn(emptyList())
        whenever(budgetDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getBudgets()).thenReturn(emptyList())
        // Server bill references the server account id=214; local account id=1 maps back.
        whenever(accountDao.getByServerId(214L)).thenReturn(
            Account(id = 1, serverId = 214, name = "微信", balance = 0.0, iconColor = "#000000")
        )
        val dto = billDTO(9, 900).copy(accountId = 214)
        whenever(api.syncBills(after = null, afterId = null, limit = 200))
            .thenReturn(SyncResponse(listOf(dto), serverTime = 1000))
        manager.sync()
        verify(billDao).upsertAll(argThat<List<Bill>> { bills ->
            bills.any { it.serverId == 9L && it.accountId == 1L }
        })
    }

    @Test
    fun `account pull reconciles a same-named seed account instead of duplicating`() = runTest {
        stubLoggedIn(true)
        whenever(accountDao.getUnsynced()).thenReturn(emptyList())
        whenever(billDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.syncBills(after = null, afterId = null, limit = 200))
            .thenReturn(SyncResponse(emptyList(), serverTime = 0))
        whenever(budgetDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getBudgets()).thenReturn(emptyList())
        // Local seed 微信 (id=1, no server id) matches server 微信 (id=214).
        whenever(accountDao.getByServerId(214L)).thenReturn(null)
        whenever(accountDao.getByNameActive("微信")).thenReturn(
            Account(id = 1, name = "微信", balance = 0.0, iconColor = "#28C145", serverId = null, updatedAt = 0)
        )
        whenever(api.getAccounts()).thenReturn(
            listOf(AccountDTO(id = 214, name = "微信", balance = 0.0, iconColor = "#28C145", updatedAt = 999))
        )
        manager.sync()
        // The seed row is reconciled (adopts the server id) rather than duplicated.
        verify(accountDao).upsert(
            argThat<Account> { acc -> acc.serverId == 214L && acc.id == 1L && !acc.dirty }
        )
    }
}
