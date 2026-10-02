package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.asr.AsrService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get

/**
 * 阶段 1 · 语音兜底转写：QQ 平台 asr_refer_text 缺失时，拉取语音附件原始音频送
 * [AsrService]（Whisper 兼容端点，env: ASR_API_KEY / ASR_BASE_URL / ASR_MODEL）转写。
 *
 * 隐私与健壮性约定：本服务只搬运音频字节与转写文本，不打日志；任何失败
 * （未配置 / 下载失败 / 转写异常 / 转写空白）一律返回 null，绝不向上抛异常，
 * 由 ButlerIntentRouter 统一回「没听清」引导文案。
 */
class VoiceTranscribeService(
    private val asrService: AsrService?,
    /** 测试注入点（构造器末尾、带默认值）：null 时用自建 [HttpClient] 下载。 */
    private val fetch: (suspend (String) -> ByteArray?)? = null
) {

    /** 拉取语音 URL 内容并转写为中文文本；任何失败（下载/未配置/转写空）返回 null，由路由层回引导文案。 */
    suspend fun transcribeUrl(url: String): String? {
        return try {
            val asr = asrService
            // ASR 未配置（无 key）或明确不可用时直接放弃，绝不发起网络请求
            if (asr == null || !asr.available) return null
            // 注入过 fetch（测试）就只走注入实现，不回落自建 client；下载失败直接放弃
            val bytes = (if (fetch != null) fetch(url) else defaultDownload(url)) ?: return null
            // filename 取 URL 路径最后一段（截掉 query），空则给 Whisper 一个兜底文件名
            val filename = url.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: DEFAULT_FILENAME
            asr.transcribe(bytes, filename)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // 语音兜底是增强路径，任何异常都不允许打断主消息链路
            null
        }
    }

    fun shutdown() {
        // 自建下载 client 只在真正用过默认下载路径时才会被创建；此处幂等关闭，可重复调用
        closeSharedClient()
    }

    companion object {
        private const val DEFAULT_FILENAME = "voice.amr"
        private const val DOWNLOAD_TIMEOUT_MS = 30_000L

        /** 自建下载 client：懒创建、shutdown 时关闭，避免不配置 ASR 也常驻连接。 */
        @Volatile
        private var sharedClient: HttpClient? = null

        private fun obtainClient(): HttpClient =
            sharedClient ?: synchronized(this) {
                sharedClient ?: HttpClient(CIO) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS
                    }
                }.also { sharedClient = it }
            }

        /** GET 下载音频字节；任何失败（网络/非 2xx 由 body 抛出）返回 null。 */
        private suspend fun defaultDownload(url: String): ByteArray? = try {
            obtainClient().get(url).body<ByteArray>()
        } catch (_: Exception) {
            null
        }

        private fun closeSharedClient() {
            synchronized(this) {
                sharedClient?.close()
                sharedClient = null
            }
        }
    }
}
