package com.example.rinklnote.server.services

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.example.rinklnote.server.tables.UsersTable

class UserServiceTest {

    private val service = UserService(jwtSecret = "test-secret", jwtIssuer = "test", jwtAudience = "rinklnote")

    @Before
    fun setup() {
        // Fresh in-memory DB per test so the shared JVM's Exposed default is never
        // pointed at a stale database left by another test class.
        TestDatabase.connect("usertest")
        transaction {
            SchemaUtils.create(UsersTable)
            UsersTable.deleteAll()
        }
    }

    @Test
    fun `register creates user and returns working login`() {
        val (id, _) = service.register("13800000001", "pass123456")

        val user = service.findById(id)
        assertNotNull(user)
        assertEquals("13800000001", user!!.phone)

        val login = service.login("13800000001", "pass123456")
        assertNotNull(login)
        assertEquals(id, login!!.first)
        assertNull(service.login("13800000001", "wrong-password"))
    }

    @Test
    fun `updatePassword rejects wrong old password`() {
        val (id, _) = service.register("13800000002", "old123456")

        assertFalse(service.updatePassword(id, "not-the-old", "new123456"))
        // Password unchanged — old one still works
        assertNotNull(service.login("13800000002", "old123456"))
    }

    @Test
    fun `updatePassword with correct old password rotates password`() {
        val (id, _) = service.register("13800000003", "old123456")

        assertTrue(service.updatePassword(id, "old123456", "new123456"))

        assertNull(service.login("13800000003", "old123456"))
        assertNotNull(service.login("13800000003", "new123456"))
    }

    @Test
    fun `updatePassword returns false for unknown user`() {
        assertFalse(service.updatePassword(99999L, "whatever", "whatever"))
    }

    @Test
    fun `bindQQ then unbindQQNumber clears qqNumber`() {
        val (id, _) = service.register("13800000004", "pass123456")

        assertTrue(service.bindQQ(id, "123456789"))
        assertEquals("123456789", service.findById(id)?.qqNumber)

        service.unbindQQNumber(id)
        assertNull(service.findById(id)?.qqNumber)
    }

    @Test
    fun `unbindQQNumber is idempotent on unbounded user`() {
        val (id, _) = service.register("13800000005", "pass123456")

        service.unbindQQNumber(id) // should not throw
        assertNull(service.findById(id)?.qqNumber)
    }
}
