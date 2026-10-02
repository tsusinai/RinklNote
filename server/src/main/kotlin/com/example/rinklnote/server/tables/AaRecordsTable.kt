package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

/**
 * AA 分账台账（阶段 2 群聊 AA 分账，2026-10-03）：
 * 每发起一次 AA 记一行「承诺与拆分结果」，成员各自实付不追踪（发起人自付份额落 bills）。
 * settled 由用户回「AA结清 编号」手动标记；软删沿用 bills 的 deleted 口径。
 */
object AaRecordsTable : Table("aa_records") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    /** 群聊发起时记录 group_openid，供「AA记录」在群内优先展示本群账目；私聊为 null。 */
    val groupOpenid = varchar("group_openid", 64).nullable()
    /** 事由（聚餐 / 打车…），解析不出时为「聚会」。 */
    val title = varchar("title", 50)
    /** 总金额（整数分）。 */
    val totalMinor = long("total_minor")
    /** 参与平摊人数。 */
    val headCount = integer("head_count")
    /** 成员描述串（每人应付清单，如「人1:1600;人2:1600;人3:1600」，金额为分）。 */
    val membersText = varchar("members_text", 500).nullable()
    /** 是否已结清（AA结清 指令标记）。 */
    val settled = bool("settled").default(false)
    val createdAt = long("created_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
}
