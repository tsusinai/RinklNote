package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.CategoryDTO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.slf4j.helpers.NOPLogger

/**
 * 阶段 1 · 识图入账单测：parseReceiptJson 四种形态 + 分类 hint 解析 +
 * 未配置视觉模型 / 缺图片时的兜底文案（不联网、不依赖数据库）。
 */
class ReceiptOcrServiceTest {

    private val service = ReceiptOcrService(BillService(), NOPLogger.NOP_LOGGER)

    private fun incoming(imageUrl: String?): ButlerIncoming = ButlerIncoming(
        userId = 1L,
        openid = "openid-x",
        chatType = ButlerChatType.PRIVATE,
        groupOpenid = null,
        msgId = "msg-1",
        text = null,
        voiceUrl = null,
        imageUrl = imageUrl
    )

    // ── parseReceiptJson ───────────────────────────────────────

    @Test
    fun `解析正常票据 JSON 各字段齐全`() {
        val info = service.parseReceiptJson(
            """{"merchant": "瑞幸咖啡", "amount": 23.5, "date": "2026-10-01", "categoryHint": "三餐"}"""
        )
        assertNotNull(info)
        assertEquals("瑞幸咖啡", info!!.merchant)
        assertEquals(23.5, info.amountYuan!!, 1e-9)
        assertEquals("2026-10-01", info.dateText)
        assertEquals("三餐", info.categoryHint)
    }

    @Test
    fun `解析剥掉 json 代码围栏与前后噪声文本`() {
        val json = """{"merchant": "肯德基", "amount": 45.8, "date": null, "categoryHint": null}"""
        // 带围栏
        val fenced = service.parseReceiptJson("```json\n$json\n```")
        assertEquals("肯德基", fenced!!.merchant)
        assertEquals(45.8, fenced.amountYuan!!, 1e-9)
        assertNull(fenced.dateText)
        // 围栏外还有闲话（首 { 到末 } 截取）
        val noisy = service.parseReceiptJson("好的，识别结果如下：$json 以上就是全部内容。")
        assertEquals("肯德基", noisy!!.merchant)
        // 无 json 围栏标记的裸 ```
        val bareFence = service.parseReceiptJson("```\n$json\n```")
        assertEquals(45.8, bareFence!!.amountYuan!!, 1e-9)
    }

    @Test
    fun `字段缺失时对应字段为 null`() {
        val info = service.parseReceiptJson("""{"amount": 12}""")
        assertNotNull(info)
        assertNull(info!!.merchant)
        assertNull(info.dateText)
        assertNull(info.categoryHint)
        assertEquals(12.0, info.amountYuan!!, 1e-9)
    }

    @Test
    fun `金额非法时 amountYuan 视为 null`() {
        // 非数字字符串
        assertNull(service.parseReceiptJson("""{"amount": "abc"}""")!!.amountYuan)
        // 零与负数
        assertNull(service.parseReceiptJson("""{"amount": 0}""")!!.amountYuan)
        assertNull(service.parseReceiptJson("""{"amount": -5.5}""")!!.amountYuan)
        // 对象/数组等非标量
        assertNull(service.parseReceiptJson("""{"amount": {"yuan": 3}}""")!!.amountYuan)
        // 数字字符串仍可解析
        assertEquals(18.9, service.parseReceiptJson("""{"amount": "18.9"}""")!!.amountYuan!!, 1e-9)
    }

    @Test
    fun `完全不是 JSON 时整体返回 null`() {
        assertNull(service.parseReceiptJson("这张图我看到了但没有金额"))
        assertNull(service.parseReceiptJson(""))
        assertNull(service.parseReceiptJson("{没有闭合"))
    }

    // ── 分类 hint 解析 ─────────────────────────────────────────

    private val categories = listOf(
        CategoryDTO(1, "三餐", "meals", "EXPENSE", emptyList()),
        CategoryDTO(2, "日用", "daily", "EXPENSE", emptyList()),
        CategoryDTO(3, "交通", "transport", "EXPENSE", emptyList()),
        CategoryDTO(4, "美妆个护", "beauty", "EXPENSE", emptyList())
    )

    @Test
    fun `hint 与分类名精确相等直接命中`() {
        assertEquals("三餐", service.resolveCategoryName("三餐", categories))
        assertEquals("交通", service.resolveCategoryName(" 交通 ", categories))
    }

    @Test
    fun `hint 包含或被包含且唯一时命中`() {
        // hint 包含分类名
        assertEquals("交通", service.resolveCategoryName("交通出行", categories))
        // 分类名包含 hint
        assertEquals("美妆个护", service.resolveCategoryName("美妆", categories))
    }

    @Test
    fun `多义包含或无命中时返回 null 落默认分类`() {
        // 「日用娱乐」同时包含「日用」与「娱乐」→ 多义
        val ambiguous = listOf(
            CategoryDTO(1, "三餐", "meals", "EXPENSE", emptyList()),
            CategoryDTO(2, "日用", "daily", "EXPENSE", emptyList()),
            CategoryDTO(3, "娱乐", "entertainment", "EXPENSE", emptyList())
        )
        assertNull(service.resolveCategoryName("日用娱乐", ambiguous))
        // 无命中
        assertNull(service.resolveCategoryName("宇宙飞船", categories))
        // 空值
        assertNull(service.resolveCategoryName(null, categories))
        assertNull(service.resolveCategoryName("  ", categories))
    }

    // ── tryBook 兜底分支（不联网） ─────────────────────────────

    @Test
    fun `视觉模型未配置时返回引导文案`() = runBlocking {
        // 若本机配了 VISION_API_KEY 则跳过本用例（避免触发真实请求）
        assumeTrue(System.getenv("VISION_API_KEY").isNullOrBlank())
        val reply = service.tryBook(incoming("https://img.example.com/receipt.jpg"))
        assertNotNull(reply)
        assertTrue(reply!!.contains("视觉模型未配置"))
    }

    @Test
    fun `缺少图片附件时给失败引导而非 null`() = runBlocking {
        val reply = service.tryBook(incoming(null))
        assertNotNull(reply)
        assertTrue(reply!!.contains("没看懂"))
    }
}
