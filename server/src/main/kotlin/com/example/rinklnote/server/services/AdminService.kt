package com.example.rinklnote.server.services

import com.example.rinklnote.server.plugins.DbRuntimeInfo
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.PushLogTable
import com.example.rinklnote.server.tables.UsersTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 管理端只读服务（前缀 /api/admin，配套 AdminRoutes）。
 *
 * ⚠️ 隐私红线（AGENTS.md 硬性约定 1 的管理端延伸，动这里必自查）：
 * admin 接口只给「聚合计数」与运维元数据（用户数 / 账单数 / 推送的 type+dayKey 等），
 * **绝不返回任何账单备注、金额明细或未聚合的用户内容**。
 * 现有查询里最敏感的字段是手机号，也只给掩码（[maskPhone]）；
 * push_log 表本身不存消息文案（仅 type/dayKey/pushed_at），天然不泄露推送内容。
 *
 * 全部方法只读（SELECT），不含任何写路径 —— P1 的封禁 / 审计落地前维持这个约束。
 *
 * 依赖注入说明：dbTypeName / llmConfigured / asrConfigured / todayStartMillis 均为 lambda，
 * 生产由 Application.kt 注入真实值，测试可覆盖（H2 + 固定时钟）。
 */
class AdminService(
    /** 数据库类型名（Database.kt 感知，见 DbRuntimeInfo）。 */
    private val dbTypeName: () -> String = { DbRuntimeInfo.typeName },
    /** LLM（DeepSeek）是否已配置 —— 只回布尔，绝不回显 key 值。 */
    private val llmConfigured: () -> Boolean = { true },
    /** ASR（Whisper）是否已配置 —— 只回布尔，绝不回显 key 值。 */
    private val asrConfigured: () -> Boolean = { false },
    /** 「今日」起点（Asia/Shanghai 当日 0 点 epoch 毫秒），测试注入固定时钟。 */
    private val todayStartMillis: () -> Long = { currentShanghaiDayStart() }
) {

    // ── DTO（@Serializable，直接作为路由响应体）──

    @Serializable
    data class AdminOverview(
        val totalUsers: Long,
        val totalBills: Long,
        val todayActiveUsers: Long,
        val qqBoundUsers: Long,
        val dbType: String,
        val llmConfigured: Boolean,
        val asrConfigured: Boolean
    )

    @Serializable
    data class AdminUserRow(
        val id: Long,
        val phone: String?,
        val createdAt: String?,
        val billCount: Long,
        /** 绑定通道计数：qq_number / qq_openid 各算一路，0~2。 */
        val boundChannels: Int
    )

    @Serializable
    data class AdminUserPage(
        val page: Int,
        val pageSize: Int,
        val total: Long,
        val totalPages: Int,
        val items: List<AdminUserRow>
    )

    @Serializable
    data class AdminPushLogRow(
        val id: Long,
        val userId: Long,
        val type: String,
        val dayKey: String,
        val pushedAt: Long
    )

    @Serializable
    data class AdminPushLogPage(
        val page: Int,
        val pageSize: Int,
        val total: Long,
        val totalPages: Int,
        val items: List<AdminPushLogRow>
    )

    // ── 查询 ──

    /** 运维大盘：用户 / 账单 / 今日活跃 / QQ 绑定 计数 + DB 类型 + LLM/ASR 配置状态（仅布尔）。 */
    fun overview(): AdminOverview = transaction {
        val totalUsers = UsersTable.selectAll().count()
        // 软删除（deleted=1）的账单不算有效账单。
        val totalBills = BillsTable.selectAll().where { BillsTable.deleted eq false }.count()
        // 今日活跃 = 今日有记账（任意来源、含收入）的去重用户数。
        val todayActive = BillsTable
            .slice(BillsTable.userId)
            .selectAll()
            .where { (BillsTable.deleted eq false) and (BillsTable.date greaterEq todayStartMillis()) }
            .groupBy(BillsTable.userId)
            .count()
        val qqBound = UsersTable.selectAll().where { UsersTable.qqOpenid.isNotNull() }.count()
        AdminOverview(
            totalUsers = totalUsers,
            totalBills = totalBills,
            todayActiveUsers = todayActive,
            qqBoundUsers = qqBound,
            dbType = dbTypeName(),
            llmConfigured = llmConfigured(),
            asrConfigured = asrConfigured()
        )
    }

    /**
     * 用户只读列表：query 匹配手机号尾段（后缀 LIKE）或精确 userId；默认第 1 页、每页 20 条。
     * 手机号只出掩码（138****1234），绝不回原值。
     */
    fun listUsers(query: String?, page: Int, pageSize: Int = DEFAULT_PAGE_SIZE): AdminUserPage = transaction {
        val safePage = page.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val q = query?.trim()

        val filtered = UsersTable.selectAll()
        if (!q.isNullOrEmpty()) {
            val idMatch = q.toLongOrNull()
            filtered.andWhere {
                if (idMatch != null) (UsersTable.phone like "%$q") or (UsersTable.id eq idMatch)
                else UsersTable.phone like "%$q"
            }
        }

        val total = filtered.count()
        val rows = filtered
            .orderBy(UsersTable.id to SortOrder.ASC)
            .limit(safePageSize, offset = (safePage - 1).toLong() * safePageSize)
            .toList()

        // 只为当页用户聚合账单数（含软删除的也算历史账单数？——不算，与大盘口径一致排除软删除）。
        val userIds = rows.map { it[UsersTable.id] }
        val billCountByUser = if (userIds.isEmpty()) emptyMap() else {
            val cnt = BillsTable.id.count()
            BillsTable
                .slice(BillsTable.userId, cnt)
                .selectAll()
                .where { (BillsTable.deleted eq false) and (BillsTable.userId inList userIds) }
                .groupBy(BillsTable.userId)
                .associate { it[BillsTable.userId] to it[cnt] }
        }

        AdminUserPage(
            page = safePage,
            pageSize = safePageSize,
            total = total,
            totalPages = if (total == 0L) 0 else (((total - 1) / safePageSize) + 1).toInt(),
            items = rows.map {
                AdminUserRow(
                    id = it[UsersTable.id],
                    phone = maskPhone(it[UsersTable.phone]),
                    createdAt = it[UsersTable.createdAt],
                    billCount = billCountByUser[it[UsersTable.id]] ?: 0L,
                    boundChannels = listOfNotNull(it[UsersTable.qqNumber], it[UsersTable.qqOpenid]).size
                )
            }
        )
    }

    /**
     * 推送历史（分页倒序）。push_log 只存 type / dayKey / pushed_at 等元数据，
     * 不存推送文案 —— 隐私优先的既有设计；若未来加 content 列，这里也只允许给截断摘要。
     */
    fun listPushLogs(page: Int, pageSize: Int = DEFAULT_PAGE_SIZE): AdminPushLogPage = transaction {
        val safePage = page.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val total = PushLogTable.selectAll().count()
        val rows = PushLogTable.selectAll()
            .orderBy(PushLogTable.id to SortOrder.DESC)
            .limit(safePageSize, offset = (safePage - 1).toLong() * safePageSize)
            .toList()

        AdminPushLogPage(
            page = safePage,
            pageSize = safePageSize,
            total = total,
            totalPages = if (total == 0L) 0 else (((total - 1) / safePageSize) + 1).toInt(),
            items = rows.map {
                AdminPushLogRow(
                    id = it[PushLogTable.id],
                    userId = it[PushLogTable.userId],
                    type = it[PushLogTable.type],
                    dayKey = it[PushLogTable.dayKey],
                    pushedAt = it[PushLogTable.pushedAt]
                )
            }
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100

        /**
         * 手机号掩码：11 位标准号保留前 3 后 4（138****1234）；
         * 非标准长度退化为保留前 2 后 2；过短 / 空一律全掩码，绝不整段回显。
         */
        internal fun maskPhone(phone: String?): String? {
            if (phone.isNullOrBlank()) return null
            return when {
                phone.length >= 11 -> phone.take(3) + "****" + phone.takeLast(4)
                phone.length >= 6 -> phone.take(2) + "****" + phone.takeLast(2)
                else -> "****"
            }
        }

        /** 业务时区（Asia/Shanghai）当日 0 点的 epoch 毫秒（统一走 TimeUtil）。 */
        private fun currentShanghaiDayStart(): Long = TimeUtil.todayStartMillis()
    }
}
