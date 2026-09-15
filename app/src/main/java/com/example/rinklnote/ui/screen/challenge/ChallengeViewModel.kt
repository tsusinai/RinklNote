package com.example.rinklnote.ui.screen.challenge

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Challenge
import com.example.rinklnote.data.db.entity.ChallengeStatus
import com.example.rinklnote.data.db.entity.ChallengeType
import com.example.rinklnote.data.db.entity.DailyCategoryAmount
import com.example.rinklnote.data.db.entity.DailySpendStat
import com.example.rinklnote.data.repository.BudgetRepository
import com.example.rinklnote.data.repository.ChallengeRepository
import com.example.rinklnote.domain.DayKind
import com.example.rinklnote.domain.AchievementInput
import com.example.rinklnote.domain.AchievementState
import com.example.rinklnote.domain.currentBookkeepingStreak
import com.example.rinklnote.domain.dayKind
import com.example.rinklnote.domain.evaluateAchievements
import com.example.rinklnote.domain.expenseBetween
import com.example.rinklnote.domain.forecastMonth
import com.example.rinklnote.domain.lessBuySaving
import com.example.rinklnote.domain.monthEndExclusive
import com.example.rinklnote.domain.monthStartOf
import com.example.rinklnote.domain.monthlyBudgetOutcomes
import com.example.rinklnote.domain.noSpendDaysBetween
import com.example.rinklnote.domain.remainingDays
import com.example.rinklnote.domain.toBookkeepingDate
import com.example.rinklnote.domain.toDayStartEpoch
import com.example.rinklnote.domain.weekStartOf
import com.example.rinklnote.util.bookkeepingZone
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 打卡墙单日格子：当月一天一行三态着色的数据源。 */
@Immutable
data class PunchDay(
    val dayOfMonth: Int,
    val kind: DayKind,       // NO_RECORD=未记账 / NO_SPEND=无消费 / SPEND=有支出
    val isToday: Boolean,
    val isFuture: Boolean,   // 本月尚未到的日子，UI 画得更淡
)

/** 「少买一点」候选分类（当月支出 Top，整数分）。 */
@Immutable
data class CategoryTotal(
    val categoryName: String,
    val totalMinor: Long,
)

/** 主题解锁行（晨曦/薄荷/琥珀）：解锁谓词由引擎值推导，应用动作在自定义主题页（E2 接线）。 */
@Immutable
data class ThemeUnlockState(
    val id: String,          // dawn / mint / amber
    val name: String,
    val condition: String,   // 解锁条件文案（全中文）
    val unlocked: Boolean,
)

/**
 * 挑战页状态：全部由 combine 的输入即时推导（不依赖 VM 存活期内的 monthStart/weekStart，
 * 跨月/跨周后每次发射现算当期），金额一律整数分。
 */
@Immutable
data class ChallengeState(
    val isLoading: Boolean = true,
    /** 是否记过任何一笔账（首笔账存在），一笔账没有时整页走空态引导。 */
    val hasAnyBill: Boolean = false,
    // —— 预测卡 ——
    val monthExpenseMinor: Long = 0L,
    val dayOfMonth: Int = 1,
    val daysInMonth: Int = 30,
    val dailyAverageMinor: Long = 0L,
    /** 按日均外推的月底支出预测（整数分）。 */
    val forecastMinor: Long = 0L,
    /** 当月总额维度预算合计；null = 本月未设总额预算（不展示结余预测）。 */
    val monthBudgetMinor: Long? = null,
    /** 预计结余 = 预算 − 预测支出；负数即预计超支（UI 红字）。 */
    val forecastBalanceMinor: Long? = null,
    val forecastOverBudget: Boolean = false,
    // —— 少买一点（纯 UI 选择，不入库） ——
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val lessBuyCategory: String? = null,
    val lessBuyPercent: Int = ChallengeViewModel.LESS_BUY_PERCENT_DEFAULT,
    val lessBuySavingMinor: Long = 0L,
    // —— 无消费日挑战（月，periodStart = 本月 1 日 0 点） ——
    val noSpendChallenge: Challenge? = null,
    val monthNoSpendDays: Int = 0,
    /** 月周期剩余天数（含今天，不含下月 1 日）。 */
    val noSpendRemainingDays: Int = 0,
    /** 当月打卡墙（1 日..月末），三态着色 + 今天描边。 */
    val punchDays: List<PunchDay> = emptyList(),
    /** 当月 1 日是周几（ISO：1=周一..7=周日），打卡墙首行补位用。 */
    val punchFirstWeekday: Int = 1,
    // —— 连续记账挑战（periodStart = 承诺日 0 点，不自动建行） ——
    val streakChallenge: Challenge? = null,
    val streakCurrent: Int = 0,
    // —— 周预算挑战（periodStart = 本周一 0 点） ——
    val weeklyChallenge: Challenge? = null,
    val weekExpenseMinor: Long = 0L,
    /** 周周期剩余天数（含今天，不含下周一）。 */
    val weekRemainingDays: Int = 0,
    // —— 成就墙（15 枚，evaluateAchievements 返回顺序即展示顺序） ——
    val achievements: List<AchievementState> = emptyList(),
    val themeUnlocks: List<ThemeUnlockState> = emptyList(),
)

/** 挑战页事件：目标调整 / 周预算设置 / 连续记账开挑战与换档 / 少买选择。 */
sealed interface ChallengeEvent {
    /** 调整无消费日目标（±1，夹取 1..28 天）。 */
    data class AdjustNoSpendGoal(val delta: Int) : ChallengeEvent

    /** 设置本周支出上限（NumericKeypad 确认，整数分）。 */
    data class SetWeeklyBudget(val amountMinor: Long) : ChallengeEvent

    /** 开始连续记账挑战（承诺日 = 今天，goal 取档位 7/14/21/30/50/100）。 */
    data class StartStreakChallenge(val goalDays: Long) : ChallengeEvent

    /** 调整进行中连续记账挑战的目标档位（不改承诺日）。 */
    data class AdjustStreakGoal(val goalDays: Long) : ChallengeEvent

    /** 少买一点：选中分类（null = 取消选择）。 */
    data class SelectLessBuyCategory(val categoryName: String?) : ChallengeEvent

    /** 少买一点：设置百分比（10..100，步进 10，越界夹取）。 */
    data class SetLessBuyPercent(val percent: Int) : ChallengeEvent
}

/**
 * 单条挑战的懒回写判定：返回应写入的新状态（ACHIEVED/MISSED），null = 维持现状不回写。
 * 只对 status=ACTIVE 的行给出结论（每周期只回写一次的保证：回写落库后 status 变更，
 * 下次发射不再命中）。时间口径一律业务时区。
 */
internal fun settleChallengeStatus(
    row: Challenge,
    stats: List<DailySpendStat>,
    today: LocalDate,
): String? {
    if (row.status != ChallengeStatus.ACTIVE || row.deleted) return null
    return when (row.type) {
        ChallengeType.NO_SPEND_DAY -> {
            if (row.goal <= 0) return null
            val endExclusive = monthEndExclusive(row.periodStart.toBookkeepingDate()).toDayStartEpoch()
            val noSpendDays = noSpendDaysBetween(stats, row.periodStart, endExclusive)
            when {
                // 月内提前达标即时结算；窗口外历史天不参与（BillDao 400 天窗口的既定取舍）。
                noSpendDays >= row.goal -> ChallengeStatus.ACHIEVED
                // 周期已过完（排他终点 ≤ 今天）仍未达标 → 错过。
                endExclusive <= today.toDayStartEpoch() -> ChallengeStatus.MISSED
                else -> null
            }
        }

        ChallengeType.WEEKLY_BUDGET -> {
            // goal=0 表示还没手输上限：不结算，避免「0 上限秒败」。
            if (row.goal <= 0) return null
            val endExclusiveDate = row.periodStart.toBookkeepingDate().plusWeeks(1)
            val spent = expenseBetween(stats, row.periodStart, endExclusiveDate.toDayStartEpoch())
            when {
                // 周内任一天累计超上限即失败（周预算的语义是「整周不超」）。
                spent > row.goal -> ChallengeStatus.MISSED
                // 整周过完仍未超 → 达成。
                endExclusiveDate <= today -> ChallengeStatus.ACHIEVED
                else -> null
            }
        }

        ChallengeType.BOOKKEEPING_STREAK -> {
            // 只统计承诺日（含）之后的日子：承诺日之前的连击不计入本承诺。
            val scoped = stats.filter { it.dayStart >= row.periodStart }
            val kindByDay = HashMap<Long, DayKind>(scoped.size)
            for (stat in scoped) kindByDay[stat.dayStart] = dayKind(stat)
            // 断签：承诺日..昨天 之间存在「整天没记账」的日子即断（今天有宽限，见引擎口径）。
            var broken = false
            var cursor = row.periodStart.toBookkeepingDate()
            val yesterday = today.minusDays(1)
            while (cursor <= yesterday) {
                if (kindByDay[cursor.toDayStartEpoch()] == DayKind.NO_RECORD) {
                    broken = true
                    break
                }
                cursor = cursor.plusDays(1)
            }
            when {
                broken -> ChallengeStatus.MISSED
                currentBookkeepingStreak(scoped, today) >= row.goal -> ChallengeStatus.ACHIEVED
                else -> null
            }
        }

        else -> null
    }
}

/**
 * 纯函数：挑战页完整状态派生（独立可测）。输入全部来自 combine 的最新发射，
 * 输出为一次性 @Immutable 快照；任何账单/预算/挑战变更后结果实时回落。
 */
internal fun deriveChallengeState(
    today: LocalDate,
    stats: List<DailySpendStat>,
    challenges: List<Challenge>,
    budgets: List<Budget>,
    recordedDays: Int,
    firstBillDate: Long?,
    monthCategoryTotals: List<DailyCategoryAmount>,
    lessBuyCategory: String?,
    lessBuyPercent: Int,
): ChallengeState {
    val zone = bookkeepingZone()
    val monthStartDate = monthStartOf(today)
    val monthStart = monthStartDate.toDayStartEpoch(zone)
    val monthEnd = monthEndExclusive(today).toDayStartEpoch(zone)
    val weekStartDate = weekStartOf(today)
    val weekStart = weekStartDate.toDayStartEpoch(zone)
    val weekEnd = weekStartDate.plusWeeks(1).toDayStartEpoch(zone)

    // —— 月 / 周支出与预测 ——
    val monthExpense = expenseBetween(stats, monthStart, monthEnd)
    val weekExpense = expenseBetween(stats, weekStart, weekEnd)
    val dayOfMonth = today.dayOfMonth
    val daysInMonth = today.lengthOfMonth()
    val dailyAverage = if (dayOfMonth > 0) monthExpense / dayOfMonth else 0L
    val forecast = forecastMonth(monthExpense, dayOfMonth, daysInMonth)

    // 当月总额维度预算合计：分类/子分类预算与软删行剔除、同月多行合计（口径与 monthlyBudgetOutcomes 一致）。
    val monthBudgetTotal = budgets
        .filter {
            !it.deleted && it.categoryId == null && it.subCategoryId == null && it.monthStart == monthStart
        }
        .sumOf { it.amountMinor }
        .takeIf { it > 0 }
    val forecastBalance = monthBudgetTotal?.let { it - forecast }

    // —— 打卡墙 ——
    val kindByDay = HashMap<Long, DayKind>(stats.size)
    for (stat in stats) kindByDay[stat.dayStart] = dayKind(stat)
    val punchDays = (0 until daysInMonth).map { offset ->
        val date = monthStartDate.plusDays(offset.toLong())
        PunchDay(
            dayOfMonth = date.dayOfMonth,
            kind = kindByDay[date.toDayStartEpoch(zone)] ?: DayKind.NO_RECORD,
            isToday = date == today,
            isFuture = date.isAfter(today),
        )
    }

    // —— 三张挑战卡对应的当期行 ——
    val monthNoSpendDays = noSpendDaysBetween(stats, monthStart, monthEnd)
    val noSpendRow = challenges.firstOrNull {
        it.type == ChallengeType.NO_SPEND_DAY && it.periodStart == monthStart && !it.deleted
    }
    val weeklyRow = challenges.firstOrNull {
        it.type == ChallengeType.WEEKLY_BUDGET && it.periodStart == weekStart && !it.deleted
    }
    // 连续记账取「进行中的优先，否则最近承诺的一行」——已达成/已错过的最近一次也展示在卡上。
    val streakRow = challenges
        .filter { it.type == ChallengeType.BOOKKEEPING_STREAK && !it.deleted }
        .let { rows ->
            rows.firstOrNull { it.status == ChallengeStatus.ACTIVE }
                ?: rows.maxByOrNull { it.periodStart }
        }
    val streakCurrent = currentBookkeepingStreak(stats, today)

    // —— 少买一点 ——
    val sortedCategories = monthCategoryTotals
        .filter { it.total > 0 }
        .sortedByDescending { it.total }
        .take(8)
        .map { CategoryTotal(it.categoryName, it.total) }
    val lessBuyBase = sortedCategories.firstOrNull { it.categoryName == lessBuyCategory }?.totalMinor
    val lessBuySavingMinor = lessBuyBase?.let { lessBuySaving(it, lessBuyPercent) } ?: 0L

    // —— 成就（15 枚）与主题解锁 ——
    val achievements = evaluateAchievements(
        AchievementInput(
            recordedDays = recordedDays,
            firstBillDate = firstBillDate,
            dailyStats = stats,
            budgetOutcomes = monthlyBudgetOutcomes(stats, budgets),
            achievedChallengeCount = challenges.count {
                it.status == ChallengeStatus.ACHIEVED && !it.deleted
            },
        )
    )
    val totalNoSpend = stats.count { dayKind(it) == DayKind.NO_SPEND }
    val amberUnlocked = achievements.firstOrNull { it.id == "budget-3months" }?.unlocked ?: false
    val themeUnlocks = listOf(
        ThemeUnlockState("dawn", "晨曦", "连续记账 30 天解锁", streakCurrent >= 30),
        ThemeUnlockState("mint", "薄荷", "累计无消费 100 天解锁", totalNoSpend >= 100),
        ThemeUnlockState("amber", "琥珀", "连续 3 个月不超支解锁", amberUnlocked),
    )

    return ChallengeState(
        isLoading = false,
        hasAnyBill = firstBillDate != null,
        monthExpenseMinor = monthExpense,
        dayOfMonth = dayOfMonth,
        daysInMonth = daysInMonth,
        dailyAverageMinor = dailyAverage,
        forecastMinor = forecast,
        monthBudgetMinor = monthBudgetTotal,
        forecastBalanceMinor = forecastBalance,
        forecastOverBudget = forecastBalance != null && forecastBalance < 0,
        categoryTotals = sortedCategories,
        lessBuyCategory = lessBuyCategory,
        lessBuyPercent = lessBuyPercent,
        lessBuySavingMinor = lessBuySavingMinor,
        noSpendChallenge = noSpendRow,
        monthNoSpendDays = monthNoSpendDays,
        noSpendRemainingDays = remainingDays(monthEnd, today),
        punchDays = punchDays,
        punchFirstWeekday = monthStartDate.dayOfWeek.value,
        streakChallenge = streakRow,
        streakCurrent = streakCurrent,
        weeklyChallenge = weeklyRow,
        weekExpenseMinor = weekExpense,
        weekRemainingDays = remainingDays(weekEnd, today),
        achievements = achievements,
        themeUnlocks = themeUnlocks,
    )
}

/**
 * 省钱挑战页 ViewModel：日粒度账单统计 + 挑战表 + 预算表三流合流，现算当期进度、
 * 预测、成就与主题解锁；周期滚动幂等补行、周期结束/断签懒回写状态（落库即 dirty 交同步推送）。
 */
class ChallengeViewModel(
    private val billDao: BillDao,
    private val challengeRepository: ChallengeRepository,
    private val budgetRepository: BudgetRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChallengeState())
    val state: StateFlow<ChallengeState> = _state.asStateFlow()

    // 「少买一点」的纯 UI 选择（分类 + 百分比），并入主合流以实时重算省钱数，不落库。
    private val _lessBuyCategory = MutableStateFlow<String?>(null)
    private val _lessBuyPercent = MutableStateFlow(LESS_BUY_PERCENT_DEFAULT)

    init {
        viewModelScope.launch {
            combine(
                billDao.observeDailySpendStats(observationWindowStart(), observationWindowEnd()),
                challengeRepository.observeAll(),
                budgetRepository.observeBudgets(),
                _lessBuyCategory,
                _lessBuyPercent,
            ) { stats, challenges, budgets, lessBuyCategory, lessBuyPercent ->
                val today = LocalDate.now(bookkeepingZone())
                // 幂等补行：首启与周期滚动（跨月/跨周）后补当期挑战行；已存在的行绝不动用户目标。
                ensureCurrentPeriodRows(today)
                // 懒回写：周期结束/断签/提前达标的进行中挑战按口径落 ACHIEVED / MISSED
                // （updateStatus 置 dirty=1 交 SyncManager 推送；回写后 flow 重发，天然只写一次）。
                challenges.forEach { row ->
                    settleChallengeStatus(row, stats, today)?.let { status ->
                        challengeRepository.updateStatus(row.id, status)
                    }
                }
                deriveChallengeState(
                    today = today,
                    stats = stats,
                    challenges = challenges,
                    budgets = budgets,
                    recordedDays = billDao.countRecordedDays(),
                    firstBillDate = billDao.getFirstBillDate(),
                    monthCategoryTotals = billDao.getDailyExpenseSummary(
                        monthStartOf(today).toDayStartEpoch(),
                        monthEndExclusive(today).toDayStartEpoch(),
                    ),
                    lessBuyCategory = lessBuyCategory,
                    lessBuyPercent = lessBuyPercent,
                )
            }.collect { derived ->
                _state.value = derived
            }
        }
    }

    fun onEvent(event: ChallengeEvent) {
        when (event) {
            is ChallengeEvent.AdjustNoSpendGoal -> adjustNoSpendGoal(event.delta)
            is ChallengeEvent.SetWeeklyBudget -> setWeeklyBudget(event.amountMinor)
            is ChallengeEvent.StartStreakChallenge -> startStreakChallenge(event.goalDays)
            is ChallengeEvent.AdjustStreakGoal -> adjustStreakGoal(event.goalDays)
            is ChallengeEvent.SelectLessBuyCategory -> _lessBuyCategory.value = event.categoryName
            is ChallengeEvent.SetLessBuyPercent ->
                _lessBuyPercent.value = event.percent.coerceIn(LESS_BUY_PERCENT_MIN, LESS_BUY_PERCENT_MAX)
        }
    }

    /**
     * 幂等补当期挑战行：
     * - 当月无消费日（periodStart = 本月 1 日 0 点，goal 默认 8 天）；
     * - 本周预算（periodStart = 本周一 0 点，goal = 当月总额预算 × 7 ÷ 当月天数向上取整，
     *   无总额预算则 0 引导手输）。
     * 连续记账不自动建行，由用户「开始挑战」事件创建。
     * 只在「该 scope 一行都没有」时创建（含软删行也算存在），绝不复活/改写已有行——
     * 保证 upsertActive 的复活语义只响应用户显式操作，且不会与服务端墓碑打架。
     */
    private suspend fun ensureCurrentPeriodRows(today: LocalDate) {
        val existing = challengeRepository.observeAll().first()
        val monthStart = monthStartOf(today).toDayStartEpoch()
        val monthHasNoSpendRow = existing.any {
            it.type == ChallengeType.NO_SPEND_DAY && it.periodStart == monthStart
        }
        if (!monthHasNoSpendRow) {
            challengeRepository.upsertActive(ChallengeType.NO_SPEND_DAY, monthStart, NO_SPEND_GOAL_DEFAULT)
        }

        val weekStart = weekStartOf(today).toDayStartEpoch()
        val weekHasBudgetRow = existing.any {
            it.type == ChallengeType.WEEKLY_BUDGET && it.periodStart == weekStart
        }
        if (!weekHasBudgetRow) {
            val daysInMonth = today.lengthOfMonth()
            val monthBudgetTotal = budgetRepository.observeBudgets().first()
                .filter {
                    !it.deleted && it.categoryId == null && it.subCategoryId == null &&
                        it.monthStart == monthStart
                }
                .sumOf { it.amountMinor }
            // 月预算 × 7 ÷ 当月天数，向上取整（纯整数运算）。
            val weekGoal =
                if (monthBudgetTotal > 0) (monthBudgetTotal * 7 + daysInMonth - 1) / daysInMonth else 0L
            challengeRepository.upsertActive(ChallengeType.WEEKLY_BUDGET, weekStart, weekGoal)
        }
    }

    /** 调整无消费日目标（±1，夹取 1..28）。upsertActive 同 scope 复活：已达/已错过行调目标后重新进行中。 */
    private fun adjustNoSpendGoal(delta: Int) {
        val row = _state.value.noSpendChallenge ?: return
        val newGoal = (row.goal + delta).coerceIn(NO_SPEND_GOAL_MIN, NO_SPEND_GOAL_MAX)
        if (newGoal == row.goal) return
        viewModelScope.launch {
            challengeRepository.upsertActive(ChallengeType.NO_SPEND_DAY, row.periodStart, newGoal)
        }
    }

    /** 设置本周支出上限（整数分）：upsertActive 同 scope 改目标，并把已错过/已达成行复活为进行中（改上限即重新考核）。 */
    private fun setWeeklyBudget(amountMinor: Long) {
        if (amountMinor <= 0) return
        val weekStart = weekStartOf(LocalDate.now(bookkeepingZone())).toDayStartEpoch()
        viewModelScope.launch {
            challengeRepository.upsertActive(ChallengeType.WEEKLY_BUDGET, weekStart, amountMinor)
        }
    }

    /** 开始连续记账挑战：承诺日 = 今天 0 点，goal 取档位；同承诺日已有行则复活并改档。 */
    private fun startStreakChallenge(goalDays: Long) {
        if (goalDays <= 0) return
        viewModelScope.launch {
            challengeRepository.upsertActive(
                ChallengeType.BOOKKEEPING_STREAK,
                LocalDate.now(bookkeepingZone()).toDayStartEpoch(),
                goalDays,
            )
        }
    }

    /** 调整进行中连续挑战的目标档位（不动承诺日；已错过行只能重新「开始挑战」换新承诺日）。 */
    private fun adjustStreakGoal(goalDays: Long) {
        val row = _state.value.streakChallenge ?: return
        if (goalDays <= 0 || goalDays == row.goal) return
        viewModelScope.launch {
            challengeRepository.updateGoal(row.id, goalDays)
        }
    }

    /** 日统计观察窗口：今天 −400 天 ~ 明天（BillDao 契约口径；窗口局限见 DAO 注释）。 */
    private fun observationWindowStart(): Long =
        LocalDate.now(bookkeepingZone()).minusDays(400).toDayStartEpoch()

    private fun observationWindowEnd(): Long =
        LocalDate.now(bookkeepingZone()).plusDays(1).toDayStartEpoch()

    companion object {
        /** 无消费日挑战默认目标（天）。 */
        const val NO_SPEND_GOAL_DEFAULT = 8L
        const val NO_SPEND_GOAL_MIN = 1L
        const val NO_SPEND_GOAL_MAX = 28L

        /** 连续记账挑战目标档位（天）。 */
        val STREAK_TIERS = listOf(7L, 14L, 21L, 30L, 50L, 100L)

        /** 「少买一点」百分比范围与默认值（步进 10）。 */
        const val LESS_BUY_PERCENT_MIN = 10
        const val LESS_BUY_PERCENT_MAX = 100
        const val LESS_BUY_PERCENT_STEP = 10
        const val LESS_BUY_PERCENT_DEFAULT = 30
    }

    class Factory(
        private val billDao: BillDao,
        private val challengeRepository: ChallengeRepository,
        private val budgetRepository: BudgetRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChallengeViewModel(billDao, challengeRepository, budgetRepository) as T
        }
    }
}
