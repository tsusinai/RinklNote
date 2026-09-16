package com.example.rinklnote.server.routes

import com.example.rinklnote.server.plugins.requireAdmin
import com.example.rinklnote.server.services.AdminService
import com.example.rinklnote.server.services.QQBotService
import com.example.rinklnote.server.services.QQBotWebSocketClient
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/** 机器人运维状态（token 有效期 + WS 网关在线 + 配置情况）。 */
@Serializable
data class AdminBotStatus(
    val configured: Boolean,
    val maskedAppId: String?,
    /** 当前缓存 access token 的过期时刻（epoch 秒）；0 = 尚未获取过 token。 */
    val tokenExpiresAt: Long,
    /** WS 网关是否在线（已完成 Identify 且 socket 仍在收帧）。 */
    val gatewayOnline: Boolean,
    /** 后台重连协程是否在跑（含退避重试中，未必然在线）。 */
    val gatewayStarted: Boolean
)

/**
 * 管理端只读 API（前缀 /api/admin）。全部 GET + requireAdmin（见 plugins/Security.kt）。
 * 注意：Kotlin 块注释可嵌套，注释里别写「/api/admin/ + 星号」这种字样，会吃掉整个文件。
 * 隐私红线见 AdminService 类注释：只出聚合计数与运维元数据，绝不出备注 / 金额明细。
 */
fun Route.adminRoutes(
    adminService: AdminService,
    qqBotService: QQBotService,
    qqWsClient: QQBotWebSocketClient
) {
    route("/api/admin") {
        authenticate("auth-jwt") {
            get("/overview") {
                call.requireAdmin() ?: return@get
                call.respond(adminService.overview())
            }

            get("/users") {
                call.requireAdmin() ?: return@get
                val query = call.request.queryParameters["query"]
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                call.respond(adminService.listUsers(query, page))
            }

            get("/push-logs") {
                call.requireAdmin() ?: return@get
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                call.respond(adminService.listPushLogs(page))
            }

            get("/bot/status") {
                call.requireAdmin() ?: return@get
                call.respond(
                    AdminBotStatus(
                        configured = qqBotService.isConfigured(),
                        maskedAppId = qqBotService.getMaskedAppId(),
                        tokenExpiresAt = qqBotService.getTokenExpiresAtEpochSec(),
                        gatewayOnline = qqWsClient.isGatewayOnline(),
                        gatewayStarted = qqWsClient.isGatewayStarted()
                    )
                )
            }
        }
    }
}
