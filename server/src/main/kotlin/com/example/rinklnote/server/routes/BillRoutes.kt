package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillDTO
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.nlu.NLUService
import com.example.rinklnote.server.tables.BillsTable
import io.ktor.http.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
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

@Serializable
data class ParseRequest(val text: String)

fun Route.billRoutes(billService: BillService, nluService: NLUService? = null) {
    // Public endpoints — reference data, no auth required
    get("/api/bills/categories") {
        val categories = billService.getCategories()
        call.respond(categories)
    }

    get("/api/bills/accounts") {
        val accounts = billService.getAccounts()
        call.respond(accounts)
    }

    authenticate("auth-jwt") {
        route("/api/bills") {
            get("/sync") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val after = call.request.queryParameters["after"]?.toLongOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                val response = billService.syncBills(userId, after, limit)
                call.respond(response)
            }

            post("/parse") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val nlu = nluService
                    ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("message" to "NLP服务未启用"))
                val body = call.receive<ParseRequest>()
                val result = nlu.parse(body.text, userId)
                if (result.amount == null || result.amount <= 0) {
                    return@post call.respond(mapOf(
                        "amount" to "", "categoryName" to "", "remark" to body.text, "subCategoryName" to "",
                        "message" to "无法识别金额，请说如'午餐20元'"
                    ))
                }
                call.respond(mapOf(
                    "amount" to result.amount.toString(),
                    "categoryName" to (result.categoryName ?: "三餐"),
                    "remark" to (result.remark ?: body.text),
                    "subCategoryName" to ""
                ))
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

            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)

                val billId = call.parameters["id"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest)

                val body = call.receive<CreateBillRequest>()

                val updated = transaction {
                    val row = BillsTable.selectAll()
                        .where { (BillsTable.id eq billId) and (BillsTable.userId eq userId) }
                        .singleOrNull()
                        ?: return@transaction null

                    val now = System.currentTimeMillis()
                    BillsTable.update({ BillsTable.id eq billId }) {
                        it[BillsTable.amount] = body.amount
                        it[BillsTable.billType] = body.billType
                        it[BillsTable.categoryId] = body.categoryId
                        it[BillsTable.categoryName] = body.categoryName
                        it[BillsTable.subCategoryName] = body.subCategoryName
                        it[BillsTable.accountId] = body.accountId
                        it[BillsTable.remark] = body.remark
                        it[BillsTable.updatedAt] = now
                    }

                    BillDTO(
                        id = billId, amount = body.amount, billType = body.billType,
                        categoryId = body.categoryId, categoryName = body.categoryName,
                        subCategoryName = body.subCategoryName, accountId = body.accountId,
                        remark = body.remark, date = row[BillsTable.date],
                        source = row[BillsTable.billSource], createdAt = row[BillsTable.createdAt],
                        updatedAt = now
                    )
                }

                if (updated != null) {
                    call.respond(updated)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "账单不存在"))
                }
            }

            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized)

                val billId = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))

                val deleted = billService.deleteBill(billId, userId)
                if (deleted) {
                    call.respond(mapOf("message" to "已删除"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "账单不存在"))
                }
            }
        }
    }
}
