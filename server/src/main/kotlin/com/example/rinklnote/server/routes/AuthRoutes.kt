package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.InMemoryRateLimiter
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
data class RegisterRequest(val phone: String, val password: String)

@Serializable
data class LoginRequest(val phone: String, val password: String)

@Serializable
data class BindQQRequest(val qqNumber: String)

@Serializable
data class AuthResponse(val userId: Long, val token: String)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class MeResponse(
    val id: Long,
    val phone: String?,
    val qqNumber: String? = null,
    val qqOpenid: String? = null,
    val createdAt: String? = null,
    val aiDisabled: Boolean = false,
    val dailyReportEnabled: Boolean = false,
    val dailyReportHour: Int = 9,
    val dailyReportMinute: Int = 0
)

@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

@Serializable
data class AiSettingRequest(val disabled: Boolean)

@Serializable
data class DailyReportSettingRequest(val enabled: Boolean, val hour: Int, val minute: Int)

@Serializable
data class QqLoginRequest(val code: String)

fun Route.authRoutes(userService: UserService, qqBotService: QQBotService) {
    // In-memory per-IP limiting: blocks brute-force login/password guessing and
    // mass account creation. Generous limits so legit users behind a shared IP
    // are not affected.
    val loginLimiter = InMemoryRateLimiter(maxAttempts = 10, windowSeconds = 600)      // 10 failed / 10 min
    val registerLimiter = InMemoryRateLimiter(maxAttempts = 5, windowSeconds = 3600)   // 5 registrations / hour

    route("/api/auth") {
        post("/register") {
            val ip = call.request.local.remoteHost
            if (registerLimiter.isBlocked(ip)) {
                call.respond(HttpStatusCode.TooManyRequests, MessageResponse("注册过于频繁，请稍后再试"))
                return@post
            }
            val body = call.receive<RegisterRequest>()
            if (body.phone.isBlank() || body.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse("手机号或密码不能为空"))
                return@post
            }
            if (body.password.length < 6) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse("密码长度至少6位"))
                return@post
            }
            val existing = userService.findByPhone(body.phone)
            if (existing != null) {
                registerLimiter.recordFailure(ip)
                call.respond(HttpStatusCode.Conflict, MessageResponse("该手机号已注册"))
                return@post
            }
            val (userId, token) = userService.register(body.phone, body.password)
            call.respond(HttpStatusCode.Created, AuthResponse(userId, token))
        }

        post("/login") {
            val ip = call.request.local.remoteHost
            if (loginLimiter.isBlocked(ip)) {
                call.respond(HttpStatusCode.TooManyRequests, MessageResponse("尝试次数过多，请稍后再试"))
                return@post
            }
            val body = call.receive<LoginRequest>()
            val result = userService.login(body.phone, body.password)
            if (result == null) {
                loginLimiter.recordFailure(ip)
                call.respond(HttpStatusCode.Unauthorized, MessageResponse("手机号或密码错误"))
                return@post
            }
            loginLimiter.recordSuccess(ip)
            val (userId, token) = result
            call.respond(AuthResponse(userId, token))
        }

        post("/qq-login") {
            val ip = call.request.local.remoteHost
            if (loginLimiter.isBlocked(ip)) {
                call.respond(HttpStatusCode.TooManyRequests, MessageResponse("尝试次数过多，请稍后再试"))
                return@post
            }
            val body = call.receive<QqLoginRequest>()
            if (body.code.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse("登录码不能为空"))
                return@post
            }
            val openid = qqBotService.consumeBindCode(body.code)
            if (openid == null) {
                loginLimiter.recordFailure(ip)
                call.respond(HttpStatusCode.Unauthorized, MessageResponse("登录码无效或已过期"))
                return@post
            }
            val user = userService.findByQqOpenid(openid)
            if (user == null) {
                loginLimiter.recordFailure(ip)
                call.respond(HttpStatusCode.NotFound, MessageResponse("该QQ尚未开通账号，请先给机器人发消息"))
                return@post
            }
            loginLimiter.recordSuccess(ip)
            val token = userService.generateToken(user.id, user.phone)
            call.respond(AuthResponse(user.id, token))
        }

        authenticate("auth-jwt") {
            post("/bind-qq") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<BindQQRequest>()
                val success = userService.bindQQ(userId, body.qqNumber)
                if (success) {
                    call.respond(MessageResponse("绑定成功"))
                } else {
                    call.respond(HttpStatusCode.Conflict, MessageResponse("该QQ号已被其他账号绑定"))
                }
            }

            get("/me") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val user = userService.findById(userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("用户不存在"))
                call.respond(
                    MeResponse(
                        id = user.id,
                        phone = user.phone,
                        qqNumber = user.qqNumber,
                        qqOpenid = user.qqOpenid,
                        createdAt = user.createdAt,
                        aiDisabled = user.aiDisabled,
                        dailyReportEnabled = user.dailyReportEnabled,
                        dailyReportHour = user.dailyReportHour,
                        dailyReportMinute = user.dailyReportMinute
                    )
                )
            }

            post("/password") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<ChangePasswordRequest>()
                if (body.oldPassword.isBlank() || body.newPassword.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("密码不能为空"))
                    return@post
                }
                if (body.newPassword.length < 6) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("密码长度至少6位"))
                    return@post
                }
                val success = userService.updatePassword(userId, body.oldPassword, body.newPassword)
                if (success) {
                    call.respond(MessageResponse("密码修改成功"))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, MessageResponse("原密码错误"))
                }
            }

            post("/unbind-qq") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                userService.unbindQQNumber(userId)
                call.respond(MessageResponse("QQ号已解绑"))
            }

            get("/ai") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(mapOf("disabled" to userService.isAiDisabled(userId)))
            }

            put("/ai") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<AiSettingRequest>()
                userService.setAiDisabled(userId, body.disabled)
                call.respond(mapOf("disabled" to body.disabled))
            }

            // 日报推送设置（QQ 端）。命名 daily-report-setting，与 api/insights/daily-report（拉日报内容）区分。
            get("/daily-report-setting") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val user = userService.findById(userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("用户不存在"))
                call.respond(
                    DailyReportSettingRequest(
                        enabled = user.dailyReportEnabled,
                        hour = user.dailyReportHour,
                        minute = user.dailyReportMinute
                    )
                )
            }

            put("/daily-report-setting") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<DailyReportSettingRequest>()
                if (body.hour !in 0..23 || body.minute !in 0..59) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("时间不合法（hour 0-23, minute 0-59）"))
                    return@put
                }
                userService.setDailyReport(userId, body.enabled, body.hour, body.minute)
                call.respond(body)
            }
        }
    }
}
