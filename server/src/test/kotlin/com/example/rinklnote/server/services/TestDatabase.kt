package com.example.rinklnote.server.services

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.UUID

/**
 * Helper for Exposed service tests.
 *
 * Each call connects a brand-new in-memory H2 database and pins it as Exposed's
 * default. A fresh, uniquely-named database per test avoids two classic Exposed
 * test traps:
 *
 *  1. H2 identity sequences are NOT reset by `DELETE FROM`, so re-seeding rows
 *     (e.g. categories) on a shared in-memory DB produces ids that drift upward
 *     and break tests that assume `categoryId = 1`.
 *  2. Exposed's default database is "most recently connected", but the thread's
 *     transaction manager is cached in a `ThreadLocal` and is NOT refreshed when a
 *     new database is registered — so a previous test class's manager can leak
 *     across classes in the same JVM and point at a stale database.
 */
object TestDatabase {
    fun connect(prefix: String) {
        val name = "mem:${prefix}_${UUID.randomUUID().toString().substring(0, 8)}"
        val db = Database.connect(
            "jdbc:h2:$name;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = db
        TransactionManager.resetCurrent(null)
    }
}
