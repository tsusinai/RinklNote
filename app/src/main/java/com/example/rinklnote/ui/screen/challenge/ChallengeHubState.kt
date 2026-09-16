package com.example.rinklnote.ui.screen.challenge

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.toArgb
import com.example.rinklnote.data.db.entity.ChallengeStatus
import com.example.rinklnote.data.db.entity.ChallengeType
import com.example.rinklnote.domain.DayKind
import com.example.rinklnote.ui.component.achievementBadgeName
import com.example.rinklnote.ui.screen.profile.UnlockableThemePresets
import com.example.rinklnote.util.Money

/**
 * 挑战区展示层纯映射：把 [ChallengeState]（由 bills / budgets / challenges 三流实时派生）
 * 翻译成 hub 大字报、竞技场、预测器、徽章馆、换装间各自的展示模型。
 *
 * 约束：
 * - 全部为纯函数，任何账单 / 预算 / 挑战变更后输入回落 → 输出实时回落（零存储特性照旧）；
 * - 金额一律整数分（Long），格式化只走 [Money]；数据层（ChallengeEngine / Achievements /
 *   Repository）零改动，本文件只做展示翻译；
 * - 本组映射是 VM 级单测的直接对象（见 ChallengeHubMappingTest）。
 */

// ---------------------------------------------------------------------------
// Hub 英雄区大字报
// ---------------------------------------------------------------------------

/** hub 顶部大字报：超大「本月已省」+ 副行「本月结余」+ 连击火焰徽章的数据源。 */
@Immutable
data class ChallengeHeroState(
    /** 本月已省（整数分）。口径 = 挑战节省：本月无消费日数 × 有消费日的日均支出——每个无消费日省下了「平常一天」的花销。 */
    val savedMinor: Long,
    /** 本月无消费日天数（口径来源，供副行文案）。 */
    val noSpendDays: Int,
    /** 有消费日的日均支出（整数分，「已省」口径的说明项）。 */
    val spendDayAvgMinor: Long,
    /** 本月结余 = 收入 − 支出（整数分，负数即超支）。 */
    val monthSurplusMinor: Long,
    /** 当前连续记账天数（连击）。 */
    val streakCurrent: Int,
    /** 进行中连续记账挑战的目标天数；null = 未开挑战（不显示目标）。 */
    val streakGoal: Long?,
    /** 连击 ≥ 3 → 火焰徽章点亮（连击数字跳动动效开关）。 */
    val onFire: Boolean,
)

internal fun deriveChallengeHero(state: ChallengeState): ChallengeHeroState {
    val spendDays = state.punchDays.count { !it.isFuture && it.kind == DayKind.SPEND }
    val avg = if (spendDays > 0) state.monthExpenseMinor / spendDays else 0L
    val goal = state.streakChallenge
        ?.takeIf { it.status == ChallengeStatus.ACTIVE && !it.deleted }
        ?.goal
    return ChallengeHeroState(
        savedMinor = state.monthNoSpendDays * avg,
        noSpendDays = state.monthNoSpendDays,
        spendDayAvgMinor = avg,
        monthSurplusMinor = state.monthIncomeMinor - state.monthExpenseMinor,
        streakCurrent = state.streakCurrent,
        streakGoal = goal,
        onFire = state.streakCurrent >= 3,
    )
}

/**
 * hub 副行结余文案：正数显式带 +，负数由 Money.format 自带 -（¥ 与否尊重展示偏好，
 * 不手写货币符号，避免「¥¥」双符号）。
 */
internal fun heroSurplusText(surplusMinor: Long): String =
    if (surplusMinor >= 0) "+" + Money.format(surplusMinor) else Money.format(surplusMinor)

// ---------------------------------------------------------------------------
// Hub 功能贴纸卡
// ---------------------------------------------------------------------------

/** hub 功能卡键（对应五张贴纸卡与各自路由）。 */
enum class HubCardKey { ARENA, ACHIEVEMENTS, PREDICTOR, ENVELOPE, THEMES }

/** hub 功能贴纸卡展示模型（hub 只做导航，不堆数据：一行副标题 + 一枚角标）。 */
@Immutable
data class HubCardState(
    val key: HubCardKey,
    val emoji: String,
    val title: String,
    val subtitle: String,
    /** 右上角贴纸徽标（短文案）；null = 不贴。 */
    val badge: String?,
)

internal fun deriveHubCards(state: ChallengeState): List<HubCardState> {
    // 竞技场：三挑战里进行中的场数
    val arenaRows = listOfNotNull(
        state.noSpendChallenge,
        state.streakChallenge,
        state.weeklyChallenge,
    )
    val activeCount = arenaRows.count { it.status == ChallengeStatus.ACTIVE && !it.deleted }
    val arenaBadge = "${activeCount.coerceIn(0, 3)}/3"

    // 徽章馆
    val unlocked = state.achievements.count { it.unlocked }
    val achievementBadge = "$unlocked/${state.achievements.size.coerceAtLeast(1)}"

    // 预测器：结余 / 超支 / 未设预算三态
    val predictorBadge = when {
        state.forecastBalanceMinor == null -> "未设预算"
        state.forecastOverBudget -> "超支!"
        else -> "有结余"
    }

    // 信封：本周剩余天数
    val envelopeBadge = "剩${state.weekRemainingDays}天"

    // 换装间
    val themeUnlocked = state.themeUnlocks.count { it.unlocked }
    val themeBadge = "${themeUnlocked}/${state.themeUnlocks.size.coerceAtLeast(1)}"

    return listOf(
        HubCardState(
            key = HubCardKey.ARENA,
            emoji = "🎮",
            title = "挑战竞技场",
            subtitle = "无消费 ${state.monthNoSpendDays} 天 · 连击 ${state.streakCurrent} 天",
            badge = arenaBadge,
        ),
        HubCardState(
            key = HubCardKey.ACHIEVEMENTS,
            emoji = "🏆",
            title = "成就徽章馆",
            subtitle = "已点亮 $unlocked 枚徽章",
            badge = achievementBadge,
        ),
        HubCardState(
            key = HubCardKey.PREDICTOR,
            emoji = "🔮",
            title = "结余预测器",
            subtitle = if (state.forecastBalanceMinor == null) {
                "设个总额预算开始预测"
            } else if (state.forecastOverBudget) {
                "按这个花法月底要超支啦"
            } else {
                "月底预计结余 ${Money.format(state.forecastBalanceMinor)}"
            },
            badge = predictorBadge,
        ),
        HubCardState(
            key = HubCardKey.ENVELOPE,
            emoji = "💌",
            title = "预算信封",
            subtitle = if ((state.weeklyChallenge?.goal ?: 0L) > 0) {
                "本周已花 ${Money.format(state.weekExpenseMinor)}"
            } else {
                "还没设本周上限，装一个？"
            },
            badge = envelopeBadge,
        ),
        HubCardState(
            key = HubCardKey.THEMES,
            emoji = "🎨",
            title = "主题换装间",
            subtitle = "已解锁 $themeUnlocked 套限定皮肤",
            badge = themeBadge,
        ),
    )
}

// ---------------------------------------------------------------------------
// 挑战竞技场（三张大字报卡）与详情
// ---------------------------------------------------------------------------

/** 竞技场单张挑战大字报卡。 */
@Immutable
data class ArenaChallengeCard(
    /** ChallengeType 常量，详情页路由参数。 */
    val type: String,
    val emoji: String,
    val title: String,
    /** 血条填充比例 0..1（周预算语义取「已花 / 上限」，不超支才有得赢）。 */
    val fraction: Float,
    /** 进度行文案（大数字旁的小字）。 */
    val progressLabel: String,
    /** 押注目标文案。 */
    val goalLabel: String,
    /** 周期剩余天数（含今天）。 */
    val remainingDays: Int,
    val achieved: Boolean,
    val missed: Boolean,
    /** 未开局的挑战（连续记账未承诺 / 周上限未设）→ 血条置灰。 */
    val notStarted: Boolean,
)

internal fun deriveArenaCards(state: ChallengeState): List<ArenaChallengeCard> {
    val noSpendGoal = state.noSpendChallenge?.goal ?: ChallengeViewModel.NO_SPEND_GOAL_DEFAULT
    val noSpend = ArenaChallengeCard(
        type = ChallengeType.NO_SPEND_DAY,
        emoji = "🛡️",
        title = "无消费日",
        fraction = if (noSpendGoal > 0) state.monthNoSpendDays.toFloat() / noSpendGoal else 0f,
        progressLabel = "打卡 ${state.monthNoSpendDays} / $noSpendGoal 天",
        goalLabel = "押注 ${noSpendGoal} 个零花钱日",
        remainingDays = state.noSpendRemainingDays,
        achieved = state.noSpendChallenge?.status == ChallengeStatus.ACHIEVED,
        missed = state.noSpendChallenge?.status == ChallengeStatus.MISSED,
        notStarted = false,
    )

    val streakRow = state.streakChallenge
    val streakActive = streakRow != null && streakRow.status == ChallengeStatus.ACTIVE &&
        !streakRow.deleted && streakRow.goal > 0
    val streakGoal = streakRow?.goal ?: 0L
    val streak = ArenaChallengeCard(
        type = ChallengeType.BOOKKEEPING_STREAK,
        emoji = "🔥",
        title = "连续记账",
        fraction = if (streakGoal > 0) state.streakCurrent.toFloat() / streakGoal else 0f,
        progressLabel = if (streakActive) "连击 ${state.streakCurrent} / $streakGoal 天" else "已连击 ${state.streakCurrent} 天",
        goalLabel = if (streakActive) "押注 $streakGoal 天不断签" else "选个档位开始押注",
        remainingDays = 0, // 连击无固定周期，剩余天数不适用
        achieved = streakRow?.status == ChallengeStatus.ACHIEVED,
        missed = streakRow?.status == ChallengeStatus.MISSED,
        notStarted = !streakActive,
    )

    val weeklyGoal = state.weeklyChallenge?.goal ?: 0L
    val weeklyStarted = weeklyGoal > 0
    val weekly = ArenaChallengeCard(
        type = ChallengeType.WEEKLY_BUDGET,
        emoji = "💌",
        title = "每周预算",
        fraction = if (weeklyStarted) state.weekExpenseMinor.toFloat() / weeklyGoal else 0f,
        progressLabel = if (weeklyStarted) {
            "已花 ${Money.format(state.weekExpenseMinor)} / 上限 ${Money.format(weeklyGoal)}"
        } else {
            "本周已花 ${Money.format(state.weekExpenseMinor)}"
        },
        goalLabel = if (weeklyStarted) "整周不超上限就赢" else "还没装本周上限",
        remainingDays = state.weekRemainingDays,
        achieved = state.weeklyChallenge?.status == ChallengeStatus.ACHIEVED,
        missed = state.weeklyChallenge?.status == ChallengeStatus.MISSED,
        notStarted = !weeklyStarted,
    )

    return listOf(noSpend, streak, weekly)
}

// ---------------------------------------------------------------------------
// 结余预测器
// ---------------------------------------------------------------------------

/** 「少花一单奶茶钱」默认情景单价（整数分，纯 UI 情景提示，不落库）。 */
internal const val MILK_TEA_PRICE_MINOR = 1500L

/** 结余预测大字报展示模型：已花 / 日均 / 预测结余三段大数字 + 情景提示。 */
@Immutable
data class PredictorPosterState(
    val spentMinor: Long,
    val dailyAvgMinor: Long,
    /** 按日均外推的月底支出预测（整数分）。 */
    val forecastMinor: Long,
    /** 预测结余 = 月总额预算 − 预测支出；null = 未设预算（第三段大数字换「预计支出」）。 */
    val balanceMinor: Long?,
    val overBudget: Boolean,
    /** 月度时间进度 0..1（血条：预算占用或时间进度）。 */
    val monthProgress: Float,
    /** 本月剩余天数（含今天）。 */
    val daysLeft: Int,
    /** 情景提示：剩余天数 × 奶茶单价（整数分）。 */
    val milkTeaSavingMinor: Long,
    // —— 少买一点（挑战页同款交互迁移到本页）——
    val categoryTotals: List<CategoryTotal>,
    val lessBuyCategory: String?,
    val lessBuyPercent: Int,
    val lessBuySavingMinor: Long,
)

internal fun derivePredictor(
    state: ChallengeState,
    milkTeaPriceMinor: Long = MILK_TEA_PRICE_MINOR,
): PredictorPosterState {
    val monthProgress = if (state.daysInMonth > 0) {
        state.dayOfMonth.toFloat() / state.daysInMonth
    } else {
        0f
    }
    return PredictorPosterState(
        spentMinor = state.monthExpenseMinor,
        dailyAvgMinor = state.dailyAverageMinor,
        forecastMinor = state.forecastMinor,
        balanceMinor = state.forecastBalanceMinor,
        overBudget = state.forecastOverBudget,
        monthProgress = monthProgress.coerceIn(0f, 1f),
        daysLeft = (state.daysInMonth - state.dayOfMonth).coerceAtLeast(0),
        milkTeaSavingMinor = (state.daysInMonth - state.dayOfMonth).coerceAtLeast(0) * milkTeaPriceMinor,
        categoryTotals = state.categoryTotals,
        lessBuyCategory = state.lessBuyCategory,
        lessBuyPercent = state.lessBuyPercent,
        lessBuySavingMinor = state.lessBuySavingMinor,
    )
}

// ---------------------------------------------------------------------------
// 成就徽章馆
// ---------------------------------------------------------------------------

/** 徽章馆单枚徽章：图标 + 名称 + 达成条件 + 真实账单算出的当前进度。 */
@Immutable
data class AchievementBadgeState(
    val id: String,
    val name: String,
    val condition: String,
    val unlocked: Boolean,
    val progressCurrent: Long,
    val progressTarget: Long,
)

internal fun deriveAchievementBadges(state: ChallengeState): List<AchievementBadgeState> =
    state.achievements.map { badge ->
        AchievementBadgeState(
            id = badge.id,
            name = achievementBadgeName(badge.id),
            condition = achievementCondition(badge.id),
            unlocked = badge.unlocked,
            progressCurrent = badge.progressCurrent,
            progressTarget = badge.progressTarget,
        )
    }

// ---------------------------------------------------------------------------
// 主题换装间
// ---------------------------------------------------------------------------

/** 换装间单张主题卡：解锁状态 + 是否使用中。 */
@Immutable
data class ThemeCardState(
    val id: String,
    val name: String,
    val condition: String,
    val unlocked: Boolean,
    /** 当前是否正在使用这套皮肤（对比当前主题色槽与预设主色）。 */
    val inUse: Boolean,
)

/**
 * 换装间映射：解锁谓词来自 [ChallengeState.themeUnlocks]（引擎值实时回落）；
 * 「使用中」= 当前自定义主题的 PRIMARY 槽与该预设主色一致（明暗两套任一命中即算）。
 * 比对归一化：去掉 alpha 段、忽略大小写（DataStore 存 "#AARRGGBB"，预设是纯色不透明）。
 *
 * @param currentPrimaryHex 当前 PRIMARY 槽的 hex（未自定义时为 null）
 */
internal fun deriveThemeCards(
    state: ChallengeState,
    currentPrimaryHex: String?,
): List<ThemeCardState> {
    val normalized = currentPrimaryHex?.takeLast(6)?.uppercase()
    return state.themeUnlocks.map { theme ->
        val preset = UnlockableThemePresets.firstOrNull { it.name == theme.name }
        ThemeCardState(
            id = theme.id,
            name = theme.name,
            condition = theme.condition,
            unlocked = theme.unlocked,
            inUse = preset != null && normalized != null && listOf(
                preset.light.themeColor,
                preset.dark.themeColor,
            ).any { it.toRgbHex() == normalized },
        )
    }
}

/** Color → "RRGGBB" 大写（「使用中」比对用，忽略 alpha）。 */
private fun androidx.compose.ui.graphics.Color.toRgbHex(): String =
    String.format("%06X", 0xFFFFFF and this.toArgb())

// ---------------------------------------------------------------------------
// 徽章目录（图标资源 / 中文名 / 达成条件；原 ChallengeScreen 内私有表迁移至此共享）
// ---------------------------------------------------------------------------

/**
 * 徽章图标 / 中文名映射统一在 `ui/component/AchievementBadgeUi.kt`
 * （2026-09-17 合并冲突去重：与「我的」徽章展示管理共用同一份，勿在本文件再写 when）。
 */

/** 成就达成条件（徽章详情弹层展示；口径与 Achievements.kt 引擎注释一致）。 */
internal fun achievementCondition(id: String): String = when (id) {
    "record-first" -> "记下第一笔账单"
    "record-7" -> "累计记账 7 天"
    "record-30" -> "累计记账 30 天"
    "record-100" -> "累计记账 100 天"
    "record-365" -> "累计记账 365 天"
    "nospend-month-3" -> "单月无消费 3 天（当天有记账且零支出）"
    "nospend-month-8" -> "单月无消费 8 天（当天有记账且零支出）"
    "nospend-month-15" -> "单月无消费 15 天（当天有记账且零支出）"
    "nospend-total-30" -> "累计无消费 30 天"
    "nospend-total-100" -> "累计无消费 100 天"
    "budget-first" -> "设置第一笔总额预算"
    "budget-month" -> "设有总额预算的月份不超支（1 个月）"
    "budget-3months" -> "连续 3 个月预算不超支（中间不能断）"
    "challenge-3" -> "完成 3 个省钱挑战"
    "challenge-10" -> "完成 10 个省钱挑战"
    else -> "达成条件未知"
}
