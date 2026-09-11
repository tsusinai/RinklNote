package com.example.rinklnote.server.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QQ「每日推送」指令的句式分类测试：指令要命中，普通记账消息不能误伤。 */
class QQMessageProcessorTest {

    private fun isCommand(text: String): Boolean =
        QQMessageProcessor.PUSH_ON.containsMatchIn(text) ||
            QQMessageProcessor.PUSH_OFF.containsMatchIn(text) ||
            QQMessageProcessor.PUSH_STATUS.containsMatchIn(text)

    @Test
    fun `开启类指令命中`() {
        for (t in listOf("开启每日推送", "开启日报推送", "开启主动推送", "开启推送", "请帮我开启每日推送")) {
            assertTrue("应命中: $t", QQMessageProcessor.PUSH_ON.containsMatchIn(t))
        }
    }

    @Test
    fun `关闭类指令命中`() {
        for (t in listOf("关闭每日推送", "关闭日报推送", "取消主动推送", "停止推送", "停用每日推送")) {
            assertTrue("应命中: $t", QQMessageProcessor.PUSH_OFF.containsMatchIn(t))
        }
    }

    @Test
    fun `查询类指令命中`() {
        for (t in listOf("推送状态", "每日推送设置", "查询每日推送", "日报推送状态")) {
            assertTrue("应命中: $t", QQMessageProcessor.PUSH_STATUS.containsMatchIn(t))
        }
    }

    @Test
    fun `普通记账与闲聊不误伤`() {
        for (t in listOf("午餐20元", "外卖推送20元", "推送一个消息给我", "今天花了30块", "登录", "ai主动推送一下账单")) {
            assertFalse("不应命中: $t", isCommand(t))
        }
    }

    @Test
    fun `开启与关闭同时出现时两者都能被识别（处理器按关闭优先）`() {
        val t = "开启每日推送然后关闭每日推送"
        assertTrue(QQMessageProcessor.PUSH_ON.containsMatchIn(t))
        assertTrue(QQMessageProcessor.PUSH_OFF.containsMatchIn(t))
    }
}
