package com.example.rinklnote.server.services.butler

import java.util.concurrent.ConcurrentHashMap

/**
 * 阶段 2 · 管家会话状态（进程内，重启丢弃可接受）：按用户挂起「批量记账待确认清单」
 * 等多轮交互的中间态。TTL 到期惰性失效（取的时候判，不靠定时器）；同一用户新发批量
 * 直接覆盖旧态。并发容器用法参考 QQBotService.bindCodes 的 ConcurrentHashMap。
 */
class ButlerSession {

    /** 待确认清单里的一笔：原始行文本 + 解析出的金额（分）+ 规则解析出的分类（可空）。 */
    data class PendingItem(
        val line: String,
        val amountMinor: Long,
        val categoryName: String?
    )

    /** 挂起中的批量清单：条目 + 过期时间（epoch 毫秒）。 */
    data class PendingBatch(
        val items: List<PendingItem>,
        val expiresAt: Long
    )

    private val pending = ConcurrentHashMap<Long, PendingBatch>()

    /** 挂起 / 覆盖该用户的待确认清单。 */
    fun put(userId: Long, batch: PendingBatch) {
        pending[userId] = batch
    }

    /** 取待确认清单；惰性判 TTL，过期即清除并返回 null（nowMillis 可注入供测试）。 */
    fun get(userId: Long, nowMillis: Long = System.currentTimeMillis()): PendingBatch? {
        val batch = pending[userId] ?: return null
        if (batch.expiresAt < nowMillis) {
            // 条件删除：只在仍挂着这条过期批次时移除，避免误删并发 put 进来的新批次
            pending.remove(userId, batch)
            return null
        }
        return batch
    }

    /** 丢弃该用户的待确认清单。 */
    fun clear(userId: Long) {
        pending.remove(userId)
    }

    /**
     * 移除清单中第 [index] 笔（从 1 起）。
     * 返回更新后的清单（expiresAt 不变）；清单不存在或剔除后为空返回 null
     * （调用方按「自动取消」处理）；序号越界原样返回当前清单（调用方应先自行校验）。
     */
    fun removeItem(userId: Long, index: Int): PendingBatch? {
        val batch = pending[userId] ?: return null
        if (index < 1 || index > batch.items.size) return batch
        val remaining = batch.items.toMutableList().apply { removeAt(index - 1) }
        if (remaining.isEmpty()) {
            pending.remove(userId, batch)
            return null
        }
        val updated = batch.copy(items = remaining)
        pending[userId] = updated
        return updated
    }

    companion object {
        /** 待确认清单有效期：10 分钟。 */
        const val TTL_MILLIS: Long = 10 * 60 * 1000L
    }
}
