package com.example.rinklnote.ui.screen.profile

import androidx.compose.runtime.Immutable
import com.example.rinklnote.data.network.dto.MeResponse
import com.example.rinklnote.domain.AchievementState
import com.example.rinklnote.domain.parseShowcaseBadges

/**
 * 个人资料页（personal-profile 路由）的状态：文字资料 / 头像 / 徽章展示 + 实时派生的成就列表。
 * 资料字段登录后以服务端为唯一可信源；未登录时仅昵称回落本地 DataStore。
 */
@Immutable
data class PersonalProfileState(
    /** 是否正在做首次拉取（me 请求在途）。 */
    val loading: Boolean = true,
    val isLoggedIn: Boolean = false,
    /** 服务端资料；null = 未设置（UI 显示「未设置」或回落）。 */
    val nickname: String? = null,
    val signature: String? = null,
    /** ISO 生日串（yyyy-MM-dd）；星座由 UI 按公历区间实时推算。 */
    val birthday: String? = null,
    /** 服务端头像相对 URL（含 ?v= 版本参数）；null = 未上传。 */
    val avatarUrl: String? = null,
    /** 本地头像（DataStore 缓存 / 未登录时的本机头像）。 */
    val localAvatarUri: String? = null,
    /** 展示徽章 key（用户勾选的原样列表；展示与上传时再与解锁态取交集兜底）。 */
    val showcaseBadges: List<String> = emptyList(),
    /** 实时派生的 15 枚成就（删账单回退后自动回落）。 */
    val achievements: List<AchievementState> = emptyList(),
    /** 资料保存（PUT）在途标记。 */
    val saving: Boolean = false,
    /** 头像压缩上传在途标记。 */
    val uploadingAvatar: Boolean = false,
    val error: String? = null
)

/** 个人资料页事件：行内编辑 + 勾选徽章 + 头像裁剪结果回传，全部经 onEvent 分发。 */
sealed interface PersonalProfileEvent {
    /** 昵称行内编辑（弹窗确认回传；空串 = 清除昵称）。 */
    data class NicknameChanged(val value: String) : PersonalProfileEvent

    /** 个性签名行内编辑（弹窗确认回传；空串 = 清除签名）。 */
    data class SignatureChanged(val value: String) : PersonalProfileEvent

    /** 生日选择（ISO 串；null = 清除）。 */
    data class BirthdayChanged(val value: String?) : PersonalProfileEvent

    /** 显式保存（各编辑弹窗确认 / 徽章勾选后触发；未登录仅写本地昵称缓存）。 */
    data object SaveProfile : PersonalProfileEvent

    /** 勾选 / 取消一枚展示徽章（成功后自动触发保存）。 */
    data class ToggleBadge(val badgeId: String) : PersonalProfileEvent

    /** 取景框裁剪完成回传的头像本地路径（background-crop?mode=avatar 的 onConfirm）。 */
    data class AvatarCropConfirmed(val imagePath: String) : PersonalProfileEvent
}

/** 个人资料页一次性副作用（Channel 承载，页面消费后即弃）。 */
sealed interface PersonalProfileEffect {
    /** 资料或头像已成功写回服务端：「我的」卡片应刷新（触发 AuthEvent.FetchProfile）。 */
    data object ProfileSynced : PersonalProfileEffect
}

/** MeResponse → 状态映射（纯函数，独立可测）：服务端为可信源，整体覆盖资料字段。 */
internal fun applyMeToProfileState(state: PersonalProfileState, me: MeResponse): PersonalProfileState =
    state.copy(
        loading = false,
        isLoggedIn = true,
        nickname = me.nickname,
        signature = me.signature,
        birthday = me.birthday,
        avatarUrl = me.avatarUrl,
        showcaseBadges = parseShowcaseBadges(me.showcaseBadges)
    )

/**
 * 头像相对 URL → 完整地址：RetrofitClient.BASE_URL 以 / 结尾，直接拼接即可。
 * 兼容服务端直接下发绝对地址的情形；`relative` 为 null 时原样返回 null（回落本地头像）。
 */
internal fun resolveAvatarFullUrl(baseUrl: String, relative: String?): String? {
    if (relative.isNullOrBlank()) return null
    return if (relative.startsWith("http://") || relative.startsWith("https://")) relative else baseUrl + relative
}

/**
 * 「我的」卡片徽章行的展示集合 = 用户勾选 ∩ 实时解锁态。
 * 某枚徽章因删账单回退失去解锁态时自动隐藏（成就零存储特性的展示端兜底）。
 */
internal fun displayableShowcaseBadges(
    showcaseBadges: List<String>,
    achievements: List<AchievementState>
): List<String> = showcaseBadges.filter { id ->
    achievements.any { it.id == id && it.unlocked }
}
