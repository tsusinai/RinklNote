package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.ChallengeService
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BotConfigTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.ChallengesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 阶段 3 · 小盘主动照料（ButlerCareService）单测：
 * 预算亮灯跨沿判定 / 同月同档去重 / 月切档重置 / 收入不亮灯；
 * 晨暮检的时刻与开关 gate / KV 解析容错 / 全空不打扰 / 文案锚点；
 * 邮件入账通知的文案与发送兜底。
 *
 * 模式与 BotCorrectServiceTest 一致：每测一个全新 H2 内存库 + 幂等种子；
 * 账单经 createWebBill 带显式日期落库（createBill 恒落「真实今天」，无法配合固定时钟）。
 */
class ButlerCareServiceTest {

    private val log = LoggerFactory.getLogger("ButlerCareServiceTest")

    private lateinit var billService: BillService
    private lateinit var budgetService: BudgetService
    private lateinit var challengeService: ChallengeService
    private lateinit var userService: UserService

    companion object {
        private const val USER_ID = 1L
        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }

    @Before
    fun setup() {
        TestDatabase.connect("butlercare")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable,
                BillsTable, BudgetsTable, ChallengesTable, BotConfigTable
            )
            // 外键依赖序清空（bills 引用 categories/accounts/users）
            BotConfigTable.deleteAll()
            BillsTable.deleteAll()
            BudgetsTable.deleteAll()
            ChallengesTable.deleteAll()
            AccountsTable.deleteAll()
            SubCategoriesTable.deleteAll()
            CategoriesTable.deleteAll()
            UsersTable.deleteAll()
        }
        billService = BillService()
        billService.seedIfNeeded()
        budgetService = BudgetService()
        challengeService = ChallengeService()
        userService = UserService(jwtSecret = "test-secret", jwtIssuer = "test", jwtAudience = "rinklnote")
        transaction {
            UsersTable.insert {
                it[UsersTable.id] = USER_ID
                it[UsersTable.phone] = "13800000077"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    // ── 造数辅助 ──

    /** 固定时钟构造被测服务。 */
    private fun newCare(now: LocalDateTime): ButlerCareService =
        ButlerCareService(billService, budgetService, challengeService, userService, log, clock = { now })

    /** 业务时区某日 0 点 epoch 毫秒（与实现里 bills.date 口径一致）。 */
    private fun dayStart(d: LocalDate): Long = d.atStartOfDay(ZONE).toInstant().toEpochMilli()

    private fun monthStartOf(now: LocalDateTime): Long = dayStart(now.toLocalDate().withDayOfMonth(1))

    /** 带显式日期的账单（billType 与分类类型一致）。 */
    private fun bookBill(amountMinor: Long, categoryName: String, billType: String, date: Long) {
        val category = billService.getCategories().first { it.name == categoryName && it.billType == billType }
        billService.createWebBill(
            userId = USER_ID,
            amountMinor = amountMinor,
            billType = billType,
            categoryId = category.id,
            categoryName = category.name,
            subCategoryName = null,
            accountId = billService.accountIdFor(USER_ID),
            remark = null,
            date = date
        )
    }

    private fun expenseBill(amountMinor: Long, categoryName: String, date: Long) =
        bookBill(amountMinor, categoryName, "EXPENSE", date)

    private fun incomeBill(amountMinor: Long, categoryName: String, date: Long) =
        bookBill(amountMinor, categoryName, "INCOME", date)

    private fun setCategoryBudget(categoryName: String, monthStart: Long, amountMinor: Long) {
        val category = billService.getCategories().first { it.name == categoryName && it.billType == "EXPENSE" }
        budgetService.upsert(USER_ID, monthStart, amountMinor, categoryId = category.id, subCategoryId = null)
    }

    private fun setTotalBudget(monthStart: Long, amountMinor: Long) {
        budgetService.upsert(USER_ID, monthStart, amountMinor)
    }

    /** KV 单键写入（整表清空后插入，规避主键冲突）。 */
    private fun setKv(key: String, value: String) {
        transaction {
            BotConfigTable.deleteAll()
            BotConfigTable.insert {
                it[BotConfigTable.key] = key
                it[BotConfigTable.value] = value
            }
        }
    }

    // ── budgetLight 预算亮灯 ──

    @Test
    fun `分类预算85%整点跨沿触发黄灯且同月同档只告一次`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 12, 0)
            setCategoryBudget("三餐", monthStartOf(now), 10_000L) // ¥100
            expenseBill(8_500L, "三餐", dayStart(now.toLocalDate()))
            val care = newCare(now)

            // 本笔 8500 恰好把占比顶到 85% 整点：after >= 85% 触发（>= 而非 >）
            val first = care.budgetLight("三餐", 8_500L, USER_ID)
            assertNotNull(first)
            assertTrue(first!!.contains("黄灯预警"))
            assertTrue(first.contains("三餐"))
            assertTrue(first.contains("已用 85%"))
            assertTrue(first.contains("还剩 ¥15.00"))

            // 无分类预算也无总额预算的分类：不告警
            assertNull(care.budgetLight("交通", 1_000L, USER_ID))

            // 同月同档只告一次：支出被改小后再次跨过 85%，去重表拦截（判定条件成立也不重复告）
            val meal = billService.allBills(USER_ID).first { it.categoryName == "三餐" }
            billService.updateBillAmount(meal.id, USER_ID, 4_000L)
            expenseBill(5_000L, "三餐", dayStart(now.toLocalDate())) // 支出回到 9000（90%）
            assertNull(care.budgetLight("三餐", 5_000L, USER_ID))
        }
    }

    @Test
    fun `分类预算100%跨沿触发红灯且单笔跳档时L2优先`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 12, 0)
            setCategoryBudget("三餐", monthStartOf(now), 10_000L)
            expenseBill(4_000L, "三餐", dayStart(now.toLocalDate()))
            val care = newCare(now)

            // 40%：未跨沿不告警
            assertNull(care.budgetLight("三餐", 4_000L, USER_ID))

            // 单笔从 40% 直接到 105%：同时满足 L1/L2 跨沿，L2 优先
            expenseBill(6_500L, "三餐", dayStart(now.toLocalDate()))
            val full = care.budgetLight("三餐", 6_500L, USER_ID)
            assertNotNull(full)
            assertTrue(full!!.contains("预算已花完"))
            assertTrue(full.contains("¥105.00/¥100.00"))
            assertFalse(full.contains("黄灯预警"))

            // 已花完后再记账（before 已 >= 预算）：不再跨沿也不再重复告
            expenseBill(500L, "三餐", dayStart(now.toLocalDate()))
            assertNull(care.budgetLight("三餐", 500L, USER_ID))
        }
    }

    @Test
    fun `无任何预算时亮灯返回null`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 12, 0)
            expenseBill(9_900L, "三餐", dayStart(now.toLocalDate()))
            assertNull(newCare(now).budgetLight("三餐", 9_900L, USER_ID))
        }
    }

    @Test
    fun `无分类预算回退总额预算且总额同档跨分类只告一次`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 12, 0)
            setTotalBudget(monthStartOf(now), 10_000L)
            expenseBill(8_600L, "三餐", dayStart(now.toLocalDate()))
            val care = newCare(now)

            // 三餐没有分类预算 → 回退总额预算，文案用「总预算」
            val first = care.budgetLight("三餐", 8_600L, USER_ID)
            assertNotNull(first)
            assertTrue(first!!.contains("总预算"))
            assertTrue(first.contains("已用 86%"))
            assertFalse(first.contains("三餐"))

            // 去重 key 用 __TOTAL__（与展示名分离）：换个分类再次跨同一总额档，同样被拦
            expenseBill(1_000L, "交通", dayStart(now.toLocalDate())) // 总支出 9600
            assertNull(care.budgetLight("交通", 1_000L, USER_ID)) // before 已 86%，未跨沿
            val meal = billService.allBills(USER_ID).first { it.categoryName == "三餐" }
            billService.updateBillAmount(meal.id, USER_ID, 4_000L) // 总支出降到 5000
            expenseBill(4_000L, "日用", dayStart(now.toLocalDate())) // 总支出 9000（90%）
            assertNull(care.budgetLight("日用", 4_000L, USER_ID)) // 跨沿成立但 __TOTAL__ 档已告过
        }
    }

    @Test
    fun `月切档后同档重新告警`() {
        runBlocking {
            var now = LocalDateTime.of(2026, 10, 15, 12, 0)
            val care = ButlerCareService(
                billService, budgetService, challengeService, userService, log, clock = { now }
            )
            setCategoryBudget("三餐", monthStartOf(now), 10_000L)
            expenseBill(8_600L, "三餐", dayStart(now.toLocalDate()))
            assertNotNull(care.budgetLight("三餐", 8_600L, USER_ID)) // 10 月 L1

            // 同一实例切到 11 月：新预算行 + 新账单，同档因月份段不同重新告警
            now = LocalDateTime.of(2026, 11, 5, 12, 0)
            setCategoryBudget("三餐", monthStartOf(now), 10_000L)
            expenseBill(8_600L, "三餐", dayStart(now.toLocalDate()))
            val nov = care.budgetLight("三餐", 8_600L, USER_ID)
            assertNotNull(nov)
            assertTrue(nov!!.contains("已用 86%"))
        }
    }

    @Test
    fun `收入分类不触发亮灯`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 12, 0)
            setTotalBudget(monthStartOf(now), 10_000L)
            expenseBill(9_500L, "三餐", dayStart(now.toLocalDate())) // 总额已 95%
            incomeBill(100_000L, "工资", dayStart(now.toLocalDate()))
            // 工资是收入分类：即便总额已近预算也不因收入触发（收入不在支出聚合里，否则 before 被算小造成假跨沿）
            assertNull(newCare(now).budgetLight("工资", 100_000L, USER_ID))
        }
    }

    // ── morningReport 晨报 ──

    @Test
    fun `晨报未到配置时刻返回null`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 7, 0) // 默认时刻 08:30
            setTotalBudget(monthStartOf(now), 10_000L)
            expenseBill(5_000L, "三餐", dayStart(now.toLocalDate().minusDays(1)))
            assertNull(newCare(now).morningReport(USER_ID))
        }
    }

    @Test
    fun `晨报到点输出昨日金额预算零消费日与挑战`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 9, 0)
            setTotalBudget(monthStartOf(now), 10_000L)
            expenseBill(5_000L, "三餐", dayStart(now.toLocalDate().minusDays(1))) // 昨天 14 日
            expenseBill(2_000L, "交通", dayStart(now.toLocalDate().minusDays(2))) // 前天 13 日
            challengeService.upsert(USER_ID, "NO_SPEND_DAY", monthStartOf(now), 10L)

            val text = newCare(now).morningReport(USER_ID)
            assertNotNull(text)
            val t = text!!
            assertTrue(t.contains("早安"))
            assertTrue(t.contains("昨天花了 ¥50.00（1笔）")) // 只有昨天的 5000
            assertTrue(t.contains("本月预算还剩 ¥30.00（已用 70%）")) // 预算 100 - 已用 70
            assertTrue(t.contains("12 个零消费日")) // 1~14 日已过 14 天，有支出的是 13/14 两天
            assertTrue(t.contains("进行中的省钱挑战：1 个"))
        }
    }

    @Test
    fun `晨报KV开关非法值回落且时刻可自定义`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 9, 0)
            setTotalBudget(monthStartOf(now), 10_000L)
            expenseBill(5_000L, "三餐", dayStart(now.toLocalDate().minusDays(1)))
            val care = newCare(now)

            // 开关关闭 → null
            setKv("butler_morning_enabled", "false")
            assertNull(care.morningReport(USER_ID))

            // 非法开关值 → 回落默认 true，正常出报
            setKv("butler_morning_enabled", "on")
            assertNotNull(care.morningReport(USER_ID))

            // 自定义时刻 07:00 → 07:30 已到点，出报
            setKv("butler_morning_time", "07:00")
            assertNotNull(newCare(LocalDateTime.of(2026, 10, 15, 7, 30)).morningReport(USER_ID))

            // 非法时刻 → 回落默认 08:30，07:30 未到 → null
            setKv("butler_morning_time", "25:99")
            assertNull(newCare(LocalDateTime.of(2026, 10, 15, 7, 30)).morningReport(USER_ID))
        }
    }

    @Test
    fun `晨报无任何数据不打扰`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 9, 0)
            assertNull(newCare(now).morningReport(USER_ID))
        }
    }

    // ── eveningNudge 暮检 ──

    @Test
    fun `暮检今日无账给补记文案`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 22, 0)
            expenseBill(5_000L, "三餐", dayStart(now.toLocalDate().minusDays(1))) // 只有昨天的
            val text = newCare(now).eveningNudge(USER_ID)
            assertNotNull(text)
            assertTrue(text!!.contains("还没记过账"))
            assertTrue(text.contains("今天没花钱"))
        }
    }

    @Test
    fun `暮检未到时刻返回null`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 20, 0) // 默认时刻 21:30
            expenseBill(5_000L, "三餐", dayStart(now.toLocalDate()))
            assertNull(newCare(now).eveningNudge(USER_ID))
        }
    }

    @Test
    fun `暮检有账输出笔数收支且无收入时省略收入`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 22, 0)
            expenseBill(3_000L, "三餐", dayStart(now.toLocalDate()))
            expenseBill(1_500L, "交通", dayStart(now.toLocalDate()))
            incomeBill(10_000L, "工资", dayStart(now.toLocalDate()))
            val text = newCare(now).eveningNudge(USER_ID)
            assertNotNull(text)
            assertTrue(text!!.contains("记了 3 笔"))
            assertTrue(text.contains("支出 ¥45.00"))
            assertTrue(text.contains("（收入 ¥100.00）"))

            // 无收入账单：省略收入括号
            transaction { BillsTable.deleteAll() }
            expenseBill(2_000L, "三餐", dayStart(now.toLocalDate()))
            val text2 = newCare(now).eveningNudge(USER_ID)
            assertNotNull(text2)
            assertTrue(text2!!.contains("记了 1 笔"))
            assertTrue(text2.contains("支出 ¥20.00"))
            assertFalse(text2.contains("收入"))
        }
    }

    @Test
    fun `暮检KV关闭返回null`() {
        runBlocking {
            val now = LocalDateTime.of(2026, 10, 15, 22, 0)
            expenseBill(3_000L, "三餐", dayStart(now.toLocalDate()))
            setKv("butler_evening_enabled", "false")
            assertNull(newCare(now).eveningNudge(USER_ID))
        }
    }

    // ── mailBookedNotice 邮件入账通知 ──

    @Test
    fun `邮件入账通知包含商户金额与分类`() {
        runBlocking {
            val care = newCare(LocalDateTime.of(2026, 10, 15, 12, 0))
            val sent = mutableListOf<Pair<Long, String>>()
            care.channelSender = { userId, content ->
                sent.add(userId to content)
                true
            }
            care.mailBookedNotice(USER_ID, "美团", 2_500L, "三餐")
            assertEquals(1, sent.size)
            assertEquals(USER_ID, sent[0].first)
            val content = sent[0].second
            assertTrue(content.contains("邮件账单已入账"))
            assertTrue(content.contains("美团"))
            assertTrue(content.contains("¥25.00"))
            assertTrue(content.contains("（三餐）"))
            assertTrue(content.contains("分类不对"))
        }
    }

    @Test
    fun `邮件入账通知分类为空时省略括号`() {
        runBlocking {
            val care = newCare(LocalDateTime.of(2026, 10, 15, 12, 0))
            val contents = mutableListOf<String>()
            care.channelSender = { _, content ->
                contents.add(content)
                true
            }
            care.mailBookedNotice(USER_ID, "自动扣款", 1_000L, "")
            assertEquals(1, contents.size)
            assertTrue(contents[0].contains("¥10.00"))
            assertFalse(contents[0].contains("（"))
        }
    }

    @Test
    fun `邮件入账通知发送失败无通道或异常均不抛`() {
        runBlocking {
            val care = newCare(LocalDateTime.of(2026, 10, 15, 12, 0))
            // sender 返回 false：只落日志不抛
            care.channelSender = { _, _ -> false }
            care.mailBookedNotice(USER_ID, "美团", 2_500L, "三餐")
            // sender 为 null：只记日志不抛
            care.channelSender = null
            care.mailBookedNotice(USER_ID, "美团", 2_500L, "三餐")
            // sender 抛异常：吞掉不抛
            care.channelSender = { _, _ -> error("通道挂了") }
            care.mailBookedNotice(USER_ID, "美团", 2_500L, "三餐")
        }
    }
}
