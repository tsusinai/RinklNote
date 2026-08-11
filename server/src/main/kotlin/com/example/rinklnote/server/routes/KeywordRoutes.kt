package com.example.rinklnote.server.routes

import com.example.rinklnote.server.tables.VoiceKeywordsTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

@Serializable
data class KeywordRequest(
    val keyword: String,
    val categoryName: String,
    val priority: Int = 10
)

@Serializable
data class KeywordDTO(
    val id: Long,
    val keyword: String,
    val categoryName: String,
    val priority: Int
)

fun Route.keywordRoutes() {
    authenticate("auth-jwt") {
        route("/api/keywords") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val keywords = transaction {
                    VoiceKeywordsTable.selectAll()
                        .where { VoiceKeywordsTable.userId eq userId }
                        .orderBy(VoiceKeywordsTable.priority, SortOrder.DESC)
                        .map {
                            KeywordDTO(
                                id = it[VoiceKeywordsTable.id],
                                keyword = it[VoiceKeywordsTable.keyword],
                                categoryName = it[VoiceKeywordsTable.categoryName],
                                priority = it[VoiceKeywordsTable.priority]
                            )
                        }
                }
                call.respond(keywords)
            }

            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<KeywordRequest>()
                if (body.keyword.isBlank() || body.categoryName.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "关键词和分类不能为空"))
                    return@post
                }

                val id = transaction {
                    // Skip duplicate
                    val exists = VoiceKeywordsTable.selectAll()
                        .where {
                            (VoiceKeywordsTable.userId eq userId) and
                            (VoiceKeywordsTable.keyword eq body.keyword)
                        }.singleOrNull()
                    if (exists != null) {
                        VoiceKeywordsTable.update({ VoiceKeywordsTable.id eq exists[VoiceKeywordsTable.id] }) {
                            it[categoryName] = body.categoryName
                            it[priority] = body.priority
                        }
                        exists[VoiceKeywordsTable.id]
                    } else {
                        VoiceKeywordsTable.insert {
                            it[VoiceKeywordsTable.userId] = userId
                            it[VoiceKeywordsTable.keyword] = body.keyword
                            it[VoiceKeywordsTable.categoryName] = body.categoryName
                            it[VoiceKeywordsTable.priority] = body.priority
                            it[VoiceKeywordsTable.createdAt] = LocalDateTime.now().toString()
                        } get VoiceKeywordsTable.id
                    }
                }
                call.respond(HttpStatusCode.Created, mapOf("id" to id))
            }

            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val currentUserId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized)

                val keywordId = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))

                val deleted = transaction {
                    VoiceKeywordsTable.deleteWhere {
                        Op.build { (VoiceKeywordsTable.id eq keywordId) and (VoiceKeywordsTable.userId eq currentUserId) }
                    }
                }
                if (deleted > 0) {
                    call.respond(mapOf("message" to "删除成功"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "关键词不存在"))
                }
            }
        }
    }
}
