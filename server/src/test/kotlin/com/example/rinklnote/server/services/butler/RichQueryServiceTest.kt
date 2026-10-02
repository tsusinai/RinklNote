package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.services.TimeUtil
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 阶段 1 · 富查账单测：上周/本周分类查账、近 30 天最花钱的一天（含并列取最近）、
 * 日均可用（有预算 / 无预算 / 超支）、未命中文本返回 null。
 * 用 H2 内存库 + 实际 createBill 落数据，日期边界与实现同源（业务时区、ISO 周）。
 */
class RichQueryServiceTest {

    private val billService = BillService()
    private val budgetService = BudgetService()
    private lateinit var richQuery: RichQueryService

    @Before
    fun setup() {
        TestDatabase.connect("richquery")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable,
                AccountsTable, BillsTable, BudgetsTable
            )
            BillsTable.deleteAll()
            BudgetsTable.deleteAll()
            SubCategoriesTable.deleteAll()
            AccountsTable.deleteAll()
            CategoriesTable.deleteAll()
            UsersTable.deleteAll()
        }
        billService.seedIfNeeded()
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = 1L
                it[UsersTable.phone] = "13800000041"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
        richQuery = RichQueryService(billService, budgetService)
    }

    // ── 工具 ───────────────────────────────────────────────────

    private fun dayStart(date: LocalDate): Long =
        date.atStartOfDay(TimeUtil.BOOKKEEPING_ZONE).toInstant().toEpochMilli()

    /** 在指定日期（业务时区当日 0 点）落一笔支出账单。 */
    private fun billAt(date: LocalDate, amountMinor: Long, category: String = "三餐") =
        billService.createBill(
            userId = 1L, amountMinor = amountMinor,
            categoryName = category, remark = null, date = dayStart(date)
        )

    /** 与实现同源的 ISO 周边界：返回 (上周一, 本周一)。 */
    private fun weekBoundaries(): Pair<LocalDate, LocalDate> {
        val thisMonday = LocalDate.now(TimeUtil.BOOKKEEPING_ZONE)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return thisMonday.minusWeeks(1) to thisMonday
    }

    private fun today(): LocalDate = LocalDate.now(TimeUtil.BOOKKEEPING_ZONE)

    private suspend fun answer(text: String): String? = richQuery.tryAnswer(text, 1L)

    // ── 意图 1：上周 / 本周 × 分类 ─────────────────────────────

    @Test
    fun `上周分类查账命中且不含本周账单`() = runBlocking {
        val (lastMonday, thisMonday) = weekBoundaries()
        billAt(lastMonday.plusDays(1), 2000L, "服饰")
        billAt(lastMonday.plusDays(2), 1500L, "服饰")
        // 本周一的账单不算上周（区间左闭右开）
        billAt(thisMonday, 9999L, "服饰")

        val reply = answer("上周买衣服花了多少")
        assertNotNull(reply)
        assertTrue(reply!!.contains("上周"))
        assertTrue(reply.contains("服饰"))
        assertTrue(reply.contains("¥35.00"))
        assertTrue(reply.contains("（2笔）"))
        assertFalse(reply.contains("99.99"))
    }

    @Test
    fun `本周查账只算本周的账单`() = runBlocking {
        val (lastMonday, _) = weekBoundaries()
        billAt(lastMonday, 9999L, "三餐") // 上周，不应计入
        billAt(today(), 1500L, "三餐")

        val reply = answer("这周吃饭花了多少")
        assertNotNull(reply)
        assertTrue(reply!!.contains("这周"))
        assertTrue(reply.contains("三餐"))
        assertTrue(reply.contains("¥15.00"))
        assertTrue(reply.contains("（1笔）"))
        assertFalse(reply.contains("99.99"))
    }

    @Test
    fun `上周该分类零记录时给引导文案`() = runBlocking {
        billAt(today(), 1500L, "三餐") // 只有本周的账
        val reply = answer("上周吃饭花了多少")
        assertNotNull(reply)
        assertTrue(reply!!.contains("上周还没记过"))
        assertTrue(reply.contains("三餐"))
    }

    @Test
    fun `规则词表未命中时走分类名包含兜底`() = runBlocking {
        val (lastMonday, _) = weekBoundaries()
        // 「服饰」是种子分类但不在规则词表（词表只有 衣服/服装/鞋）→ 词表 miss，
        // 回落「分类名出现在文本里」的唯一包含命中；账单按服饰分类落库。
        billAt(lastMonday.plusDays(1), 4200L, "服饰")
        val reply = answer("上周服饰花了多少")
        assertNotNull(reply)
        assertTrue(reply!!.contains("服饰"))
        assertTrue(reply.contains("¥42.00"))
    }

    // ── 意图 2：近 30 天最花钱的一天 ───────────────────────────

    @Test
    fun `最花钱的一天取金额最大日且忽略 30 天前账单`() = runBlocking {
        val t = today()
        billAt(t.minusDays(1), 3000L)
        billAt(t.minusDays(1), 2000L) // 当天合计 ¥50.00
        billAt(t.minusDays(2), 8000L, "交通") // 最大 ¥80.00
        billAt(t.minusDays(30), 500000L) // 恰好出 30 天窗口，不应计入

        val expected = t.minusDays(2)
        val reply = answer("最近哪天花钱最多")
        assertNotNull(reply)
        assertTrue(reply!!.contains("近30天花得最多的一天是 ${expected.monthValue}月${expected.dayOfMonth}日"))
        assertTrue(reply.contains("¥80.00"))
        assertTrue(reply.contains("（1笔）"))
        assertFalse(reply.contains("5000.00"))
    }

    @Test
    fun `最花钱的一天金额并列时取最近一天`() = runBlocking {
        val t = today()
        billAt(t.minusDays(3), 3000L)
        billAt(t.minusDays(3), 2000L) // 合计 5000，日期更近
        billAt(t.minusDays(4), 5000L, "交通") // 同为 5000，日期更早

        val expected = t.minusDays(3)
        val reply = answer("哪天开销最大")
        assertNotNull(reply)
        assertTrue(reply!!.contains("${expected.monthValue}月${expected.dayOfMonth}日"))
        assertTrue(reply.contains("¥50.00"))
        assertTrue(reply.contains("（2笔）"))
    }

    @Test
    fun `近30天无支出时给引导文案`() = runBlocking {
        val reply = answer("哪天开销最大")
        assertNotNull(reply)
        assertTrue(reply!!.contains("还没有支出记录"))
    }

    // ── 意图 3：日均可用 ───────────────────────────────────────

    @Test
    fun `日均可用有预算时按剩余天数长除`() = runBlocking {
        val t = today()
        budgetService.upsert(1L, dayStart(t.withDayOfMonth(1)), 10000L) // 预算 ¥100
        billService.createBill(userId = 1L, amountMinor = 3000L, categoryName = "三餐", remark = null) // 今天花 ¥30

        val daysLeft = t.lengthOfMonth() - t.dayOfMonth + 1
        val dailyMinor = 7000L / daysLeft
        val reply = answer("每天还能花多少")
        assertNotNull(reply)
        assertTrue(reply!!.contains("本月预算还剩 ¥70.00"))
        assertTrue(reply.contains("这个月还剩 $daysLeft 天"))
        assertTrue(reply.contains("平均每天还能花 ¥${Money.format(dailyMinor)}"))
    }

    @Test
    fun `日均可用无预算时给引导文案`() = runBlocking {
        val reply = answer("日均还能花多少")
        assertNotNull(reply)
        assertTrue(reply!!.contains("还没设预算"))
    }

    @Test
    fun `日均可用超支时改口为超支提醒`() = runBlocking {
        val t = today()
        budgetService.upsert(1L, dayStart(t.withDayOfMonth(1)), 1000L) // 预算 ¥10
        billService.createBill(userId = 1L, amountMinor = 3000L, categoryName = "三餐", remark = null) // 已花 ¥30

        val reply = answer("一天还能花多少")
        assertNotNull(reply)
        assertTrue(reply!!.contains("本月预算已超 ¥20.00"))
        assertFalse(reply.contains("平均每天"))
    }

    // ── 未命中 ─────────────────────────────────────────────────

    @Test
    fun `未命中的文本一律返回 null 落回既有路由`() = runBlocking {
        assertNull(answer("午餐20元")) // 记账句
        assertNull(answer("这个月花了多少")) // 既有查询（无上周/本周/这周）
        assertNull(answer("上周花了多少")) // 无分类词，富查账不接管
        assertNull(answer("今天没花钱打卡")) // 管家打卡句
        assertNull(answer(""))
    }
}
