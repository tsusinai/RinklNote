package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.ChallengesTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/** 合法挑战类型（与 App 端 Challenge 实体取值一致，路由层据此校验）。 */
val CHALLENGE_TYPES = setOf("NO_SPEND_DAY", "BOOKKEEPING_STREAK", "WEEKLY_BUDGET")

/** 合法挑战状态。 */
val CHALLENGE_STATUSES = setOf("ACTIVE", "ACHIEVED", "MISSED")

@Serializable
data class ChallengeDTO(
    val id: Long,
    val type: String,
    val periodStart: Long,
    // 目标值。单位由 type 决定：NO_SPEND_DAY / BOOKKEEPING_STREAK = 天数；WEEKLY_BUDGET = 整数分。
    val goal: Long,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deleted: Boolean = false
)

@Serializable
data class UpsertChallengeRequest(
    val type: String,
    val periodStart: Long,
    val goal: Long,
    val status: String = "ACTIVE",
    // 客户端行的 updated_at（毫秒），用于服务端 LWW：比服务端现行 updated_at 旧的写入不覆盖。
    // 不带则视为直接写入（交互式创建/旧客户端），以服务器时间为准，行为与预算 PUT 一致。
    val updatedAt: Long? = null
)

class ChallengeService {

    /**
     * 当前用户全部挑战（含已软删行，供客户端 pull 时清理本地墓碑）。
     */
    fun list(userId: Long): List<ChallengeDTO> = transaction {
        ChallengesTable.selectAll()
            .where { ChallengesTable.userId eq userId }
            .map { it.toDTO() }
    }

    /**
     * Create-or-update for a (user, type, periodStart) scope. Returns the current row.
     *
     * LWW（Last-Writer-Wins）：[clientUpdatedAt] 为客户端行的 updated_at，与服务端现行值比较，
     * 新者胜 —— 旧写入不覆盖新行（原样返回服务端现行行）；命中已有行时应用写入并复活 deleted=false。
     * 不带 [clientUpdatedAt] 则无条件应用（以服务器时间为准）。时间戳统一由服务器时钟生成，
     * 与预算服务的 created_at/updated_at 口径一致。
     */
    fun upsert(
        userId: Long,
        type: String,
        periodStart: Long,
        goal: Long,
        status: String = "ACTIVE",
        clientUpdatedAt: Long? = null
    ): ChallengeDTO {
        val now = System.currentTimeMillis()
        return transaction {
            val existing = ChallengesTable.selectAll()
                .where {
                    (ChallengesTable.userId eq userId) and
                        (ChallengesTable.type eq type) and
                        (ChallengesTable.periodStart eq periodStart)
                }
                .singleOrNull()

            val rowId = if (existing != null) {
                val existingId = existing[ChallengesTable.id]
                val existingUpdatedAt = existing[ChallengesTable.updatedAt]
                if (clientUpdatedAt != null && existingUpdatedAt != null && clientUpdatedAt < existingUpdatedAt) {
                    // 客户端写入比服务端现行行旧（LWW 新者胜）→ 不覆盖，也不复活软删行。
                    existingId
                } else {
                    ChallengesTable.update({ ChallengesTable.id eq existingId }) {
                        it[ChallengesTable.goal] = goal
                        it[ChallengesTable.status] = status
                        it[ChallengesTable.deleted] = false
                        it[ChallengesTable.updatedAt] = now
                    }
                    existingId
                }
            } else {
                ChallengesTable.insert {
                    it[ChallengesTable.userId] = userId
                    it[ChallengesTable.type] = type
                    it[ChallengesTable.periodStart] = periodStart
                    it[ChallengesTable.goal] = goal
                    it[ChallengesTable.status] = status
                    it[ChallengesTable.createdAt] = now
                    it[ChallengesTable.updatedAt] = now
                } get ChallengesTable.id
            }
            rowId
        }.let { id ->
            transaction {
                ChallengesTable.selectAll().where { ChallengesTable.id eq id }.single().toDTO()
            }
        }
    }

    private fun ResultRow.toDTO() = ChallengeDTO(
        id = this[ChallengesTable.id],
        type = this[ChallengesTable.type],
        periodStart = this[ChallengesTable.periodStart],
        goal = this[ChallengesTable.goal],
        status = this[ChallengesTable.status],
        createdAt = this[ChallengesTable.createdAt],
        updatedAt = this[ChallengesTable.updatedAt],
        deleted = this[ChallengesTable.deleted]
    )
}
