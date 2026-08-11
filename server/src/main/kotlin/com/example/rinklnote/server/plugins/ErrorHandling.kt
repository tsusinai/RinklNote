package com.example.rinklnote.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException
import org.slf4j.LoggerFactory
import java.sql.SQLIntegrityConstraintViolationException

fun Application.configureErrorHandling() {
    val logger = LoggerFactory.getLogger("ErrorHandling")
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to (cause.message ?: "参数无效")))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, mapOf("message" to (cause.message ?: "操作冲突")))
        }
        exception<NumberFormatException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "数字格式错误"))
        }
        exception<ArrayIndexOutOfBoundsException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "参数格式错误"))
        }
        exception<JdbcSQLIntegrityConstraintViolationException> { call, _ ->
            call.respond(HttpStatusCode.Conflict, mapOf("message" to "数据冲突，该记录已存在"))
        }
        exception<SQLIntegrityConstraintViolationException> { call, _ ->
            call.respond(HttpStatusCode.Conflict, mapOf("message" to "数据冲突，该记录已存在"))
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled error: ${cause.javaClass.simpleName}", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("message" to "服务器内部错误"))
        }
    }
}
