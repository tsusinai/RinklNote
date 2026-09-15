package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.InMemoryRateLimiter
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.WecomBotService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * 企微 Bot 配置请求：token/encodingAesKey 必填，pushWebhookUrl 可空
 * （传空串表示清除「消息推送」webhook 配置）。
 */
@Serializable
data class WecomBotConfigRequest(
    val token: String,
    val encodingAesKey: String,
    val pushWebhookUrl: String? = null
)

/**
 * 企微 Bot 管理路由（Phase D 任务 0）：逐字镜像 FeishuBotManageRoutes 的结构 ——
 * JWT 鉴权 + 绑定码防爆破限流，config 读写走 WecomBotService 的 bot_config KV。
 * 至此 QQ / 飞书 / 企微三通道管理面完全对称（App 端绑定码流程三通道同构）。
 */
fun Route.wecomBotManageRoutes(wecomBotService: WecomBotService, userService: UserService) {
    // 6 位绑定码的防爆破闸：每 IP 5 分钟内最多 5 次失败（照 QQ / 飞书管理端）
    val bindLimiter = InMemoryRateLimiter(maxAttempts = 5, windowSeconds = 300)

    route("/api/wecom-bot") {
        authenticate("auth-jwt") {
            get("/status") {
                call.respond(mapOf(
                    "configured" to wecomBotService.isConfigured().toString(),
                    "maskedToken" to (wecomBotService.getMaskedToken() ?: ""),
                    "message" to if (wecomBotService.isConfigured()) "企微机器人已配置" else "企微机器人未配置"
                ))
            }

            get("/config") {
                call.respond(mapOf(
                    "configured" to wecomBotService.isConfigured().toString(),
                    "maskedToken" to (wecomBotService.getMaskedToken() ?: ""),
                    "hasSaved" to wecomBotService.hasSavedConfig().toString(),
                    "hasPushWebhook" to (!wecomBotService.getPushWebhookUrl().isNullOrBlank()).toString()
                ))
            }

            post("/config") {
                val body = call.receive<WecomBotConfigRequest>()
                if (body.token.isBlank() || body.encodingAesKey.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Token 和 EncodingAESKey 不能为空"))
                }
                try {
                    wecomBotService.saveToDb(
                        body.token, body.encodingAesKey,
                        body.pushWebhookUrl.orEmpty()
                    )
                    call.respond(mapOf("message" to "企微 Bot 配置已保存"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "保存失败: ${e.message}"))
                }
            }

            get("/bind-status") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未登录"))

                val user = userService.findById(userId)
                val userid = user?.wecomUserid
                call.respond(mapOf(
                    "bound" to (userid != null).toString(),
                    "openId" to (userid?.takeLast(6) ?: ""),
                    "message" to if (userid != null) "已绑定企业微信" else "未绑定企业微信"
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

                val userid = wecomBotService.consumeBindCode(body.code)
                if (userid == null) {
                    bindLimiter.recordFailure(ip)
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "绑定码无效或已过期"))
                }

                val success = userService.bindWecomByUserid(userId, userid)
                if (success) {
                    call.respond(mapOf("message" to "企业微信绑定成功"))
                } else {
                    call.respond(HttpStatusCode.Conflict, mapOf("message" to "该企业微信已被其他用户绑定"))
                }
            }

            post("/unbind") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "未登录"))

                userService.unbindWecom(userId)
                call.respond(mapOf("message" to "已解绑企业微信"))
            }
        }
    }
}
