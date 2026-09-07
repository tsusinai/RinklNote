package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.AiAssistService
import com.example.rinklnote.server.services.AiTokenService
import com.example.rinklnote.server.services.PhoneIntentRouter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class AiGenerateTokenRequest(val name: String = "小爱")

@Serializable
data class AiAskRequest(val text: String)

@Serializable
data class AiRecordRequest(
    val amount: Double,
    val category: String? = null,
    val type: String? = null,
    val remark: String? = null,
    val date: Long? = null
)

@Serializable
data class AiTokenResponse(val id: Long, val token: String, val name: String, val createdAt: Long)

fun Route.aiAssistantRoutes(
    phoneIntentRouter: PhoneIntentRouter,
    aiAssistService: AiAssistService,
    aiTokenService: AiTokenService
) {
    // 个人令牌校验：Authorization: Bearer <token> → 查哈希 → userId，未命中/已作废 → null。
    suspend fun io.ktor.server.application.ApplicationCall.aiUserIdOrNull(): Long? {
        val header = request.headers["Authorization"] ?: return null
        if (!header.startsWith("Bearer ")) return null
        return aiTokenService.findUserByToken(header.removePrefix("Bearer ").trim())
    }

    route("/api/ai") {
        // ── 令牌管理：走现有 JWT 登录态（用 App 的 Bearer JWT） ──
        authenticate("auth-jwt") {
            post("/tokens") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<AiGenerateTokenRequest>()
                val name = body.name.ifBlank { "小爱" }.take(60)
                val now = System.currentTimeMillis()
                val (id, raw) = aiTokenService.generate(userId, name, now)
                call.respond(HttpStatusCode.Created, AiTokenResponse(id, raw, name, now))
            }

            get("/tokens") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(aiTokenService.list(userId))
            }

            post("/tokens/{id}/revoke") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
                if (aiTokenService.revoke(userId, id)) call.respond(mapOf("message" to "已作废"))
                else call.respond(HttpStatusCode.NotFound, mapOf("message" to "令牌不存在"))
            }

            post("/tokens/revoke-all") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                aiTokenService.revokeAll(userId)
                call.respond(mapOf("message" to "已全部作废"))
            }
        }

        // ── AI 入口：个人令牌鉴权（Bearer 访问令牌，复用 JWT 之外的个人令牌） ──
        post("/ask") {
            val userId = call.aiUserIdOrNull()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            val body = call.receive<AiAskRequest>()
            if (body.text.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("reply" to "没听清内容，试试「午餐20元」"))
            }
            // 复用完整 NLU/intent 管线；记账 source 落 "AI"。month 由自然语言里解析，无需单独传。
            val reply = phoneIntentRouter.route(body.text, userId, "AI")
            call.respond(mapOf("reply" to reply))
        }

        post("/record") {
            val userId = call.aiUserIdOrNull()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            val body = call.receive<AiRecordRequest>()
            if (body.amount <= 0 || !body.amount.isFinite()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("reply" to "没听清金额，试试「午餐20元」"))
            }
            // createBill 由分类名推导 EXPENSE/INCOME；type 字段为描述性，不参与落库判断。
            call.respond(aiAssistService.record(userId, body.amount, body.category, body.remark))
        }

        get("/today") {
            val userId = call.aiUserIdOrNull()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            call.respond(aiAssistService.today(userId))
        }

        get("/month") {
            val userId = call.aiUserIdOrNull()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            call.respond(aiAssistService.month(userId))
        }

        get("/balance") {
            val userId = call.aiUserIdOrNull()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            call.respond(aiAssistService.balance(userId))
        }

        get("/summary") {
            val userId = call.aiUserIdOrNull()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("reply" to "未授权：令牌缺失或已失效"))
            val month = call.request.queryParameters["month"]
            call.respond(aiAssistService.summary(userId, month))
        }
    }
}