package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.insight.InsightService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class QueryRequest(val query: String)

fun Route.insightRoutes(insightService: InsightService) {
    authenticate("auth-jwt") {
        route("/api/insights") {
            get("/monthly") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val month = call.request.queryParameters["month"]
                    ?: java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))

                val result = insightService.monthlySummary(userId, month)
                call.respond(result)
            }

            get("/anomaly") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val result = insightService.anomalyCheck(userId)
                call.respond(result)
            }

            post("/query") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<QueryRequest>()
                if (body.query.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "查询内容不能为空"))
                    return@post
                }

                val result = insightService.naturalQuery(userId, body.query)
                call.respond(result)
            }

            get("/habit") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val result = insightService.habitForApp(userId)
                call.respond(result)
            }

            get("/suggest") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val suggestion = insightService.suggestDailyPattern(userId)
                if (suggestion != null) call.respond(suggestion)
                else call.respond(mapOf("empty" to "true"))
            }

            get("/suggest-config") {
                call.respond(insightService.loadSuggestConfig())
            }

            put("/suggest-config") {
                val body = call.receive<InsightService.SuggestConfig>()
                insightService.saveSuggestConfig(body)
                call.respond(mapOf("message" to "配置已保存"))
            }
        }
    }
}
