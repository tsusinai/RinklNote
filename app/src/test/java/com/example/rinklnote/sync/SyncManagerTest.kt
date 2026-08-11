package com.example.rinklnote.sync

import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.dao.BudgetDao
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.BillDTO
import com.example.rinklnote.data.network.dto.BudgetDTO
import com.example.rinklnote.data.network.dto.SyncResponse
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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
    private lateinit var manager: SyncManager

    @Before
    fun setUp() {
        api = mock()
        tokenManager = mock()
        billDao = mock()
        budgetDao = mock()
        manager = SyncManager(api, tokenManager, billDao, templateDao = null, budgetDao = budgetDao)
        whenever(tokenManager.lastSyncTime).thenReturn(flowOf(0L))
    }

    private suspend fun stubLoggedIn(loggedIn: Boolean) {
        whenever(tokenManager.isLoggedIn()).thenReturn(loggedIn)
    }

    private fun bill(id: Long, deleted: Boolean = false, serverId: Long? = null) = Bill(
        id = id, amount = 10.0, billType = "EXPENSE", categoryId = 1,
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
}
