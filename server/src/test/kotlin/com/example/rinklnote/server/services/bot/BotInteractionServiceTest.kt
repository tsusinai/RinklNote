package com.example.rinklnote.server.services.bot

import com.example.rinklnote.server.services.*
import com.example.rinklnote.server.tables.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** 使用真实 H2 与账单服务，覆盖跨账号、旧卡、重放、并发与平台回调。 */
class BotInteractionServiceTest {
    private val bills = BillService()
    private val budgets = BudgetService()
    private val users = UserService("test-secret", "test", "rinklnote")
    private var now = System.currentTimeMillis()
    private lateinit var service: BotInteractionService
    private var qqUser = 0L
    private var feishuUser = 0L
    private var mpUser = 0L

    @Before fun setup() {
        TestDatabase.connect("botinteraction")
        transaction { SchemaUtils.create(UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable,
            BillsTable, BudgetsTable, BotActionTable) }
        bills.seedIfNeeded()
        qqUser = users.createByQqOpenid("qq-owner").id
        feishuUser = users.createByFeishuOpenId("ou-owner").id
        mpUser = users.createByWechatOpenid("wx-owner").id
        now = System.currentTimeMillis()
        service = BotInteractionService(bills, budgets, users, "https://example.test", clock = { now })
    }

    private fun book(user: Long = qqUser) = bills.createBill(user, 2000L, "三餐", "私人备注")
    private fun token(b: BillDTO, label: String, user: Long = qqUser, channel: String = "QQ", actor: String = "qq-owner") =
        service.billCard(user, channel, actor, b, "已记录").buttons.first { it.label == label }.token
    private fun fails(status: Int, block: () -> Unit) {
        try { block(); fail("应拒绝该操作") } catch (e: BotActionException) { assertEquals(status, e.status) }
    }
    private fun webToken(b: BillDTO? = null) = service.webLink(mpUser, "wx-owner", b)!!.substringAfter("#token=")

    @Test fun `撤销绑定这一笔不会误删更新的最近账单`() {
        val first = book(); val undo = token(first, "撤销这一笔"); val second = book()
        service.action(undo, "QQ", "qq-owner")
        assertTrue(bills.getBill(first.id, qqUser)!!.deleted)
        assertFalse(bills.getBill(second.id, qqUser)!!.deleted)
        fails(409) { service.action(undo, "QQ", "qq-owner") }
    }

    @Test fun `跨用户与跨通道不能使用同一票据`() {
        users.createByQqOpenid("qq-other")
        val b = book(); val undo = token(b, "撤销这一笔")
        fails(403) { service.action(undo, "QQ", "qq-other") }
        fails(403) { service.action(undo, "FEISHU", "qq-owner") }
        assertFalse(bills.getBill(b.id, qqUser)!!.deleted)
    }

    @Test fun `过期票据不能读写`() {
        val b = book(); val undo = token(b, "撤销这一笔")
        now += 30 * 60_000L
        fails(410) { service.action(undo, "QQ", "qq-owner") }
        assertFalse(bills.getBill(b.id, qqUser)!!.deleted)
    }

    @Test fun `旧版本拒绝修正且查看按钮能获取最新卡片`() {
        val b = book(); val card = service.billCard(qqUser, "QQ", "qq-owner", b, "已记录")
        bills.updateBillAmount(b.id, qqUser, 3500L)
        // 显式推进版本，固定覆盖外部编辑与旧卡同时存在的情形。
        transaction { BillsTable.update({ BillsTable.id eq b.id }) { it[updatedAt] = (b.updatedAt ?: b.createdAt) + 1 } }
        now += 10
        val view = service.action(card.buttons.first { it.label == "查看账单" }.token, "QQ", "qq-owner")
        assertTrue(view.text.contains("35.00"))
        assertNotNull(view.qqCard)
        fails(409) { service.action(card.buttons.first { it.label == "撤销这一笔" }.token, "QQ", "qq-owner") }
    }

    @Test fun `网页票据只编辑特定账单并保留备注账户日期`() {
        val b = book(mpUser); val token = webToken(b)
        val category = bills.getCategories().first { it.name == "交通" }
        val result = service.webEdit(token, InteractionEdit("save", 5797L, category.id))
        assertTrue(result.consumed); assertEquals(5797L, result.bill!!.amountMinor)
        val updated = bills.getBill(b.id, mpUser)!!
        assertEquals(b.remark, updated.remark); assertEquals(b.date, updated.date)
        assertEquals(b.accountId, updated.accountId); assertEquals(b.sortOrder, updated.sortOrder)
        assertEquals(b.latitude, updated.latitude); assertEquals(b.longitude, updated.longitude)
        assertEquals("交通", updated.categoryName)
        assertTrue(service.webView(token).consumed)
        fails(409) { service.webEdit(token, InteractionEdit("undo")) }
        fails(403) { service.webEdit(webToken(updated), InteractionEdit("reminders", enabled = true)) }
    }

    @Test fun `网页不接受卡片票据或短指令码`() {
        val b = book(); val t = token(b, "改金额")
        fails(403) { service.webView(t) }
        fails(410) { service.webView(t.take(12)) }
    }

    @Test fun `非法金额与跨收支分类不会消耗网页入口`() {
        val b = book(mpUser); val t = webToken(b)
        fails(400) { service.webEdit(t, InteractionEdit("save", 0L, b.categoryId)) }
        val salary = bills.getCategories().first { it.name == "工资" }
        fails(400) { service.webEdit(t, InteractionEdit("save", 100L, salary.id)) }
        assertFalse(service.webView(t).consumed)
        assertEquals(2000L, bills.getBill(b.id, mpUser)!!.amountMinor)
    }

    @Test fun `QQ点击改金额后文本补充仍定位旧账单`() {
        val b = book(); service.action(token(b, "改金额"), "QQ", "qq-owner")
        val newer = book()
        val result = service.textAction("57.97", "QQ", "qq-owner")!!
        assertTrue(result.text.contains("57.97"))
        assertEquals(5797L, bills.getBill(b.id, qqUser)!!.amountMinor)
        assertEquals(2000L, bills.getBill(newer.id, qqUser)!!.amountMinor)
        assertNull(service.textAction("57.97", "QQ", "qq-owner"))
    }

    @Test fun `QQ分类选择与取消金额流程不生成新账单`() {
        val b = book()
        val menu = service.action(token(b, "换分类"), "QQ", "qq-owner").qqCard!!
        val choose = menu.buttons.first { it.label == "交通" }
        service.textAction("交通 ${choose.token.take(12)}", "QQ", "qq-owner")
        val current = bills.getBill(b.id, qqUser)!!
        assertEquals("交通", current.categoryName)
        service.action(token(current, "改金额"), "QQ", "qq-owner")
        assertNotNull(service.textAction("取消", "QQ", "qq-owner"))
        assertNull(service.textAction("30.00", "QQ", "qq-owner"))
        assertEquals(1, bills.allBills(qqUser).count { !it.deleted })
    }

    @Test fun `同一撤销并发点击最多一次成功`() {
        val b = book(); val t = token(b, "撤销这一笔")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(List(2) { Callable {
                runCatching { service.action(t, "QQ", "qq-owner") }.isSuccess
            } }).map { it.get() }
            assertEquals(1, results.count { it }); assertTrue(bills.getBill(b.id, qqUser)!!.deleted)
        } finally { executor.shutdownNow() }
    }

    @Test fun `票据重建服务后仍有效`() {
        val b = book(); val t = token(b, "撤销这一笔")
        val restarted = BotInteractionService(bills, budgets, users, "https://example.test", clock = { now })
        restarted.action(t, "QQ", "qq-owner")
        assertTrue(bills.getBill(b.id, qqUser)!!.deleted)
    }

    @Test fun `预算只取总额预算并支持提醒开关与时间`() {
        val b = book(mpUser)
        val start = Instant.ofEpochMilli(now).atZone(TimeUtil.BOOKKEEPING_ZONE).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(TimeUtil.BOOKKEEPING_ZONE).toInstant().toEpochMilli()
        budgets.upsert(mpUser, start, 10000L)
        budgets.upsert(mpUser, start, 1000L, b.categoryId)
        val t = webToken(); val view = service.webView(t)
        assertEquals(10000L, view.budget!!.budgetMinor); assertEquals(2000L, view.budget.spentMinor)
        assertEquals(8000L, view.budget.remainingMinor)
        val saved = service.webEdit(t, InteractionEdit("reminders", enabled = true, hour = 20, minute = 45))
        assertTrue(saved.budget!!.enabled); assertEquals(20, saved.budget.hour); assertEquals(45, saved.budget.minute)
        assertTrue(saved.consumed)
        assertFalse(saved.text.contains("私人备注")); assertFalse(saved.text.contains("挑战"))
    }

    @Test fun `飞书原生表单回调修改具体账单且可刷新成功卡`() {
        val b = book(feishuUser)
        val prepared = service.action(token(b, "改金额", feishuUser, "FEISHU", "ou-owner"), "FEISHU", "ou-owner")
        val form = prepared.card!!["body"]!!.jsonObject["elements"]!!.jsonArray.last().jsonObject
        assertEquals("form", form["tag"]!!.jsonPrimitive.content)
        val submit = form["elements"]!!.jsonArray.last().jsonObject
        val value = submit["behaviors"]!!.jsonArray.first().jsonObject["value"]!!
        val callback = buildJsonObject {
            putJsonObject("operator") { put("open_id", "ou-owner") }
            putJsonObject("action") {
                put("value", value); putJsonObject("form_value") { put("amount", "57.97"); put("category", b.categoryId.toString()) }
            }
        }
        val result = BotPlatformCallbacks.feishu(callback, service)
        assertEquals("success", result["toast"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(5797L, bills.getBill(b.id, feishuUser)!!.amountMinor)
        assertEquals("error", BotPlatformCallbacks.feishu(callback, service)["toast"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `QQ键盘为指定用户且不包含备注或账单登录令牌`() {
        val b = book(); val card = service.billCard(qqUser, "QQ", "qq-owner", b, "已记录")
        val payload = BotCardRenderer.qq(card, "msg-id")
        assertEquals(2, payload["msg_type"]!!.jsonPrimitive.int)
        assertEquals("msg-id", payload["msg_id"]!!.jsonPrimitive.content)
        val button = payload["keyboard"]!!.jsonObject["content"]!!.jsonObject["rows"]!!.jsonArray.first()
            .jsonObject["buttons"]!!.jsonArray.first().jsonObject
        assertEquals(0, button["action"]!!.jsonObject["permission"]!!.jsonObject["type"]!!.jsonPrimitive.int)
        assertFalse(payload.toString().contains("私人备注"))
    }

    @Test fun `金额严格两位小数拒绝非数与溢出`() {
        assertEquals(5797L, Money.parseMinor("57.97"))
        listOf("0", "-1", "1.001", "NaN", "Infinity", "1e3", "999999999999999999999999").forEach { assertNull(Money.parseMinor(it)) }
    }
}
