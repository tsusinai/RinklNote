package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.asr.AsrService
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.readAllParts
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream

@Serializable
data class TranscribeResponse(val text: String = "", val available: Boolean = true)

// Hard cap on upload size (10MB) so a client cannot buffer unbounded audio into
// memory or ship a huge payload to the ASR provider.
private const val MAX_AUDIO_BYTES = 10L * 1024 * 1024

/**
 * POST /api/bills/transcribe — multipart audio upload → speech-to-text.
 * Requires JWT. When no ASR provider is configured it returns 503 with
 * { available:false } so the Android client can fall back to on-device recognition.
 */
fun Route.transcribeRoutes(asrService: AsrService?) {
    authenticate("auth-jwt") {
        route("/api/bills/transcribe") {
            post {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                if (asrService == null || !asrService.available) {
                    return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        TranscribeResponse(available = false)
                    )
                }

                var fileBytes: ByteArray? = null
                var filename = "audio.m4a"
                // readAllParts() also disposes each part after reading
                for (part in call.receiveMultipart().readAllParts()) {
                    if (part is PartData.FileItem) {
                        filename = part.originalFileName ?: filename
                        // Stream in chunks and bail as soon as the cap is exceeded
                        // so an oversized upload is rejected before it is fully
                        // buffered into memory.
                        fileBytes = part.streamProvider().use { input ->
                            val out = ByteArrayOutputStream()
                            val chunk = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(chunk, 0, chunk.size)
                                if (n < 0) break
                                total += n
                                if (total > MAX_AUDIO_BYTES) {
                                    return@post call.respond(
                                        HttpStatusCode.PayloadTooLarge,
                                        mapOf("message" to "音频文件过大，最大支持10MB")
                                    )
                                }
                                out.write(chunk, 0, n)
                            }
                            out.toByteArray()
                        }
                    }
                }

                val bytes = fileBytes
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "缺少音频文件"))

                val text = asrService.transcribe(bytes, filename)
                    ?: return@post call.respond(HttpStatusCode.BadGateway, mapOf("message" to "语音识别失败"))

                call.respond(TranscribeResponse(text = text))
            }
        }
    }
}
