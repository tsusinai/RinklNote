package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object BotConfigTable : Table("bot_config") {
    val key = varchar("key", 50)
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}
