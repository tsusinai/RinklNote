package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.QQBotService
import com.example.rinklnote.server.services.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class BindCodeRequest(val code: String)

@Serializable
data class BotConfigRequest(val appId: String, val clientSecret: String)

@Serializable
data class QQBindStatusResponse(val bound: Boolean, val openid: String? = null)

fun Route.qqBotManageRoutes(qqBotService: QQBotService, userService: UserService) {
    route("/api/qq-bot") {
        authenticate("auth-jwt") {
            get("/status") {
                call.respond(mapOf(
                    "configured" to qqBotService.isConfigured().toString(),
                    "maskedAppId" to (qqBotService.getMaskedAppId() ?: ""),
                    "message" to if (qqBotService.isConfigured()) "QQ 机器人已配置" else "QQ 机器人未配置"
                ))
            }

            get("/config") {
                call.respond(mapOf(
                    "configured" to qqBotService.isConfigured().toString(),
                    "maskedAppId" to (qqBotService.getMaskedAppId() ?: ""),
                    "hasSaved" to qqBotService.hasSavedConfig().toString()
                ))
            }

            post("/config") {
                val body = call.receive<BotConfigRequest>()
                if (body.appId.isBlank() || body.clientSecret.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "AppID 和 ClientSecret 不能为空"))
                }
                try {
                    qqBotService.saveToDb(body.appId, body.clientSecret)
                    call.respond(mapOf("message" to "QQ Bot 配置已保存"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "保存失败: ${e.message}"))
                }
            }

            get("/bind-status") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未登录"))

                val user = userService.findById(userId)
                val openid = user?.qqOpenid
                call.respond(mapOf(
                    "bound" to (openid != null).toString(),
                    "openid" to (openid?.takeLast(6) ?: ""),
                    "message" to if (openid != null) "已绑定 QQ" else "未绑定 QQ"
                ))
            }

            post("/bind") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未登录"))

                val body = call.receive<BindCodeRequest>()
                if (body.code.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "请输入绑定码"))
                }

                val openid = qqBotService.consumeBindCode(body.code)
                if (openid == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "绑定码无效或已过期"))
                }

                val success = userService.bindByQqOpenid(userId, openid)
                if (success) {
                    call.respond(mapOf("message" to "QQ 绑定成功"))
                } else {
                    call.respond(HttpStatusCode.Conflict, mapOf("message" to "该 QQ 已被其他账号绑定"))
                }
            }

            post("/unbind") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未登录"))

                userService.unbindQq(userId)
                call.respond(mapOf("message" to "已解绑 QQ"))
            }
        }
    }
}
