package com.example.rinklnote.server.plugins

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.tables.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

fun Application.configureDatabase() {
    val url = System.getenv("DATABASE_URL")
        ?: environment.config.propertyOrNull("database.url")?.getString()
        ?: "jdbc:h2:mem:rinklnote;DB_CLOSE_DELAY=-1"

    val isH2 = url.startsWith("jdbc:h2")

    val config = HikariConfig().apply {
        jdbcUrl = url
        if (!isH2) {
            username = environment.config.propertyOrNull("database.user")?.getString() ?: "rinklnote"
            password = environment.config.propertyOrNull("database.password")?.getString() ?: "rinklnote"
        }
        driverClassName = if (isH2) "org.h2.Driver" else "org.postgresql.Driver"
        maximumPoolSize = if (isH2) 2 else 10
    }

    Database.connect(HikariDataSource(config))

    transaction {
        SchemaUtils.createMissingTablesAndColumns(UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable, VoiceKeywordsTable, CorrectionLogTable, BotConfigTable, BillTemplatesTable)

        // Performance indexes (not created by createMissingTablesAndColumns)
        runMigrations()
    }

    val billService = BillService()
    billService.seedIfNeeded()
    log.info("Database initialized (${if (isH2) "H2" else "PostgreSQL"}) and seeded")
}

private fun Transaction.runMigrations() {
    val indexes = listOf(
        "CREATE INDEX IF NOT EXISTS idx_bills_user_id ON bills(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_bills_user_date ON bills(user_id, date)",
        "CREATE INDEX IF NOT EXISTS idx_corrections_processed ON correction_log(processed, user_id)",
        "CREATE INDEX IF NOT EXISTS idx_templates_user ON bill_templates(user_id)",
    )
    indexes.forEach { sql ->
        try {
            exec(sql)
        } catch (_: Exception) {
            // Ignore "index already exists" errors
        }
    }
}
