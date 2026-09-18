package com.example.rinklnote.server.plugins

import com.example.rinklnote.server.services.AlertNotifier
import io.ktor.http.*
import io.ktor.serialization.ContentConvertException
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException
import org.slf4j.LoggerFactory
import java.sql.SQLIntegrityConstraintViolationException

/**
 * 全局异常处理（StatusPages）。[alertNotifier] 非空时，**未捕获异常**（catch-all Throwable
 * 分支）会经 Bot 通道向主账号发一条告警 —— 只含方法/路径与异常摘要，绝不含请求体等敏感内容
 * （隐私红线见 AlertNotifier 注释头；2026-09-18 Task 0.8）。业务语义异常（400/409 类）不打扰。
 */
fun Application.configureErrorHandling(alertNotifier: AlertNotifier? = null) {
    val logger = LoggerFactory.getLogger("ErrorHandling")
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to (cause.message ?: "参数无效")))
        }
        // Malformed request body (bad JSON / wrong types / undecodable charset) is a
        // client error, not a 500. These two Ktor exceptions have separate hierarchies.
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "请求体格式错误"))
        }
        exception<ContentConvertException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "请求体格式错误"))
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
            // 全局异常 hook：响应先行，告警异步发（fire-and-forget，失败只落日志）。
            alertNotifier?.alertAsync(call.request.httpMethod.value, call.request.path(), cause)
        }
    }
}
