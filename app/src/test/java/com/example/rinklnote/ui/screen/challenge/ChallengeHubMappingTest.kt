package com.example.rinklnote.ui.screen.challenge

import com.example.rinklnote.data.db.entity.Challenge
import com.example.rinklnote.data.db.entity.ChallengeStatus
import com.example.rinklnote.data.db.entity.ChallengeType
import com.example.rinklnote.domain.DayKind
import com.example.rinklnote.ui.screen.profile.UnlockableThemePresets
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挑战区「大字报乐园」展示层纯映射单测：hub 英雄区 / 功能卡 / 竞技场 / 预测器 / 换装间。
 * 输入直接构造 [ChallengeState]（展示层翻译的契约输入），金额一律整数分。
 */
class ChallengeHubMappingTest {

    private fun punchDay(day: Int, kind: DayKind, isFuture: Boolean = false) =
        PunchDay(dayOfMonth = day, kind = kind, isToday = false, isFuture = isFuture)

    private fun state(
        monthExpenseMinor: Long = 0L,
        monthIncomeMinor: Long = 0L,
        monthNoSpendDays: Int = 0,
        punchDays: List<PunchDay> = emptyList(),
        streakCurrent: Int = 0,
        streakChallenge: Challenge? = null,
        noSpendChallenge: Challenge? = null,
        weeklyChallenge: Challenge? = null,
        weeklyExpenseMinor: Long = 0L,
        weekRemainingDays: Int = 3,
        forecastMinor: Long = 0L,
        forecastBalanceMinor: Long? = null,
        forecastOverBudget: Boolean = false,
        daysInMonth: Int = 30,
        dayOfMonth: Int = 15,
        dailyAverageMinor: Long = 0L,
        achievements: List<com.example.rinklnote.domain.AchievementState> = fullBadgeRoster(),
        themeUnlocks: List<ThemeUnlockState> = defaultThemes(),
    ) = ChallengeState(
        monthExpenseMinor = monthExpenseMinor,
        monthIncomeMinor = monthIncomeMinor,
        monthNoSpendDays = monthNoSpendDays,
        punchDays = punchDays,
        streakCurrent = streakCurrent,
        streakChallenge = streakChallenge,
        noSpendChallenge = noSpendChallenge,
        weeklyChallenge = weeklyChallenge,
        weekExpenseMinor = weeklyExpenseMinor,
        weekRemainingDays = weekRemainingDays,
        forecastMinor = forecastMinor,
        forecastBalanceMinor = forecastBalanceMinor,
        forecastOverBudget = forecastOverBudget,
        daysInMonth = daysInMonth,
        dayOfMonth = dayOfMonth,
        dailyAverageMinor = dailyAverageMinor,
        achievements = achievements,
        themeUnlocks = themeUnlocks,
    )

    /** 引擎满编 15 枚成就（全部未解锁）：模拟真实状态形状，测角标计数。 */
    private fun fullBadgeRoster() = List(15) { index ->
        com.example.rinklnote.domain.AchievementState(
            id = "stub-$index",
            unlocked = false,
            progressCurrent = 0L,
            progressTarget = 1L,
        )
    }

    private fun defaultThemes() = listOf(
        ThemeUnlockState("dawn", "晨曦", "连续记账 30 天解锁", false),
        ThemeUnlockState("mint", "薄荷", "累计无消费 100 天解锁", true),
        ThemeUnlockState("amber", "琥珀", "连续 3 个月不超支解锁", false),
    )

    // ---------- hub 英雄区 ----------

    @Test
    fun `hero saved = no spend days x spend day average`() {
        // 10 个有消费日花了 30000 分 → 日均 3000；3 个无消费日 → 已省 9000 分
        val punch = List(10) { punchDay(it + 1, DayKind.SPEND) } +
            List(3) { punchDay(11 + it, DayKind.NO_SPEND) } +
            List(2) { punchDay(14 + it, DayKind.NO_RECORD) }
        val hero = deriveChallengeHero(
            state(
                monthExpenseMinor = 30000L,
                monthNoSpendDays = 3,
                punchDays = punch,
            )
        )
        assertEquals(3000L, hero.spendDayAvgMinor)
        assertEquals(9000L, hero.savedMinor)
    }

    @Test
    fun `hero saved is zero when no spend days`() {
        val hero = deriveChallengeHero(state(monthExpenseMinor = 50000L, monthNoSpendDays = 0))
        assertEquals(0L, hero.savedMinor)
    }

    @Test
    fun `hero surplus = income minus expense and keeps sign`() {
        assertEquals(
            2000L,
            deriveChallengeHero(state(monthIncomeMinor = 12000L, monthExpenseMinor = 10000L)).monthSurplusMinor
        )
        assertEquals(
            -500L,
            deriveChallengeHero(state(monthIncomeMinor = 9500L, monthExpenseMinor = 10000L)).monthSurplusMinor
        )
    }

    @Test
    fun `hero on fire starts at streak 3 and only active streak row gives goal`() {
        val active = Challenge(
            type = ChallengeType.BOOKKEEPING_STREAK, periodStart = 0L, goal = 30L,
            status = ChallengeStatus.ACTIVE,
        )
        val achieved = Challenge(
            type = ChallengeType.BOOKKEEPING_STREAK, periodStart = 0L, goal = 30L,
            status = ChallengeStatus.ACHIEVED,
        )
        val hero = deriveChallengeHero(state(streakCurrent = 3, streakChallenge = active))
        assertTrue(hero.onFire)
        assertEquals(30L, hero.streakGoal)

        assertFalse(deriveChallengeHero(state(streakCurrent = 2, streakChallenge = active)).onFire)
        // 已达成/已错过行不是「进行中目标」
        assertNull(deriveChallengeHero(state(streakCurrent = 5, streakChallenge = achieved)).streakGoal)
    }

    @Test
    fun `hero surplus text keeps plus and minus`() {
        assertEquals("+¥120.00", heroSurplusText(12000L))
        assertEquals("-¥120.00", heroSurplusText(-12000L))
    }

    // ---------- hub 功能卡 ----------

    @Test
    fun `hub cards badges reflect unlocked counts and active challenges`() {
        val streak = Challenge(
            type = ChallengeType.BOOKKEEPING_STREAK, periodStart = 0L, goal = 14L,
            status = ChallengeStatus.ACTIVE,
        )
        val noSpend = Challenge(
            type = ChallengeType.NO_SPEND_DAY, periodStart = 0L, goal = 8L,
            status = ChallengeStatus.ACTIVE,
        )
        val cards = deriveHubCards(
            state(
                streakChallenge = streak,
                noSpendChallenge = noSpend,
                monthNoSpendDays = 2,
                forecastBalanceMinor = 5000L,
                themeUnlocks = defaultThemes(),
            )
        )
        assertEquals(5, cards.size)
        val arena = cards.first { it.key == HubCardKey.ARENA }
        assertEquals("2/3", arena.badge)
        val achievements = cards.first { it.key == HubCardKey.ACHIEVEMENTS }
        assertEquals("0/15", achievements.badge)
        val predictor = cards.first { it.key == HubCardKey.PREDICTOR }
        assertEquals("有结余", predictor.badge)
        assertTrue(predictor.subtitle.contains("结余"))
        val envelope = cards.first { it.key == HubCardKey.ENVELOPE }
        assertEquals("剩3天", envelope.badge)
        val themes = cards.first { it.key == HubCardKey.THEMES }
        assertEquals("1/3", themes.badge)
    }

    @Test
    fun `hub predictor badge warns when over budget`() {
        val cards = deriveHubCards(state(forecastBalanceMinor = -800L, forecastOverBudget = true))
        assertEquals("超支!", cards.first { it.key == HubCardKey.PREDICTOR }.badge)
        // 未设预算
        val unset = deriveHubCards(state(forecastBalanceMinor = null))
        assertEquals("未设预算", unset.first { it.key == HubCardKey.PREDICTOR }.badge)
    }

    // ---------- 竞技场 ----------

    @Test
    fun `arena cards carry type and progress fractions`() {
        val weekly = Challenge(
            type = ChallengeType.WEEKLY_BUDGET, periodStart = 0L, goal = 10000L,
            status = ChallengeStatus.ACTIVE,
        )
        val cards = deriveArenaCards(
            state(
                monthNoSpendDays = 4,
                weeklyChallenge = weekly,
                weeklyExpenseMinor = 4000L,
            )
        )
        assertEquals(3, cards.size)
        val noSpend = cards[0]
        assertEquals(ChallengeType.NO_SPEND_DAY, noSpend.type)
        assertEquals(4f / ChallengeViewModel.NO_SPEND_GOAL_DEFAULT, noSpend.fraction, 0.0001f)
        val streak = cards[1]
        assertEquals(ChallengeType.BOOKKEEPING_STREAK, streak.type)
        assertTrue(streak.notStarted) // 未开连续记账挑战
        val budget = cards[2]
        assertEquals(ChallengeType.WEEKLY_BUDGET, budget.type)
        assertEquals(0.4f, budget.fraction, 0.0001f)
        assertFalse(budget.notStarted)
    }

    @Test
    fun `arena weekly over budget keeps fraction above one and marks missed`() {
        val weekly = Challenge(
            type = ChallengeType.WEEKLY_BUDGET, periodStart = 0L, goal = 10000L,
            status = ChallengeStatus.MISSED,
        )
        val card = deriveArenaCards(state(weeklyChallenge = weekly, weeklyExpenseMinor = 12000L))[2]
        assertEquals(1.2f, card.fraction, 0.0001f)
        assertTrue(card.missed)
    }

    // ---------- 预测器 ----------

    @Test
    fun `predictor derives milk tea scenario from days left`() {
        val predictor = derivePredictor(
            state(daysInMonth = 30, dayOfMonth = 10),
            milkTeaPriceMinor = 1500L,
        )
        assertEquals(20, predictor.daysLeft)
        assertEquals(20 * 1500L, predictor.milkTeaSavingMinor)
        assertEquals(10f / 30f, predictor.monthProgress, 0.0001f)
    }

    @Test
    fun `predictor keeps balance and over budget flags from state`() {
        val predictor = derivePredictor(state(forecastBalanceMinor = -1000L, forecastOverBudget = true))
        assertEquals(-1000L, predictor.balanceMinor)
        assertTrue(predictor.overBudget)
    }

    // ---------- 成就徽章馆 ----------

    @Test
    fun `achievement badges carry name and condition`() {
        // 空 achievements（引擎未返回）→ 空表
        assertTrue(deriveAchievementBadges(state(achievements = emptyList())).isEmpty())
        // 满编 15 枚；已知 id 映射中文名与条件文案，未知 id 走兜底不崩
        val badges = deriveAchievementBadges(
            state(
                achievements = fullBadgeRoster().mapIndexed { index, badge ->
                    if (index == 0) badge.copy(id = "record-7") else badge
                }
            )
        )
        assertEquals(15, badges.size)
        assertEquals("记账 7 天", badges[0].name)
        assertEquals("累计记账 7 天", badges[0].condition)
        assertEquals("stub-1", badges[1].name)
        assertEquals("达成条件未知", badges[1].condition)
    }

    // ---------- 主题换装间 ----------

    @Test
    fun `theme cards mark in use by primary hex`() {
        val dawnPreset = UnlockableThemePresets.first { it.id == "dawn" }
        val themes = defaultThemes().map { it.copy(unlocked = it.id == "dawn") }
        // 比对契约：归一化为 RGB 段后一致即命中——DataStore 存 "#AARRGGBB"（带 alpha 段）也命中
        val cards = deriveThemeCards(state(themeUnlocks = themes), "#FFEF7D68")
        assertTrue(cards[0].inUse)

        val hex = String.format(
            "#%06X",
            0xFFFFFF and dawnPreset.light.themeColor.toArgb(),
        )
        val cardsInUse = deriveThemeCards(state(themeUnlocks = themes), hex)
        assertTrue(cardsInUse[0].inUse)
        assertTrue(cardsInUse[0].unlocked)
        assertFalse(cardsInUse[1].inUse)
    }

    @Test
    fun `theme cards without preset match stay not in use`() {
        val cards = deriveThemeCards(state(themeUnlocks = defaultThemes()), null)
        assertTrue(cards.none { it.inUse })
    }
}
