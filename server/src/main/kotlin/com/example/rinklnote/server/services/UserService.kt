package com.example.rinklnote.server.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import com.example.rinklnote.server.tables.UsersTable
import java.time.LocalDateTime
import java.util.*

data class UserInfo(
    val id: Long,
    val phone: String,
    val qqNumber: String?,
    val qqOpenid: String?,
    val createdAt: String? = null
)

class UserService(
    private val jwtSecret: String,
    private val jwtIssuer: String,
    private val jwtAudience: String
) {
    fun register(phone: String, password: String): Pair<Long, String> {
        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        val userId = transaction {
            UsersTable.insert {
                it[UsersTable.phone] = phone
                it[passwordHash] = hash
                it[createdAt] = LocalDateTime.now().toString()
            } get UsersTable.id
        }
        val token = generateToken(userId, phone)
        return Pair(userId, token)
    }

    fun login(phone: String, password: String): Pair<Long, String>? {
        val user = transaction {
            UsersTable.selectAll().where { UsersTable.phone eq phone }.singleOrNull()
        } ?: return null

        val hash = user[UsersTable.passwordHash]
        if (!BCrypt.checkpw(password, hash)) return null

        val userId = user[UsersTable.id]
        val token = generateToken(userId, phone)
        return Pair(userId, token)
    }

    fun bindQQ(userId: Long, qqNumber: String): Boolean {
        return transaction {
            val existing = UsersTable.selectAll().where { UsersTable.qqNumber eq qqNumber }.singleOrNull()
            if (existing != null && existing[UsersTable.id] != userId) return@transaction false

            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.qqNumber] = qqNumber
            }
            true
        }
    }

    fun findById(id: Long): UserInfo? {
        return transaction {
            UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.let {
                UserInfo(id = it[UsersTable.id], phone = it[UsersTable.phone], qqNumber = it[UsersTable.qqNumber], qqOpenid = it[UsersTable.qqOpenid], createdAt = it[UsersTable.createdAt])
            }
        }
    }

    fun findByPhone(phone: String): UserInfo? {
        return transaction {
            UsersTable.selectAll().where { UsersTable.phone eq phone }.singleOrNull()?.let {
                UserInfo(id = it[UsersTable.id], phone = it[UsersTable.phone], qqNumber = it[UsersTable.qqNumber], qqOpenid = it[UsersTable.qqOpenid], createdAt = it[UsersTable.createdAt])
            }
        }
    }

    fun findByQQ(qqNumber: String): UserInfo? {
        return transaction {
            UsersTable.selectAll().where { UsersTable.qqNumber eq qqNumber }.singleOrNull()?.let {
                UserInfo(id = it[UsersTable.id], phone = it[UsersTable.phone], qqNumber = it[UsersTable.qqNumber], qqOpenid = it[UsersTable.qqOpenid], createdAt = it[UsersTable.createdAt])
            }
        }
    }

    fun findByQqOpenid(openid: String): UserInfo? {
        return transaction {
            UsersTable.selectAll().where { UsersTable.qqOpenid eq openid }.singleOrNull()?.let {
                UserInfo(id = it[UsersTable.id], phone = it[UsersTable.phone], qqNumber = it[UsersTable.qqNumber], qqOpenid = it[UsersTable.qqOpenid], createdAt = it[UsersTable.createdAt])
            }
        }
    }

    fun bindByQqOpenid(userId: Long, openid: String): Boolean {
        return transaction {
            val existing = UsersTable.selectAll().where { UsersTable.qqOpenid eq openid }.singleOrNull()
            if (existing != null && existing[UsersTable.id] != userId) return@transaction false

            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.qqOpenid] = openid
            }
            true
        }
    }

    fun unbindQq(userId: Long) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.qqOpenid] = null
            }
        }
    }

    fun unbindQQNumber(userId: Long) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.qqNumber] = null
            }
        }
    }

    fun updatePassword(userId: Long, oldPassword: String, newPassword: String): Boolean {
        return transaction {
            val hash = UsersTable.selectAll()
                .where { UsersTable.id eq userId }
                .singleOrNull()?.get(UsersTable.passwordHash)
                ?: return@transaction false
            if (!BCrypt.checkpw(oldPassword, hash)) return@transaction false
            UsersTable.update({ UsersTable.id eq userId }) {
                it[passwordHash] = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            }
            true
        }
    }

    private fun generateToken(userId: Long, phone: String): String {
        return JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("phone", phone)
            .withExpiresAt(Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000))
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
