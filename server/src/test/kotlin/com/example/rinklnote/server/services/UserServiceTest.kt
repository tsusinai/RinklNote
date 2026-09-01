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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.selectAll

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

    @Test
    fun `setAiDisabled and isAiDisabled roundtrip with default false`() {
        val (id, _) = service.register("13800000006", "pass123456")
        // 默认 false
        assertFalse(service.isAiDisabled(id))
        assertFalse(service.findById(id)!!.aiDisabled)

        service.setAiDisabled(id, true)
        assertTrue(service.isAiDisabled(id))
        assertTrue(service.findById(id)!!.aiDisabled)

        service.setAiDisabled(id, false)
        assertFalse(service.isAiDisabled(id))
    }

    @Test
    fun `findAllBoundQq returns only users with qqOpenid and carries aiDisabled`() {
        // 用户1 有 openid；用户2 只绑 qqNumber、无 openid → 不应出现
        val (id1, _) = service.register("13800000007", "pass123456")
        val (id2, _) = service.register("13800000008", "pass123456")
        service.bindByQqOpenid(id1, "openid-abc")
        service.bindQQ(id2, "111111111")

        val bound = service.findAllBoundQq()
        assertEquals(1, bound.size)
        assertEquals(id1, bound.first().id)
        assertFalse(bound.first().aiDisabled)
    }

    @Test
    fun `createByQqOpenid creates openid account with null phone and is idempotent`() {
        val u1 = service.createByQqOpenid("openid-xxx")
        assertNotNull(u1)
        assertNull(u1.phone)
        assertEquals("openid-xxx", u1.qqOpenid)
        assertNotNull(service.findById(u1.id))
        assertNull(service.findById(u1.id)!!.phone)

        // 同一 openid 再调用返回同一账号，不重复建
        val u2 = service.createByQqOpenid("openid-xxx")
        assertEquals(u1.id, u2.id)
    }

    @Test
    fun `openid account cannot be phone-logged-in and token works for null phone`() {
        val u = service.createByQqOpenid("openid-yyy")
        assertNull(service.login("", "whatever"))
        assertNull(service.findByPhone(""))

        // generateToken 接受 null phone，产出合法 JWT（三段）
        val token = service.generateToken(u.id, u.phone)
        assertEquals(3, token.split(".").size)
    }
}
