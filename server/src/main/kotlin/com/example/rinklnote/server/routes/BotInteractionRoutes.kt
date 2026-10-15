package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.bot.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** 独立的公众号编辑入口只接受完整、限时的 Web 票据，不签发全账号 JWT。 */
fun Route.botInteractionRoutes(service: BotInteractionService) {
    route("/api/bot/interaction") {
        suspend fun ApplicationCall.runAction(edit: Boolean) {
            response.header(HttpHeaders.CacheControl, "no-store")
            response.header("Referrer-Policy", "no-referrer")
            val token = request.header("X-Bot-Action")
                ?: return respond(HttpStatusCode.Unauthorized, mapOf("message" to "请从小盘重新打开入口"))
            try {
                if (edit) {
                    if (!request.contentType().match(ContentType.Application.Json)) {
                        return respond(HttpStatusCode.UnsupportedMediaType, mapOf("message" to "请求格式错误"))
                    }
                    respond(service.webEdit(token, receive<InteractionEdit>()))
                } else respond(service.webView(token))
            } catch (e: BotActionException) {
                respond(HttpStatusCode.fromValue(e.status), mapOf("message" to e.message))
            }
        }
        get { call.runAction(false) }
        post { call.runAction(true) }
    }
}
