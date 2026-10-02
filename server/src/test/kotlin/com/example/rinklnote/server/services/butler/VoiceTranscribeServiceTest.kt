package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.asr.AsrService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 测试用假 ASR：记录调用与入参，返回 canned 文本。 */
private class FakeAsr(
    override val available: Boolean = true,
    private val reply: String? = "午餐二十元"
) : AsrService {
    var calls: Int = 0
        private set
    var lastBytes: ByteArray? = null
        private set
    var lastFilename: String? = null
        private set

    override suspend fun transcribe(fileBytes: ByteArray, filename: String): String? {
        calls++
        lastBytes = fileBytes
        lastFilename = filename
        return reply
    }
}

/**
 * 阶段 1 · 语音 Whisper 兜底转写单测：
 * 未配置不联网、下载失败吞异常、filename 取 URL 末段、空白转写回 null、shutdown 幂等。
 * 全程用注入 fetch，不发起真实网络请求。
 */
class VoiceTranscribeServiceTest {

    private fun payloadOf(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    /** 必不执行的 fetch：一旦被调用就抛 AssertionError（Error 不被服务吞掉，测试直接红）。 */
    private val mustNotFetch: suspend (String) -> ByteArray? =
        { throw AssertionError("该场景不应发起语音下载") }

    @Test
    fun `asr 未配置或不可用时直接返回 null 且不下载`() = runBlocking {
        val url = "https://qq.example.com/mvo/abc.amr?token=x"
        assertNull(VoiceTranscribeService(null, mustNotFetch).transcribeUrl(url))

        val unavailable = FakeAsr(available = false)
        assertNull(VoiceTranscribeService(unavailable, mustNotFetch).transcribeUrl(url))
        assertEquals(0, unavailable.calls)
    }

    @Test
    fun `下载抛异常或返回空时返回 null 且不触发转写`() = runBlocking {
        val asr = FakeAsr()
        val failing: suspend (String) -> ByteArray? = { throw RuntimeException("网络开小差") }
        assertNull(VoiceTranscribeService(asr, failing).transcribeUrl("https://qq.example.com/v.amr"))
        assertEquals(0, asr.calls)

        val empty: suspend (String) -> ByteArray? = { null }
        assertNull(VoiceTranscribeService(asr, empty).transcribeUrl("https://qq.example.com/v.amr"))
        assertEquals(0, asr.calls)
    }

    @Test
    fun `转写成功返回文本且 filename 取 URL 路径末段并截掉 query`() = runBlocking {
        val asr = FakeAsr()
        val payload = payloadOf(1, 2, 3)
        val fetch: suspend (String) -> ByteArray? = { payload }
        val out = VoiceTranscribeService(asr, fetch)
            .transcribeUrl("https://qq.example.com/mvo/abc123.amr?token=x&sig=y")
        assertEquals("午餐二十元", out)
        assertEquals(1, asr.calls)
        assertEquals("abc123.amr", asr.lastFilename)
        assertEquals(3, asr.lastBytes?.size)
    }

    @Test
    fun `URL 末段为空时使用默认文件名 voice amr`() = runBlocking {
        val asr = FakeAsr()
        val payload = payloadOf(9)
        VoiceTranscribeService(asr, { payload }).transcribeUrl("https://qq.example.com/")
        assertEquals("voice.amr", asr.lastFilename)
    }

    @Test
    fun `转写结果为空白时返回 null`() = runBlocking {
        val asr = FakeAsr(reply = "   ")
        val payload = payloadOf(1)
        val out = VoiceTranscribeService(asr, { payload }).transcribeUrl("https://qq.example.com/v.amr")
        assertNull(out)
        assertEquals(1, asr.calls)
    }

    @Test
    fun `shutdown 可重复调用不抛异常`() {
        val service = VoiceTranscribeService(null)
        service.shutdown()
        service.shutdown()
    }
}
