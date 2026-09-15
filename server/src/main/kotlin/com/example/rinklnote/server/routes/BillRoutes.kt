package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.BillDTO
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.Money
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
    // 新字段（分，权威值）；旧客户端只发 amount 时回退。
    val amountMinor: Long? = null,
    // 旧字段（元），仅供回退。
    val amount: Double? = null,
    val billType: String,
    val categoryId: Long,
    val categoryName: String,
    val subCategoryName: String? = null,
    val accountId: Long,
    val remark: String? = null,
    val date: Long? = null,
    val baseUpdatedAt: Long? = null, // 条件 PUT：带则要求等于服务端 updatedAt，否则 409
    val sortOrder: Long? = null, // 同日内显式排序名次（App 端拖动重排）
    // 经纬度（度）：仅用户主动打点的账单才有值；null = 未打点（老客户端不发，忽略即可）。
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Serializable
data class ParseRequest(val text: String)

fun Route.billRoutes(billService: BillService, nluService: NLUService? = null) {
    // Public endpoints — reference data, no auth required
    get("/api/bills/categories") {
        val categories = billService.getCategories()
        call.respond(categories)
    }

    authenticate("auth-jwt") {
        // 账户已改为每用户：需要在鉴权下按用户返回。
        get("/api/bills/accounts") {
            val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(billService.accountsFor(userId))
        }

        route("/api/bills") {
            get("/sync") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val after = call.request.queryParameters["after"]?.toLongOrNull()
                val afterId = call.request.queryParameters["afterId"]?.toLongOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                val response = billService.syncBills(userId, after, afterId, limit)
                call.respond(response)
            }

            get("/{id}") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
                val dto = billService.getBill(id, userId)
                if (dto != null) call.respond(dto)
                else call.respond(HttpStatusCode.NotFound, mapOf("message" to "账单不存在"))
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
                try {
                    val amountMinor = Money.resolveAmountMinor(body.amountMinor, body.amount)
                    val bill = billService.createWebBill(
                        userId = userId,
                        amountMinor = amountMinor,
                        billType = body.billType,
                        categoryId = body.categoryId,
                        categoryName = body.categoryName,
                        subCategoryName = body.subCategoryName,
                        accountId = body.accountId,
                        remark = body.remark,
                        date = body.date,
                        sortOrder = body.sortOrder,
                        latitude = body.latitude,
                        longitude = body.longitude
                    )
                    call.respond(HttpStatusCode.Created, bill)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to (e.message ?: "请求不合法")))
                }
            }

            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)

                val billId = call.parameters["id"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))

                val body = call.receive<CreateBillRequest>()
                val amountMinor = try {
                    Money.resolveAmountMinor(body.amountMinor, body.amount)
                } catch (e: IllegalArgumentException) {
                    return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to (e.message ?: "金额不合法")))
                }
                require(amountMinor > 0) { "金额必须大于0" }
                require(body.billType == "EXPENSE" || body.billType == "INCOME") { "账单类型不合法" }

                // 条件 PUT：带 baseUpdatedAt 且和服务端最新 updatedAt 不符 → 409 + 当前最新 DTO，
                // 客户端据此重取 base 重放，避免离线/陈旧端覆盖新端改动。
                if (body.baseUpdatedAt != null) {
                    val current = billService.getBill(billId, userId)
                    if (current == null) {
                        return@put call.respond(HttpStatusCode.NotFound, mapOf("message" to "账单不存在"))
                    }
                    if (current.updatedAt != body.baseUpdatedAt) {
                        return@put call.respond(HttpStatusCode.Conflict, current)
                    }
                }

                val updated = transaction {
                    val row = BillsTable.selectAll()
                        .where { (BillsTable.id eq billId) and (BillsTable.userId eq userId) }
                        .singleOrNull()
                        ?: return@transaction null

                    val now = System.currentTimeMillis()
                    BillsTable.update({ BillsTable.id eq billId }) {
                        it[BillsTable.amountMinor] = amountMinor
                        it[BillsTable.amount] = Money.fromMinor(amountMinor)
                        it[BillsTable.billType] = body.billType
                        it[BillsTable.categoryId] = body.categoryId
                        it[BillsTable.categoryName] = body.categoryName
                        it[BillsTable.subCategoryName] = body.subCategoryName
                        it[BillsTable.accountId] = body.accountId
                        it[BillsTable.remark] = body.remark
                        it[BillsTable.sortOrder] = body.sortOrder
                        // PUT 是全量替换语义（与 remark 等字段一致）：传 null 即清除打点。
                        it[BillsTable.latitude] = body.latitude
                        it[BillsTable.longitude] = body.longitude
                        it[BillsTable.updatedAt] = now
                    }

                    BillDTO(
                        id = billId, amountMinor = amountMinor, amount = Money.fromMinor(amountMinor), billType = body.billType,
                        categoryId = body.categoryId, categoryName = body.categoryName,
                        subCategoryName = body.subCategoryName, accountId = body.accountId,
                        remark = body.remark, date = row[BillsTable.date],
                        source = row[BillsTable.billSource], createdAt = row[BillsTable.createdAt],
                        updatedAt = now, sortOrder = body.sortOrder,
                        latitude = body.latitude, longitude = body.longitude
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
