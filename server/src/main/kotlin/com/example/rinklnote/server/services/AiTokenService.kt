package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.AiApiTokensTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class AiTokenDTO(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val revoked: Boolean,
    val revokedAt: Long? = null
)

class AiTokenService {

    companion object {
        private val RANDOM = SecureRandom()

        fun sha256(token: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun generateRawToken(): String {
            val bytes = ByteArray(32)
            RANDOM.nextBytes(bytes)
            return "rln_" + bytes.joinToString("") { "%02x".format(it) }
        }
    }

    /** Returns (id, rawToken). Plaintext returned exactly once; only its SHA-256 hash is persisted. */
    fun generate(userId: Long, name: String, now: Long = System.currentTimeMillis()): Pair<Long, String> {
        val raw = generateRawToken()
        val hash = sha256(raw)
        val id = transaction {
            AiApiTokensTable.insert {
                it[AiApiTokensTable.userId] = userId
                it[AiApiTokensTable.tokenHash] = hash
                it[AiApiTokensTable.name] = name
                it[AiApiTokensTable.createdAt] = now
            } get AiApiTokensTable.id
        }
        return id to raw
    }

    fun list(userId: Long): List<AiTokenDTO> = transaction {
        AiApiTokensTable.selectAll()
            .where { AiApiTokensTable.userId eq userId }
            .orderBy(AiApiTokensTable.id to SortOrder.ASC)
            .map { it.toDTO() }
    }

    fun revoke(userId: Long, id: Long, now: Long = System.currentTimeMillis()): Boolean = transaction {
        val updated = AiApiTokensTable.update({
            (AiApiTokensTable.id eq id) and (AiApiTokensTable.userId eq userId)
        }) {
            it[revokedAt] = now
        }
        updated > 0
    }

    fun revokeAll(userId: Long, now: Long = System.currentTimeMillis()): Int = transaction {
        AiApiTokensTable.update({ AiApiTokensTable.userId eq userId }) {
            it[revokedAt] = now
        }
    }

    /** Resolves a raw bearer token to a userId, or null if unknown / revoked. */
    fun findUserByToken(rawToken: String): Long? {
        val hash = sha256(rawToken)
        return transaction {
            AiApiTokensTable.selectAll()
                .where { (AiApiTokensTable.tokenHash eq hash) and (AiApiTokensTable.revokedAt.isNull()) }
                .singleOrNull()?.get(AiApiTokensTable.userId)
        }
    }

    private fun ResultRow.toDTO() = AiTokenDTO(
        id = this[AiApiTokensTable.id],
        name = this[AiApiTokensTable.name],
        createdAt = this[AiApiTokensTable.createdAt],
        revoked = this[AiApiTokensTable.revokedAt] != null,
        revokedAt = this[AiApiTokensTable.revokedAt]
    )
}