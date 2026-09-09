package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BudgetService
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
                call.respond(
                    budgetService.upsert(
                        userId,
                        body.monthStart,
                        body.amount,
                        body.categoryId,
                        body.subCategoryId,
                        body.periodType
                    )
                )
            }
        }
    }
}
