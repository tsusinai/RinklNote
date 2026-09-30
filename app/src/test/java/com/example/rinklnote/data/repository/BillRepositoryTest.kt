package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.dao.AccountDao
import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 删除撤销（体验优化 Task 2）单测：验证 [applyUndoDelete] 的落库契约——
 * 软删行恢复 deleted=0/dirty=1、重复撤销幂等、墓碑被物理清除后按快照重插、
 * 余额按收支方向回补。BillRepositoryImpl 需要真实 Room 事务，JVM 单测以 DAO
 * 打桩直接验证事务内动作；undoDeleteBill 的 runCatching 兜底由实现保证。
 */
class BillRepositoryTest {

    private lateinit var billDao: BillDao
    private lateinit var accountDao: AccountDao

    /** 已同步过的支出账单快照（删除前的原样，deleted=false 由删除方传入）。 */
    private val snapshot = Bill(
        id = 7, amountMinor = 2000L, billType = BillType.EXPENSE, categoryId = 1,
        categoryName = "三餐", accountId = 1, date = 100, createdAt = 100,
        serverId = 99L, baseUpdatedAt = 100L
    )

    @Before
    fun setUp() {
        billDao = mock()
        accountDao = mock()
    }

    @Test
    fun `撤销软删账单恢复为未删除且待同步`() = runTest {
        // 删除后的本地行：软删墓碑（deleted=1, dirty=1）。
        whenever(billDao.getById(7L)).thenReturn(snapshot.copy(deleted = true, dirty = true))
        whenever(accountDao.getById(1L)).thenReturn(
            Account(id = 1, name = "无账户", balanceMinor = 5000L, iconColor = "#000000")
        )

        applyUndoDelete(billDao, accountDao, snapshot, now = 1234L)

        val billCaptor = argumentCaptor<Bill>()
        verify(billDao).update(billCaptor.capture())
        val restored = billCaptor.firstValue
        assertFalse("恢复后 deleted 必须为 0", restored.deleted)
        assertTrue("恢复后 dirty 必须为 1（下次推送按 PUT 写回服务端）", restored.dirty)
        assertEquals(1234L, restored.updatedAt)
        assertEquals("server_id 保留，复用既有 PUT 语义", 99L, restored.serverId)
        assertEquals("恢复用快照内容，不采用墓碑行", 2000L, restored.amountMinor)

        // 撤销 = 把删除时反向回补的余额影响重新应用：支出账单 → 余额再减回 2000。
        val accountCaptor = argumentCaptor<Account>()
        verify(accountDao).update(accountCaptor.capture())
        assertEquals(3000L, accountCaptor.firstValue.balanceMinor)
        assertTrue("余额回补后账户置脏等待同步", accountCaptor.firstValue.dirty)
    }

    @Test
    fun `重复撤销幂等不重复回补余额`() = runTest {
        // 行已处于未删除态（上一次撤销已生效）→ 幂等跳过。
        whenever(billDao.getById(7L)).thenReturn(snapshot.copy(deleted = false, dirty = true))

        applyUndoDelete(billDao, accountDao, snapshot, now = 1234L)

        verify(billDao, never()).update(any())
        verify(billDao, never()).insert(any())
        verify(accountDao, never()).update(any())
    }

    @Test
    fun `本地行被物理清除时按快照原样重插`() = runTest {
        // 软删墓碑已被推送物理清除（服务端软删 + 本地 hardDelete）。
        whenever(billDao.getById(7L)).thenReturn(null)
        whenever(accountDao.getById(1L)).thenReturn(
            Account(id = 1, name = "无账户", balanceMinor = 0L, iconColor = "#000000")
        )

        applyUndoDelete(billDao, accountDao, snapshot, now = 1234L)

        val captor = argumentCaptor<Bill>()
        verify(billDao).insert(captor.capture())
        val reinserted = captor.firstValue
        assertEquals("保留原本地 id，绝不重建新行", 7L, reinserted.id)
        assertEquals("保留原 server_id，下次推送按 PUT 恢复服务端", 99L, reinserted.serverId)
        assertFalse(reinserted.deleted)
        assertTrue(reinserted.dirty)
    }

    @Test
    fun `撤销收入账单余额向收入方向回补`() = runTest {
        val income = snapshot.copy(billType = BillType.INCOME)
        whenever(billDao.getById(7L)).thenReturn(income.copy(deleted = true, dirty = true))
        whenever(accountDao.getById(1L)).thenReturn(
            Account(id = 1, name = "无账户", balanceMinor = 1000L, iconColor = "#000000")
        )

        applyUndoDelete(billDao, accountDao, income, now = 1234L)

        val captor = argumentCaptor<Account>()
        verify(accountDao).update(captor.capture())
        assertEquals("收入账单恢复 → 余额加回 2000", 3000L, captor.firstValue.balanceMinor)
    }

    @Test
    fun `账户缺失时仍恢复账单但不回补余额`() = runTest {
        whenever(billDao.getById(7L)).thenReturn(snapshot.copy(deleted = true, dirty = true))
        whenever(accountDao.getById(1L)).thenReturn(null)

        applyUndoDelete(billDao, accountDao, snapshot, now = 1234L)

        // 账单本体仍要恢复；余额无处回补则跳过（不抛异常中断事务）。
        verify(billDao).update(any())
        verify(accountDao, never()).update(any())
    }
}
