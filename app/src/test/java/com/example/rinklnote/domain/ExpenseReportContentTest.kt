package com.example.rinklnote.domain

import com.example.rinklnote.data.network.dto.ExpenseReportResponse
import org.junit.Assert.*
import org.junit.Test

class ExpenseReportContentTest {
    @Test fun `报告持久化后保留HTML且兼容历史消息`() {
        val report = ExpenseReportResponse("expense-report-v1", "summary", "支出总结", "2026-10",
            "<section><h2>小结</h2><p>三餐占比最高</p></section>", "", "llm", 500L, 1L, false)
        val stored = ExpenseReportContent.encode(report)
        assertEquals(report.html, ExpenseReportContent.decode(stored)!!.html)
        assertTrue(ExpenseReportContent.decode(stored)!!.plainText.contains("三餐"))
        assertNull(ExpenseReportContent.decode("本月支出500元"))
        assertNull(ExpenseReportContent.decode("{\"format\":\"other\"}"))
    }
    @Test fun `报告问句优先识别数字月份而真实记账保持原路由`() {
        assertEquals("analysis", ExpenseReportContent.intent("分析一下8月交通支出"))
        assertEquals("summary", ExpenseReportContent.intent("帮我总结本月支出"))
        assertEquals("anomaly", ExpenseReportContent.intent("本月有支出异常吗"))
        assertNull(ExpenseReportContent.intent("午餐28元"))
        assertNull(ExpenseReportContent.intent("今天异常支出20元"))
        assertNull(ExpenseReportContent.intent("8月花了多少"))
    }
}
