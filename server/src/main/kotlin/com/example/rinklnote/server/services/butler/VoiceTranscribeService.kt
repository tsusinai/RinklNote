package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.asr.AsrService

/**
 * 阶段 1 · 语音兜底转写：QQ 平台 asr_refer_text 缺失时，拉取语音附件原始音频送
 * [AsrService]（Whisper 兼容端点，env: ASR_API_KEY / ASR_BASE_URL / ASR_MODEL）转写。
 *
 * TODO(阶段1-子代理A)：实现 download → transcribe 链路与单测（见派工契约）。
 */
class VoiceTranscribeService(private val asrService: AsrService?) {

    /** 拉取语音 URL 内容并转写为中文文本；任何失败（下载/未配置/转写空）返回 null，由路由层回引导文案。 */
    suspend fun transcribeUrl(url: String): String? {
        // TODO(阶段1)：下载音频字节 → asrService.transcribe(bytes, filename)
        return null
    }

    fun shutdown() {
        // TODO(阶段1)：若持有 HttpClient，在此关闭（Application.kt 已接线调用）
    }
}
