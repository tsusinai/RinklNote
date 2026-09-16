package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.WebhookEventTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * QQMessageProcessor.isFirstEvent 去重台账的窗口语义测试（安全评审修复）。
 *
 * 修复前清理 SQL 是 `processed_at < now`（now = 当前毫秒）：每来一条新事件就把之前**所有**
 * 去重行当场删光，平台 5s 重试直接穿透去重造成重复入账。修复后 cutoff = now - 3 天 ——
 * 本测试对表断言：3 天内的行必须保留，4 天前的行被清掉。
 */
class QQMessageProcessorDedupTest {

    companion object {
        private const val DAY_MS = 86_400_000L
    }

    @Before
    fun setup() {
        TestDatabase.connect("qqdedup")
        transaction {
            SchemaUtils.create(WebhookEventTable)
            WebhookEventTable.deleteAll()
        }
    }

    @Test
    fun `写入新事件时 3 天窗口内的去重行保留、4 天前的清掉`() {
        val now = System.currentTimeMillis()
        transaction {
            WebhookEventTable.insert {
                it[WebhookEventTable.eventId] = "stale-4d"
                it[WebhookEventTable.processedAt] = now - 4 * DAY_MS
            }
            WebhookEventTable.insert {
                it[WebhookEventTable.eventId] = "fresh-2d"
                it[WebhookEventTable.processedAt] = now - 2 * DAY_MS
            }
        }

        assertTrue("新事件首投应放行", QQMessageProcessor.isFirstEvent("brand-new-event"))

        val ids = transaction {
            WebhookEventTable.selectAll().map { it[WebhookEventTable.eventId] }.toSet()
        }
        assertFalse("4 天前的去重行应被机会性清理", "stale-4d" in ids)
        assertTrue(
            "3 天窗口内的去重行必须保留（旧实现会当场删光 → 平台 5s 重试穿透去重）",
            "fresh-2d" in ids
        )
        // 新事件以 SHA-256 哈希落库（故不在明文 id 集合里）：共 2 行 = 新事件 + 窗口内旧行
        assertEquals(2, ids.size)

        assertFalse("同一事件第二次投递应判为重复", QQMessageProcessor.isFirstEvent("brand-new-event"))
    }
}
