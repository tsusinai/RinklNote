package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.UpsertBudgetRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.budgetRoutes(budgetService: BudgetService) {
    authenticate("auth-jwt") {
        route("/api/budgets") {
            get("/summary") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val periodStart = call.request.queryParameters["periodStart"]?.toLongOrNull()
                    ?: System.currentTimeMillis()
                call.respond(budgetService.summary(userId, periodStart))
            }

            get {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(budgetService.list(userId))
            }

            put {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<UpsertBudgetRequest>()
                val amountMinor = try {
                    Money.resolveAmountMinor(body.amountMinor, body.amount)
                } catch (e: IllegalArgumentException) {
                    return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to (e.message ?: "金额不合法")))
                }
                call.respond(
                    budgetService.upsert(
                        userId,
                        body.monthStart,
                        amountMinor,
                        body.categoryId,
                        body.subCategoryId,
                        body.periodType
                    )
                )
            }

            delete("/{id}") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized)

                val budgetId = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))

                val deleted = budgetService.delete(userId, budgetId)
                if (deleted) {
                    call.respond(mapOf("message" to "已删除"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "预算不存在"))
                }
            }
        }
    }
}
