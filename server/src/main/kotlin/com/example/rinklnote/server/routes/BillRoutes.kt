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
data class CreateBillRequest(
    val amount: Double,
    val billType: String,
    val categoryId: Long,
    val categoryName: String,
    val subCategoryName: String? = null,
    val accountId: Long,
    val remark: String? = null,
    val date: Long? = null
)

fun Route.billRoutes(billService: BillService) {
    authenticate("auth-jwt") {
        route("/api/bills") {
            get("/sync") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val after = call.request.queryParameters["after"]?.toLongOrNull()
                val response = billService.syncBills(userId, after)
                call.respond(response)
            }

            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<CreateBillRequest>()
                val bill = billService.createWebBill(
                    userId = userId,
                    amount = body.amount,
                    billType = body.billType,
                    categoryId = body.categoryId,
                    categoryName = body.categoryName,
                    subCategoryName = body.subCategoryName,
                    accountId = body.accountId,
                    remark = body.remark,
                    date = body.date
                )
                call.respond(HttpStatusCode.Created, bill)
            }

            get("/categories") {
                val categories = billService.getCategories()
                call.respond(categories)
            }

            get("/accounts") {
                val accounts = billService.getAccounts()
                call.respond(accounts)
            }
        }
    }
}
