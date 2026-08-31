package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class CreateAccountRequest(val name: String, val iconColor: String, val balance: Double = 0.0)

@Serializable
data class UpdateAccountRequest(val name: String? = null, val iconColor: String? = null, val balance: Double? = null)

fun Route.accountRoutes(billService: BillService) {
    authenticate("auth-jwt") {
        route("/api/accounts") {
            get {
                val userId = call.userId()
                call.respond(billService.accountsFor(userId))
            }
            post {
                val userId = call.userId()
                val body = call.receive<CreateAccountRequest>()
                try {
                    val dto = billService.createAccount(userId, body.name, body.iconColor, body.balance)
                    call.respond(HttpStatusCode.Created, dto)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to (e.message ?: "请求不合法")))
                }
            }
            put("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
                val body = call.receive<UpdateAccountRequest>()
                if (body.balance != null) {
                    if (body.balance < 0 || !body.balance.isFinite()) {
                        return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "余额不能为负"))
                    }
                }
                val dto = billService.renameAccount(id, userId, body.name ?: "", body.iconColor ?: "")
                if (dto == null) {
                    return@put call.respond(HttpStatusCode.NotFound, mapOf("message" to "账户不存在"))
                }
                if (body.balance != null) {
                    billService.updateAccountBalance(id, body.balance, userId)
                }
                val fresh = billService.accountsFor(userId).firstOrNull { it.id == id } ?: dto
                call.respond(fresh)
            }
            delete("/{id}") {
                val userId = call.userId()
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
                if (billService.deleteAccount(id, userId)) {
                    call.respond(mapOf("message" to "已删除"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "账户不存在"))
                }
            }
        }
    }
}

private fun ApplicationCall.userId(): Long =
    principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
        ?: error("Unauthorized")
