package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.ChallengesTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChallengeServiceTest {

    private val service = ChallengeService()

    @Before
    fun setup() {
        // Fresh in-memory DB per test so the shared JVM's Exposed default is never
        // pointed at a stale database left by another test class.
        TestDatabase.connect("challengetest")
        transaction {
            SchemaUtils.create(UsersTable, ChallengesTable)
            // Clear in FK dependency order so the shared in-memory DB is pristine per test.
            ChallengesTable.deleteAll()
            UsersTable.deleteAll()
            insertUser(1L, "13900000021")
            insertUser(2L, "13900000022")
        }
    }

    private fun insertUser(id: Long, phone: String) {
        UsersTable.insert {
            it[UsersTable.id] = id
            it[UsersTable.phone] = phone
            it[UsersTable.passwordHash] = "hash"
            it[UsersTable.createdAt] = "2026-01-01"
        }
    }

    private fun forceUpdatedAt(id: Long, value: Long) {
        transaction {
            ChallengesTable.update({ ChallengesTable.id eq id }) {
                it[ChallengesTable.updatedAt] = value
            }
        }
    }

    private fun markDeleted(id: Long) {
        transaction {
            ChallengesTable.update({ ChallengesTable.id eq id }) {
                it[ChallengesTable.deleted] = true
            }
        }
    }

    @Test
    fun `upsert creates a new challenge for a user and scope`() {
        val created = service.upsert(1L, type = "NO_SPEND_DAY", periodStart = 100L, goal = 8L)
        assertTrue(created.id > 0)
        assertEquals("NO_SPEND_DAY", created.type)
        assertEquals(100L, created.periodStart)
        assertEquals(8L, created.goal)
        assertEquals("ACTIVE", created.status)
        assertTrue(!created.deleted)
        assertTrue(created.updatedAt != null)

        val all = service.list(1L)
        assertEquals(1, all.size)
        assertEquals(created.id, all[0].id)
    }

    @Test
    fun `upsert with same user type and periodStart updates instead of duplicating`() {
        val first = service.upsert(1L, type = "WEEKLY_BUDGET", periodStart = 200L, goal = 50000L)
        val second = service.upsert(1L, type = "WEEKLY_BUDGET", periodStart = 200L, goal = 88800L)

        assertEquals(first.id, second.id)
        assertEquals(88800L, second.goal)
        assertEquals(1, service.list(1L).size)
    }

    @Test
    fun `same periodStart with different type creates separate rows`() {
        val noSpend = service.upsert(1L, type = "NO_SPEND_DAY", periodStart = 900L, goal = 8L)
        val weekly = service.upsert(1L, type = "WEEKLY_BUDGET", periodStart = 900L, goal = 30000L)

        assertNotEquals(noSpend.id, weekly.id)
        assertEquals(2, service.list(1L).size)
    }

    @Test
    fun `list is scoped per user`() {
        service.upsert(1L, type = "NO_SPEND_DAY", periodStart = 300L, goal = 5L)
        service.upsert(2L, type = "NO_SPEND_DAY", periodStart = 300L, goal = 15L)

        val user1 = service.list(1L)
        val user2 = service.list(2L)
        assertEquals(1, user1.size)
        assertEquals(1, user2.size)
        assertEquals(5L, user1[0].goal)
        assertEquals(15L, user2[0].goal)
        assertNotEquals(user1[0].id, user2[0].id)
    }

    @Test
    fun `upsert with stale updatedAt does not overwrite newer row`() {
        val created = service.upsert(1L, type = "BOOKKEEPING_STREAK", periodStart = 400L, goal = 7L)
        val clientUpdatedAt = created.updatedAt!!
        // 模拟另一端随后写入的更新（服务端行比客户端携带的时间戳新）。
        val newer = System.currentTimeMillis() + 60_000L
        forceUpdatedAt(created.id, newer)
        transaction {
            ChallengesTable.update({ ChallengesTable.id eq created.id }) {
                it[ChallengesTable.goal] = 99L
                it[ChallengesTable.status] = "ACHIEVED"
            }
        }

        val stale = service.upsert(
            1L, type = "BOOKKEEPING_STREAK", periodStart = 400L,
            goal = 1L, status = "MISSED", clientUpdatedAt = clientUpdatedAt
        )

        // 旧写入不覆盖新行：goal/status 保持服务端现行值，updated_at 不变。
        assertEquals(created.id, stale.id)
        assertEquals(99L, stale.goal)
        assertEquals("ACHIEVED", stale.status)
        assertEquals(newer, stale.updatedAt)
        assertEquals(1, service.list(1L).size)
    }

    @Test
    fun `upsert with newer updatedAt overwrites the row`() {
        val created = service.upsert(1L, type = "NO_SPEND_DAY", periodStart = 500L, goal = 3L)
        val old = System.currentTimeMillis() - 60_000L
        forceUpdatedAt(created.id, old)

        val fresh = service.upsert(
            1L, type = "NO_SPEND_DAY", periodStart = 500L,
            goal = 10L, status = "ACHIEVED", clientUpdatedAt = System.currentTimeMillis()
        )

        assertEquals(created.id, fresh.id)
        assertEquals(10L, fresh.goal)
        assertEquals("ACHIEVED", fresh.status)
        assertTrue(fresh.updatedAt!! > old)
    }

    @Test
    fun `upsert revives a deleted row when client write is newer`() {
        val created = service.upsert(1L, type = "WEEKLY_BUDGET", periodStart = 600L, goal = 20000L)
        markDeleted(created.id)
        assertTrue(service.list(1L).single { it.id == created.id }.deleted)

        val revived = service.upsert(
            1L, type = "WEEKLY_BUDGET", periodStart = 600L,
            goal = 25000L, clientUpdatedAt = System.currentTimeMillis() + 60_000L
        )

        assertEquals(created.id, revived.id)
        assertTrue(!revived.deleted)
        assertEquals(25000L, revived.goal)
    }

    @Test
    fun `stale write does not revive a deleted row`() {
        val created = service.upsert(1L, type = "NO_SPEND_DAY", periodStart = 1000L, goal = 6L)
        val clientUpdatedAt = created.updatedAt!!
        val newer = System.currentTimeMillis() + 60_000L
        forceUpdatedAt(created.id, newer)
        markDeleted(created.id)

        val stale = service.upsert(
            1L, type = "NO_SPEND_DAY", periodStart = 1000L,
            goal = 9L, clientUpdatedAt = clientUpdatedAt
        )

        // 墓碑更新，新者胜：旧写入既不覆盖也不能复活已删行。
        assertEquals(created.id, stale.id)
        assertTrue(stale.deleted)
        assertEquals(6L, stale.goal)
    }

    @Test
    fun `upsert without client updatedAt applies unconditionally`() {
        val created = service.upsert(1L, type = "NO_SPEND_DAY", periodStart = 700L, goal = 4L)
        val newer = System.currentTimeMillis() + 60_000L
        forceUpdatedAt(created.id, newer)

        // 不带 updatedAt → 视为直接写入（交互式创建/旧客户端），以服务器时间为准，无条件应用。
        val t0 = System.currentTimeMillis()
        val applied = service.upsert(1L, type = "NO_SPEND_DAY", periodStart = 700L, goal = 12L, status = "ACHIEVED")

        assertEquals(created.id, applied.id)
        assertEquals(12L, applied.goal)
        assertEquals("ACHIEVED", applied.status)
        assertTrue(applied.updatedAt!! >= t0)
    }

    @Test
    fun `upsert updates status for an existing row`() {
        val created = service.upsert(1L, type = "BOOKKEEPING_STREAK", periodStart = 800L, goal = 30L)

        val achieved = service.upsert(1L, type = "BOOKKEEPING_STREAK", periodStart = 800L, goal = 30L, status = "ACHIEVED")

        assertEquals(created.id, achieved.id)
        assertEquals("ACHIEVED", achieved.status)
        assertEquals("ACHIEVED", service.list(1L).single { it.id == created.id }.status)
    }
}
