package com.example.rinklnote.server.routes

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
    val phone: String,
    val qqNumber: String? = null,
    val qqOpenid: String? = null,
    val createdAt: String? = null
)

@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

fun Route.authRoutes(userService: UserService) {
    route("/api/auth") {
        post("/register") {
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
                call.respond(HttpStatusCode.Conflict, MessageResponse("该手机号已注册"))
                return@post
            }
            val (userId, token) = userService.register(body.phone, body.password)
            call.respond(HttpStatusCode.Created, AuthResponse(userId, token))
        }

        post("/login") {
            val body = call.receive<LoginRequest>()
            val result = userService.login(body.phone, body.password)
            if (result == null) {
                call.respond(HttpStatusCode.Unauthorized, MessageResponse("手机号或密码错误"))
                return@post
            }
            val (userId, token) = result
            call.respond(AuthResponse(userId, token))
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
                        createdAt = user.createdAt
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
        }
    }
}
