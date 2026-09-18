package com.example.rinklnote.server.services.coach

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.ChallengeService
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.tables.AccountsTable
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BudgetsTable
import com.example.rinklnote.server.tables.CategoriesTable
import com.example.rinklnote.server.tables.ChallengesTable
import com.example.rinklnote.server.tables.SubCategoriesTable
import com.example.rinklnote.server.tables.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Task 1.4：账单教练单测。派生口径与 App `domain/ChallengeEngine.kt` 一致
 * （整数 HALF_UP 线性外推，`值×倍数 + 除数/2) ÷ 除数`），测试向量写死。
 * 隐私红线自查：建议文案只含聚合数字与分类名，绝不出现账单备注。
 */
class CoachServiceTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val billService = BillService()
    private val budgetService = BudgetService()
    private val challengeService = ChallengeService()
    private val coach = CoachService(billService, budgetService, challengeService)

    /** 固定「今天」= 2026-09-16（周三）；本周一 = 2026-09-14。 */
    private val today = LocalDate.of(2026, 9, 16)
    private val weekStartEpoch = LocalDate.of(2026, 9, 14).atStartOfDay(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        TestDatabase.connect("coach")
        transaction {
            SchemaUtils.create(
                UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable,
                BudgetsTable, ChallengesTable
            )
            BillsTable.deleteAll()
            ChallengesTable.deleteAll()
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
                it[UsersTable.phone] = "13800000061"
                it[UsersTable.passwordHash] = "hash"
                it[UsersTable.createdAt] = "2026-01-01"
            }
        }
    }

    private fun insertWeekBill(amountMinor: Long, categoryName: String, day: LocalDate, remark: String? = null) {
        val accountId = billService.accountIdFor(1L)
        val cat = billService.getCategories().first { it.name == categoryName }
        billService.createWebBill(
            userId = 1L, amountMinor = amountMinor, billType = "EXPENSE",
            categoryId = cat.id, categoryName = cat.name, subCategoryName = null,
            accountId = accountId, remark = remark,
            date = day.atStartOfDay(zone).toInstant().toEpochMilli()
        )
    }

    // ── 纯函数（向量写死，口径对齐 App ChallengeEngine.forecastMonth）──

    @Test
    fun `线性基准整数HALF_UP`() {
        // 7000 × 4 ÷ 7 = 4000（(28000+3)/7 = 4000）
        assertEquals(4000L, CoachService.linearBaselineMinor(7000L, 4, 7))
        // 7000 × 3 ÷ 7 = 3000（(21000+3)/7 = 3000）
        assertEquals(3000L, CoachService.linearBaselineMinor(7000L, 3, 7))
        // 无法外推时原样返回预算
        assertEquals(7000L, CoachService.linearBaselineMinor(7000L, 0, 7))
        // 月度口径：100000 分预算，9 月过 16/30 天 → (100000×16 + 15)/30 = 53333
        assertEquals(53333L, CoachService.linearBaselineMinor(100000L, 16, 30))
    }

    @Test
    fun `期末外推整数HALF_UP`() {
        // 5000 × 7 ÷ 4 = 8750（(35000+2)/4 = 8750）
        assertEquals(8750L, CoachService.forecastPeriodMinor(5000L, 4, 7))
        // 3050 × 7 ÷ 3 = 7117（(21350+1)/3 = 7117）
        assertEquals(7117L, CoachService.forecastPeriodMinor(3050L, 3, 7))
        // App forecastMonth 同向量：total=100, day=10, days=30 → (100×30+5)/10 = 300
        assertEquals(300L, CoachService.forecastPeriodMinor(100L, 10, 30))
    }

    @Test
    fun `烧穿档位判定`() {
        // OVER：外推 8750 超预算 7000 达 25%（>10%）
        assertEquals(CoachService.BurnLevel.OVER, CoachService.burnLevel(5000L, 4000L, 8750L, 7000L))
        // FAST：外推 7117 会烧穿但幅度 ≤10%（71170 ≤ 77000）
        assertEquals(CoachService.BurnLevel.FAST, CoachService.burnLevel(3050L, 3004L, 7117L, 7000L))
        // ON_TRACK：外推 4667 ≤ 7000
        assertEquals(CoachService.BurnLevel.ON_TRACK, CoachService.burnLevel(2000L, 3000L, 4667L, 7000L))
    }

    // ── 聚合 → 建议生成 ──

    @Test
    fun `周烧穿预警带挑战id且无明细泄漏`() {
        val challenge = challengeService.upsert(1L, "WEEKLY_BUDGET", weekStartEpoch, 7000L, "ACTIVE")
        // 周二花了 50 元（5000 分）：第 3 天基准 3000，外推 (5000×7+2)/4？——今天周三=已过3天
        // forecast = (5000×7 + 3/2=1)/3 = 35001/3 = 11667 > 7000 → OVER
        insertWeekBill(5000L, "三餐", LocalDate.of(2026, 9, 15), remark = "绝密备注内容")
        val advices = coach.weeklyAdvice(1L, today)
        assertTrue(advices.isNotEmpty())
        val over = advices.first { it.type == "WEEKLY_BURN_OVER" }
        assertEquals("WARN", over.level)
        assertEquals(challenge.id, over.challengeId)
        assertTrue(over.text.contains("50.00"))
        assertTrue(over.text.contains("三餐"))
        assertTrue(over.text.contains("远超"))
        // 隐私红线：文案绝不包含账单备注
        assertFalse(over.text.contains("绝密备注"))
        // 周报文案可由建议拼出（标题走小盘人格化）
        val push = coach.weeklyPushCopy(1L, today)!!
        assertTrue(push.contains("小盘周报"))
        assertTrue(push.contains("预计"))
    }

    @Test
    fun `节奏平稳给正反馈`() {
        challengeService.upsert(1L, "WEEKLY_BUDGET", weekStartEpoch, 7000L, "ACTIVE")
        // 周三只花 20 元（2000 分）：基准 3000、外推 4667 → ON_TRACK
        insertWeekBill(2000L, "交通", LocalDate.of(2026, 9, 15))
        val advices = coach.weeklyAdvice(1L, today)
        val onTrack = advices.first { it.type == "WEEKLY_ON_TRACK" }
        assertEquals("INFO", onTrack.level)
        assertTrue(onTrack.text.contains("基准"))
    }

    @Test
    fun `月预算外推超支给预警并带预算id`() {
        // 无周挑战；月预算 60 元（6000 分），9 月过 16 天花 40 元（4000 分）→ 外推 7500 > 6000
        val monthStartEpoch = LocalDate.of(2026, 9, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val budget = budgetService.upsert(1L, monthStartEpoch, 6000L)
        insertWeekBill(4000L, "日用", LocalDate.of(2026, 9, 15))
        val advices = coach.weeklyAdvice(1L, today)
        val monthOver = advices.first { it.type == "MONTH_FORECAST_OVER" }
        assertEquals("WARN", monthOver.level)
        assertEquals(budget.id, monthOver.budgetId)
        assertTrue(monthOver.text.contains("75.00"))
    }

    @Test
    fun `无预算无挑战时不出建议`() {
        insertWeekBill(5000L, "三餐", LocalDate.of(2026, 9, 15))
        assertTrue(coach.weeklyAdvice(1L, today).isEmpty())
        assertTrue("无建议不推周报", coach.weeklyPushCopy(1L, today) == null)
    }

    @Test
    fun `周报每周最多一条由PushScheduler去重`() {
        // WEEKLY_REPORT 的 dayKey 锚定周一；这里验证 key 生成口径本身稳定
        val monday = CoachService.weekStartOf(today)
        assertEquals(LocalDate.of(2026, 9, 14), monday)
        assertEquals(LocalDate.of(2026, 9, 14), CoachService.weekStartOf(LocalDate.of(2026, 9, 14)))
        assertEquals(LocalDate.of(2026, 9, 14), CoachService.weekStartOf(LocalDate.of(2026, 9, 20)))
        assertEquals(LocalDate.of(2026, 9, 21), CoachService.weekStartOf(LocalDate.of(2026, 9, 21)))
    }
}
