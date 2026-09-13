package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.Money
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class CreateAccountRequest(
    val name: String,
    val iconColor: String,
    val iconKey: String = "WALLET",
    // 新字段（分，权威值）；旧客户端只发 balance 时回退。
    val balanceMinor: Long? = null,
    // 旧字段（元），仅供回退。
    val balance: Double? = null
)

@Serializable
data class UpdateAccountRequest(
    val name: String? = null,
    val iconColor: String? = null,
    val iconKey: String? = null,
    // 新字段（分，权威值）；旧客户端只发 balance 时回退。
    val balanceMinor: Long? = null,
    // 旧字段（元），仅供回退。
    val balance: Double? = null
)

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
                val balanceMinor = if (body.balanceMinor == null && body.balance == null) 0L
                else Money.resolveBalanceMinor(body.balanceMinor, body.balance)
                try {
                    val dto = billService.createAccount(
                        userId = userId,
                        name = body.name,
                        iconColor = body.iconColor,
                        iconKey = body.iconKey,
                        balanceMinor = balanceMinor
                    )
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
                val balanceMinor = if (body.balanceMinor != null || body.balance != null) {
                    try {
                        Money.resolveBalanceMinor(body.balanceMinor, body.balance)
                    } catch (e: IllegalArgumentException) {
                        return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to (e.message ?: "余额不合法")))
                    }
                } else {
                    null
                }
                val dto = try {
                    billService.updateAccount(
                        id = id,
                        userId = userId,
                        name = body.name,
                        iconColor = body.iconColor,
                        iconKey = body.iconKey,
                        balanceMinor = balanceMinor
                    )
                } catch (e: IllegalArgumentException) {
                    return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to (e.message ?: "请求不合法")))
                }
                if (dto == null) {
                    return@put call.respond(HttpStatusCode.NotFound, mapOf("message" to "账户不存在"))
                }
                call.respond(dto)
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
