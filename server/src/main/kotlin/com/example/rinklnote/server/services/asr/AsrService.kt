package com.example.rinklnote.server.services.asr

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Speech-to-text provider. Returns null / available=false when not usable. */
interface AsrService {
    val available: Boolean
    suspend fun transcribe(fileBytes: ByteArray, filename: String): String?
}

data class AsrConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.openai.com",
    val model: String = "whisper-1",
    val timeoutMs: Long = 30_000L
)

/**
 * Whisper-compatible ASR via the OpenAI audio transcriptions endpoint.
 * When no API key is configured the service is unavailable (Android falls back to on-device).
 */
class WhisperAsrService(private val config: AsrConfig?) : AsrService {

    override val available: Boolean
        get() = config != null && config.apiKey.isNotBlank()

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = config?.timeoutMs ?: 30_000L
        }
    }

    @Serializable
    private data class WhisperResponse(val text: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun transcribe(fileBytes: ByteArray, filename: String): String? {
        val cfg = config
        if (cfg == null || cfg.apiKey.isBlank()) return null
        return try {
            val responseText = client.post("${cfg.baseUrl}/v1/audio/transcriptions") {
                header("Authorization", "Bearer ${cfg.apiKey}")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("model", cfg.model)
                            append("language", "zh")
                            append("file", fileBytes, Headers.build {
                                append(HttpHeaders.ContentType, "audio/mp4")
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                            })
                        }
                    )
                )
            }.bodyAsText()
            json.decodeFromString<WhisperResponse>(responseText)
                .text
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun shutdown() {
        client.close()
    }
}
