package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.CHALLENGE_STATUSES
import com.example.rinklnote.server.services.CHALLENGE_TYPES
import com.example.rinklnote.server.services.ChallengeService
import com.example.rinklnote.server.services.UpsertChallengeRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.challengeRoutes(challengeService: ChallengeService) {
    authenticate("auth-jwt") {
        route("/api/challenges") {
            // 全量列表（含已软删行，供客户端 pull 时清理本地墓碑）。
            get {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(challengeService.list(userId))
            }

            // Upsert：scope 唯一键 = user_id + type + period_start；命中则按 LWW 更新并复活，否则创建。
            put {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<UpsertChallengeRequest>()
                require(body.type in CHALLENGE_TYPES) { "挑战类型不合法" }
                require(body.status in CHALLENGE_STATUSES) { "挑战状态不合法" }
                require(body.goal >= 0) { "挑战目标不合法" }
                call.respond(
                    challengeService.upsert(
                        userId,
                        body.type,
                        body.periodStart,
                        body.goal,
                        body.status,
                        body.updatedAt
                    )
                )
            }
        }
    }
}
