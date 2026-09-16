package com.example.rinklnote.server.routes

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 飞书 webhook Verification Token 强校验的决策表测试（安全评审修复）。
 * 校验逻辑抽成纯函数 [checkVerificationToken]，路由只负责取值与响应 —— 本测试直接锁死决策：
 *  - 双未配（Encrypt Key / Verification Token）→ 503 拒绝服务（全中文错误）；
 *  - 仅配 Token：缺 token → 403（旧实现「不带即放行」是伪造事件开户/注账的绕过口）；
 *  - 仅配 Token：不匹配 → 403，一致（顶层或 header.token）→ 放行；
 *  - 配了 Encrypt Key：以验签为准，token 不参与。
 */
class FeishuBotWebhookRoutesTest {

    private fun payload(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `配了 Encrypt Key 时以验签为准，token 不参与校验`() {
        val p = payload("""{"type":"event_callback"}""")
        assertNull(checkVerificationToken("encrypt-key-1", "tok-1", p))
        assertNull(checkVerificationToken("encrypt-key-1", null, p))
    }

    @Test
    fun `Encrypt Key 与 Token 双未配时拒绝服务`() {
        val verdict = checkVerificationToken(null, null, payload("""{"type":"event_callback"}"""))
        assertEquals(HttpStatusCode.ServiceUnavailable, verdict?.first)
        assertTrue(
            "错误消息应为全中文的安全配置提示",
            verdict?.second?.contains("机器人未完成安全配置") == true
        )
    }

    @Test
    fun `仅配 Token 时请求完全不带 token 即 403`() {
        // 伪造 im.message.receive_v1 常不带 token 字段：必须拒绝（旧实现放行，本用例锁死回归）
        val forged = payload(
            """{"header":{"event_type":"im.message.receive_v1"},"event":{"message":{"message_id":"om_x"}}}"""
        )
        val verdict = checkVerificationToken(null, "tok-1", forged)
        assertEquals(HttpStatusCode.Forbidden, verdict?.first)
    }

    @Test
    fun `仅配 Token 时 token 不匹配 403`() {
        val verdict = checkVerificationToken(null, "tok-1", payload("""{"token":"bad-token"}"""))
        assertEquals(HttpStatusCode.Forbidden, verdict?.first)
    }

    @Test
    fun `仅配 Token 时顶层或 header_token 一致均放行`() {
        // url_verification 的 token 在顶层
        assertNull(checkVerificationToken(null, "tok-1", payload("""{"token":"tok-1"}""")))
        // v2 事件的 token 在 header.token
        assertNull(checkVerificationToken(null, "tok-1", payload("""{"header":{"token":"tok-1"}}""")))
    }
}
