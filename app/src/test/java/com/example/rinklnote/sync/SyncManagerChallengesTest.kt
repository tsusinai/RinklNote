package com.example.rinklnote.sync

import com.example.rinklnote.data.db.dao.AccountDao
import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.dao.BudgetDao
import com.example.rinklnote.data.db.dao.ChallengeDao
import com.example.rinklnote.data.db.entity.Challenge
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.ChallengeDTO
import com.example.rinklnote.data.network.dto.SyncResponse
import com.example.rinklnote.data.network.dto.UpsertChallengeRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * SyncManager 挑战同步（JVM 单测）：
 * pull 全量 LWW 合并（新胜 / 旧不败）、本地 dirty 行不被覆盖、
 * 服务端 deleted 行清理本地、push 回写 server_id、challengeDao = null 兼容。
 */
class SyncManagerChallengesTest {

    private lateinit var api: ApiService
    private lateinit var tokenManager: TokenManager
    private lateinit var billDao: BillDao
    private lateinit var budgetDao: BudgetDao
    private lateinit var accountDao: AccountDao
    private lateinit var challengeDao: ChallengeDao
    private lateinit var manager: SyncManager

    @Before
    fun setUp() {
        api = mock()
        tokenManager = mock()
        billDao = mock()
        budgetDao = mock()
        accountDao = mock()
        challengeDao = mock()
        manager = SyncManager(
            api, tokenManager, billDao,
            templateDao = null, budgetDao = budgetDao, accountDao = accountDao,
            challengeDao = challengeDao
        )
        whenever(tokenManager.lastSyncTime).thenReturn(flowOf(0L))
    }

    private suspend fun stubLoggedIn(loggedIn: Boolean) {
        whenever(tokenManager.isLoggedIn()).thenReturn(loggedIn)
    }

    /** sync() 会先跑账户同步，未打桩的 suspend List 返回 null 会让 forEach 抛 NPE，这里统一给空。 */
    private suspend fun stubAccountSyncDefaults() {
        whenever(accountDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getAccounts()).thenReturn(emptyList())
        whenever(accountDao.getAllActive()).thenReturn(emptyList())
    }

    /** 除挑战外全部给空，让 sync() 能一路跑到 syncChallenges()。 */
    private suspend fun stubSyncUpstreamDefaults() {
        stubLoggedIn(true)
        stubAccountSyncDefaults()
        whenever(billDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.syncBills(after = null, afterId = null, limit = 200))
            .thenReturn(SyncResponse(emptyList(), serverTime = 0))
        whenever(budgetDao.getUnsynced()).thenReturn(emptyList())
        whenever(api.getBudgets()).thenReturn(emptyList())
        whenever(challengeDao.getUnsynced()).thenReturn(emptyList())
    }

    private fun challengeDTO(id: Long, updatedAt: Long, deleted: Boolean = false) = ChallengeDTO(
        id = id, type = "NO_SPEND_DAY", periodStart = 100, goal = 8,
        status = "ACTIVE", createdAt = 1, updatedAt = updatedAt, deleted = deleted
    )

    private fun localRow(updatedAt: Long, dirty: Boolean = false) = Challenge(
        id = 3, serverId = 10, type = "NO_SPEND_DAY", periodStart = 100, goal = 5,
        status = "ACTIVE", updatedAt = updatedAt, dirty = dirty
    )

    @Test
    fun `challenge merge overwrites local when the server copy is newer`() = runTest {
        stubSyncUpstreamDefaults()
        whenever(api.getChallenges()).thenReturn(listOf(challengeDTO(id = 10, updatedAt = 999)))
        whenever(challengeDao.getByServerId(10L)).thenReturn(localRow(updatedAt = 500))

        val result = manager.sync()

        assertTrue("sync returned ${result}", result is SyncResult.Success)
        // 服务端行整体覆盖本地（保留 id / server_id），updatedAt 取服务端现行，落库即 clean。
        verify(challengeDao).upsert(
            Challenge(
                id = 3, serverId = 10, type = "NO_SPEND_DAY", periodStart = 100, goal = 8,
                status = "ACTIVE", updatedAt = 999, deleted = false, dirty = false
            )
        )
    }

    @Test
    fun `challenge merge keeps local when the local copy is newer`() = runTest {
        stubSyncUpstreamDefaults()
        whenever(api.getChallenges()).thenReturn(listOf(challengeDTO(id = 10, updatedAt = 500)))
        whenever(challengeDao.getByServerId(10L)).thenReturn(localRow(updatedAt = 1000))

        manager.sync()

        // 本地行更新 → 服务端旧行不覆盖，也不清理。
        verify(challengeDao, never()).upsert(any())
        verify(challengeDao, never()).deleteByServerId(any())
    }

    @Test
    fun `challenge pull never overwrites a local dirty row`() = runTest {
        stubSyncUpstreamDefaults()
        whenever(api.getChallenges()).thenReturn(listOf(challengeDTO(id = 10, updatedAt = 500)))
        whenever(challengeDao.getByServerId(10L)).thenReturn(localRow(updatedAt = 1000, dirty = true))

        manager.sync()

        // 本地有未推送的编辑：即使服务端行存在也绝不回写，等下一轮 push。
        verify(challengeDao, never()).upsert(any())
    }

    @Test
    fun `challenge pull removes server-side deleted challenges locally`() = runTest {
        stubSyncUpstreamDefaults()
        whenever(api.getChallenges())
            .thenReturn(listOf(challengeDTO(id = 10, updatedAt = 999, deleted = true)))

        manager.sync()

        // 服务端已删除 → 本地按 server_id 直接清掉，不做 upsert。
        verify(challengeDao).deleteByServerId(10)
        verify(challengeDao, never()).upsert(any())
    }

    @Test
    fun `challenge push uploads and stamps the returned server id`() = runTest {
        val c = Challenge(
            id = 7, type = "WEEKLY_BUDGET", periodStart = 100, goal = 50000,
            status = "ACTIVE", updatedAt = 1000, dirty = true
        )
        whenever(api.upsertChallenge(any())).thenReturn(
            ChallengeDTO(
                id = 500, type = "WEEKLY_BUDGET", periodStart = 100, goal = 50000,
                status = "ACTIVE", createdAt = 900, updatedAt = 2000
            )
        )

        manager.pushChallenge(c)

        // 请求体携带业务字段 + 客户端行时间戳（服务端 LWW 用）。
        verify(api).upsertChallenge(
            argThat<UpsertChallengeRequest> { req ->
                req.type == "WEEKLY_BUDGET" && req.periodStart == 100L &&
                    req.goal == 50000L && req.status == "ACTIVE" && req.updatedAt == 1000L
            }
        )
        // 用返回 DTO 回写 server_id 与服务端时间（updateServerId 同时清 dirty）。
        verify(challengeDao).updateServerId(7, 500, 2000)
    }

    @Test
    fun `sync completes without touching challenges when challengeDao is null`() = runTest {
        val noChallengeManager = SyncManager(
            api, tokenManager, billDao,
            templateDao = null, budgetDao = budgetDao, accountDao = accountDao
        )
        stubSyncUpstreamDefaults()

        val result = noChallengeManager.sync()

        assertEquals(SyncResult.Success(0, 0), result)
        verify(api, never()).getChallenges()
        verify(api, never()).upsertChallenge(any())
    }
}
