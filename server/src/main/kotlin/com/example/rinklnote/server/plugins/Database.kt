package com.example.rinklnote.server.plugins

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.tables.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
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
        SchemaUtils.create(UsersTable, CategoriesTable, AccountsTable, BillsTable, VoiceKeywordsTable, CorrectionLogTable)
    }

    val billService = BillService()
    billService.seedIfNeeded()
    log.info("Database initialized (${if (isH2) "H2" else "PostgreSQL"}) and seeded")
}
