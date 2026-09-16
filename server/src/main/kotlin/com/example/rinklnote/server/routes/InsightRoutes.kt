package com.example.rinklnote.server.routes

import com.example.rinklnote.server.plugins.requireAdmin
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
            get("/daily-report") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val zone = java.time.ZoneId.of("Asia/Shanghai")
                val now = java.time.ZonedDateTime.now(zone)
                val dayStart = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val stats = insightService.dailyReport(userId, dayStart, dayEnd)
                call.respond(stats)
            }

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

            get("/monthly-anomaly") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val month = call.request.queryParameters["month"]
                    ?: java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))

                val result = insightService.monthlyAnomaly(userId, month)
                call.respond(result)
            }

            get("/monthly-review") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val month = call.request.queryParameters["month"]
                    ?: java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))

                val result = insightService.monthlyReview(userId, month)
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

            // GET 保持所有登录用户可读：设置页要展示当前建议条行为并标注「由管理员配置」。
            get("/suggest-config") {
                call.respond(insightService.loadSuggestConfig())
            }

            // 收口：建议条配置是全局配置，写操作仅限管理员（requireAdmin 未命中直接 403）。
            put("/suggest-config") {
                call.requireAdmin() ?: return@put
                val body = call.receive<InsightService.SuggestConfig>()
                insightService.saveSuggestConfig(body)
                call.respond(mapOf("message" to "配置已保存"))
            }
        }
    }
}
