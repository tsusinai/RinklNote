package com.example.rinklnote.server.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQBotServiceTest {

    @Test
    fun `bind code roundtrip maps code back to openid`() {
        val svc = QQBotService()
        val code = svc.generateBindCode("openid-roundtrip")
        assertEquals("openid-roundtrip", svc.consumeBindCode(code))
    }

    @Test
    fun `consumed bind code cannot be reused`() {
        val svc = QQBotService()
        val code = svc.generateBindCode("openid-reuse")
        svc.consumeBindCode(code)
        assertNull(svc.consumeBindCode(code))
    }
}
