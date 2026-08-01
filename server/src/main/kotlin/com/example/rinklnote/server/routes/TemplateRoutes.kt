package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.TemplateDTO
import com.example.rinklnote.server.services.TemplateService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ReorderRequest(val ids: List<Long>)

fun Route.templateRoutes(templateService: TemplateService) {
    authenticate("auth-jwt") {
        route("/api/templates") {
            get {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(templateService.list(userId))
            }

            post {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<TemplateDTO>()
                val created = templateService.create(userId, body)
                call.respond(HttpStatusCode.Created, created)
            }

            put("/{id}") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val tid = call.parameters["id"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
                val body = call.receive<TemplateDTO>()
                if (templateService.update(userId, tid, body))
                    call.respond(mapOf("message" to "已更新"))
                else call.respond(HttpStatusCode.NotFound, mapOf("message" to "模板不存在"))
            }

            delete("/{id}") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val tid = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("message" to "无效ID"))
                if (templateService.delete(userId, tid))
                    call.respond(mapOf("message" to "已删除"))
                else call.respond(HttpStatusCode.NotFound, mapOf("message" to "模板不存在"))
            }

            post("/reorder") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<ReorderRequest>()
                templateService.reorder(userId, body.ids)
                call.respond(mapOf("message" to "已排序"))
            }
        }
    }
}
