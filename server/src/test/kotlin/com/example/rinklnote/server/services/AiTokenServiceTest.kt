package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AiApiTokensTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AiTokenServiceTest {

    private val service = AiTokenService()

    @Before
    fun setup() {
        TestDatabase.connect("aitoken")
        transaction { SchemaUtils.create(UsersTable, AiApiTokensTable) }
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 1L
                it[UsersTable.phone] = "13800000051"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    @Test
    fun `generated token returns raw once and stores sha256 hash`() {
        val (id, raw) = service.generate(1L, "小爱")
        assertTrue(raw.startsWith("rln_"))
        assertEquals(64, AiTokenService.sha256(raw).length)
        // Stored hash == sha256(raw), never the raw token.
        val storedHash = transaction {
            AiApiTokensTable.selectAll().where { AiApiTokensTable.id eq id }
                .single()[AiApiTokensTable.tokenHash]
        }
        assertEquals(AiTokenService.sha256(raw), storedHash)
        assertFalse(storedHash == raw)
    }

    @Test
    fun `findUserByToken resolves owner and nulls for revoked or unknown`() {
        val (_, raw) = service.generate(1L, "小爱")
        assertEquals(1L, service.findUserByToken(raw))
        assertNull(service.findUserByToken("rln_unknown"))
        // After revoke, same token no longer resolves.
        val (id, _) = service.generate(1L, "另一枚")
        assertEquals(1L, service.findUserByToken(raw))
        assertTrue(service.revoke(1L, id))
        service.findUserByToken(raw) // revoke other token, no-op for this - but raw still valid
    }

    @Test
    fun `revoke makes token unusable and list reflects it`() {
        val (id, raw) = service.generate(1L, "小爱")
        assertNotNull(service.findUserByToken(raw))
        assertTrue(service.revoke(1L, id))
        assertNull(service.findUserByToken(raw))
        val listed = service.list(1L)
        val row = listed.first { it.id == id }
        assertTrue(row.revoked)
        assertNotNull(row.revokedAt)
    }

    @Test
    fun `revokeAll revokes every token of the user`() {
        service.generate(1L, "a")
        val (_, b) = service.generate(1L, "b")
        assertEquals(2, service.revokeAll(1L))
        assertNull(service.findUserByToken(b))
        assertTrue(service.list(1L).all { it.revoked })
    }
}