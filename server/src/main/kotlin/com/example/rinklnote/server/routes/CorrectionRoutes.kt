package com.example.rinklnote.server.routes

import com.example.rinklnote.server.tables.CorrectionLogTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import com.example.rinklnote.server.services.TimeUtil

@Serializable
data class CorrectionRequest(
    val originalText: String,
    val originalCategory: String,
    val correctedCategory: String
)

fun Route.correctionRoutes() {
    authenticate("auth-jwt") {
        route("/api/corrections") {
            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<CorrectionRequest>()
                if (body.originalText.isBlank() || body.correctedCategory.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "参数不能为空"))
                    return@post
                }

                transaction {
                    CorrectionLogTable.insert {
                        it[CorrectionLogTable.userId] = userId
                        it[originalText] = body.originalText
                        it[originalCategory] = body.originalCategory
                        it[correctedCategory] = body.correctedCategory
                        it[processed] = false
                        it[correctedAt] = TimeUtil.now().toString()
                    }
                }
                call.respond(HttpStatusCode.Created, mapOf("message" to "修正已记录"))
            }
        }
    }
}
