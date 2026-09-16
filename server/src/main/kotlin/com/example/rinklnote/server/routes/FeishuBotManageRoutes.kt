package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.FeishuBotService
import com.example.rinklnote.server.services.InMemoryRateLimiter
import com.example.rinklnote.server.services.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * 飞书 Bot 配置请求：appId/appSecret 必填，encryptKey/verificationToken 可空
 * （传空串表示清除该配置）。
 */
@Serializable
data class FeishuBotConfigRequest(
    val appId: String,
    val appSecret: String,
    val encryptKey: String? = null,
    val verificationToken: String? = null
)

/**
 * 飞书 Bot 管理路由（B2）：照 QQBotManageRoutes 的结构 —— JWT 鉴权 + 绑定码防爆破限流，
 * config 读写走 FeishuBotService 的 bot_config KV。
 */
fun Route.feishuBotManageRoutes(feishuBotService: FeishuBotService, userService: UserService) {
    // 6 位绑定码的防爆破闸：每 IP 5 分钟内最多 5 次失败（照 QQ 管理端）
    val bindLimiter = InMemoryRateLimiter(maxAttempts = 5, windowSeconds = 300)

    route("/api/feishu-bot") {
        authenticate("auth-jwt") {
            get("/status") {
                call.respond(mapOf(
                    "configured" to feishuBotService.isConfigured().toString(),
                    "maskedAppId" to (feishuBotService.getMaskedAppId() ?: ""),
                    "message" to if (feishuBotService.isConfigured()) "飞书机器人已配置" else "飞书机器人未配置"
                ))
            }

            get("/config") {
                call.respond(mapOf(
                    "configured" to feishuBotService.isConfigured().toString(),
                    "maskedAppId" to (feishuBotService.getMaskedAppId() ?: ""),
                    "hasSaved" to feishuBotService.hasSavedConfig().toString(),
                    "hasEncryptKey" to (!feishuBotService.getEncryptKey().isNullOrBlank()).toString()
                ))
            }

            post("/config") {
                val body = call.receive<FeishuBotConfigRequest>()
                if (body.appId.isBlank() || body.appSecret.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "AppID 和 AppSecret 不能为空"))
                }
                try {
                    feishuBotService.saveToDb(
                        body.appId, body.appSecret,
                        body.encryptKey.orEmpty(), body.verificationToken.orEmpty()
                    )
                    call.respond(mapOf("message" to "飞书 Bot 配置已保存"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "保存失败: ${e.message}"))
                }
            }

            get("/bind-status") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未登录"))

                val user = userService.findById(userId)
                val openId = user?.feishuOpenId
                call.respond(mapOf(
                    "bound" to (openId != null).toString(),
                    "openId" to (openId?.takeLast(6) ?: ""),
                    "message" to if (openId != null) "已绑定飞书" else "未绑定飞书"
                ))
            }

            post("/bind") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未登录"))

                val ip = call.request.local.remoteHost
                if (bindLimiter.isBlocked(ip)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("message" to "尝试次数过多，请稍后再试"))
                }

                val body = call.receive<BindCodeRequest>()
                if (body.code.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "请输入绑定码"))
                }

                val openId = feishuBotService.consumeBindCode(body.code)
                if (openId == null) {
                    bindLimiter.recordFailure(ip)
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "绑定码无效或已过期"))
                }

                val success = userService.bindFeishuByOpenId(userId, openId)
                if (success) {
                    call.respond(mapOf("message" to "飞书绑定成功"))
                } else {
                    call.respond(HttpStatusCode.Conflict, mapOf("message" to "该飞书账号已被其他用户绑定"))
                }
            }

            post("/unbind") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未登录"))

                userService.unbindFeishu(userId)
                call.respond(mapOf("message" to "已解绑飞书"))
            }
        }
    }
}
