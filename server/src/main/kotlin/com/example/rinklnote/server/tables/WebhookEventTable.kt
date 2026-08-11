package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

/**
 * Dedup ledger for QQ bot webhook events. QQ redelivers an event when the previous
 * delivery is not ACKed in time; without idempotency a retry would create a
 * duplicate bill. Each processed event is inserted once (event_id is the primary
 * key), so a concurrent or delayed retry is rejected.
 */
object WebhookEventTable : Table("webhook_events") {
    val eventId = varchar("event_id", 64)
    val processedAt = long("processed_at")

    override val primaryKey = PrimaryKey(eventId)
}
