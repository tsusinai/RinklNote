package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.RateService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 汇率接口（2026-09-18 Task 4.2）：GET /api/rates。
 * 响应契约（钉死）：{"base":"CNY","rates":{...每 1 单位该币种兑 CNY...},"updatedAt":"ISO-8601"}
 * rates 语义与 App DEMO_RATES_VS_CNY 一致；登录用户可读（JWT 保护，与业务接口同策略）。
 */
fun Route.rateRoutes(rateService: RateService) {
    authenticate("auth-jwt") {
        get("/api/rates") {
            call.respond(rateService.snapshotPayload())
        }
    }
}
