package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

/** 短期交互票据只保存随机令牌摘要，绑定操作者、具体账单与版本。 */
object BotActionTable : Table("bot_actions") {
    val tokenHash = varchar("token_hash", 64)
    val commandCode = varchar("command_code", 12).uniqueIndex()
    val userId = long("user_id").references(UsersTable.id)
    val channel = varchar("channel", 16)
    val actor = varchar("actor", 128)
    val action = varchar("action", 32)
    val billId = long("bill_id").nullable()
    val billVersion = long("bill_version").nullable()
    val expiresAt = long("expires_at").index()
    val consumed = bool("consumed").default(false)
    override val primaryKey = PrimaryKey(tokenHash)
}
