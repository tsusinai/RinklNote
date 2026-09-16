package com.example.rinklnote.ui.screen.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.ProfileUpdateRequest
import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.repository.BudgetRepository
import com.example.rinklnote.data.repository.ChallengeRepository
import com.example.rinklnote.domain.AchievementInput
import com.example.rinklnote.domain.evaluateAchievements
import com.example.rinklnote.domain.monthlyBudgetOutcomes
import com.example.rinklnote.domain.parseShowcaseBadges
import com.example.rinklnote.domain.toggleShowcaseBadge
import com.example.rinklnote.domain.toDayStartEpoch
import com.example.rinklnote.data.db.entity.ChallengeStatus
import com.example.rinklnote.util.bookkeepingZone
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * 个人资料页 ViewModel（路由级短生命周期，就近放在 screen/profile 包）：
 * - 资料字段：登录后 GET /api/auth/me 拉取、PUT /api/auth/profile 整体替换（每次行内编辑即存）；
 * - 头像：裁剪产物压缩到 512px JPEG 后 multipart 上传；未登录仅写本地 DataStore；
 * - 徽章展示：勾选 ≤3 枚随 PUT profile 同步；成就列表由 Room 三流实时派生（删账单自动回落）。
 *
 * 「我的」卡片联动经 [PersonalProfileEffect.ProfileSynced]：页面收集后触发 AuthEvent.FetchProfile。
 */
class PersonalProfileViewModel(
    private val appContext: Context,
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val settingsManager: SettingsManager,
    private val billDao: BillDao,
    private val challengeRepository: ChallengeRepository,
    private val budgetRepository: BudgetRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PersonalProfileState())
    val state: StateFlow<PersonalProfileState> = _state.asStateFlow()

    private val _effects = Channel<PersonalProfileEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            // 本地头像缓存先行（离线 / 未登录也有得显示），登录态再被服务端 URL 压过。
            settingsManager.avatarUri.collect { uri ->
                _state.update { it.copy(localAvatarUri = uri) }
            }
        }
        viewModelScope.launch {
            // 成就实时派生：窗口与挑战页一致（今天 −400 天 ~ 明天），删账单 / 回退后自动回落。
            val zone = bookkeepingZone()
            val now = LocalDate.now(zone)
            combine(
                billDao.observeDailySpendStats(
                    now.minusDays(400).toDayStartEpoch(zone),
                    now.plusDays(1).toDayStartEpoch(zone)
                ),
                challengeRepository.observeAll(),
                budgetRepository.observeBudgets()
            ) { stats, challenges, budgets ->
                evaluateAchievements(
                    AchievementInput(
                        recordedDays = billDao.countRecordedDays(),
                        firstBillDate = billDao.getFirstBillDate(),
                        dailyStats = stats,
                        budgetOutcomes = monthlyBudgetOutcomes(stats, budgets),
                        achievedChallengeCount = challenges.count {
                            it.status == ChallengeStatus.ACHIEVED && !it.deleted
                        }
                    )
                )
            }.collect { achievements ->
                _state.update { it.copy(achievements = achievements) }
            }
        }
        viewModelScope.launch {
            // 登录态检查（TokenManager.isLoggedIn 是挂起函数）：登录则拉服务端资料，未登录直接就绪。
            if (tokenManager.isLoggedIn()) {
                fetchProfile()
            } else {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun onEvent(event: PersonalProfileEvent) {
        when (event) {
            is PersonalProfileEvent.NicknameChanged -> {
                val trimmed = event.value.trim().takeIf { it.isNotEmpty() }
                _state.update { it.copy(nickname = trimmed) }
            }

            is PersonalProfileEvent.SignatureChanged -> {
                val trimmed = event.value.trim().takeIf { it.isNotEmpty() }
                _state.update { it.copy(signature = trimmed) }
            }

            is PersonalProfileEvent.BirthdayChanged ->
                _state.update { it.copy(birthday = event.value?.trim()?.takeIf { v -> v.isNotEmpty() }) }

            PersonalProfileEvent.SaveProfile -> persistProfile()

            is PersonalProfileEvent.ToggleBadge -> {
                val before = _state.value.showcaseBadges
                val after = toggleShowcaseBadge(before, event.badgeId)
                if (after == before && event.badgeId !in before) {
                    // 已达上限再添加：给出提示，不改动选择
                    _state.update { it.copy(error = "最多展示 3 枚徽章，请先取消一枚") }
                    return
                }
                _state.update { it.copy(showcaseBadges = after, error = null) }
                persistProfile()
            }

            is PersonalProfileEvent.AvatarCropConfirmed -> handleAvatarCrop(event.imagePath)
        }
    }

    /** 拉取服务端资料并覆盖本地状态；同时把昵称写进 DataStore 作「我的」卡片离线缓存。 */
    private fun fetchProfile() {
        viewModelScope.launch {
            try {
                val me = api.getMe()
                _state.update { applyMeToProfileState(it, me) }
                settingsManager.setNickname(me.nickname)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "资料加载失败：${e.message}") }
            }
        }
    }

    /**
     * 保存资料：登录 → PUT profile 整体替换（请求体永远带全量当前值）；
     * 未登录 → 仅昵称写 DataStore（卡片离线回落），其余字段提示需登录。
     */
    private fun persistProfile() {
        val s = _state.value
        if (!s.isLoggedIn) {
            viewModelScope.launch {
                settingsManager.setNickname(s.nickname)
                _state.update { it.copy(error = "未登录：昵称仅保存在本机，登录后才能全量同步") }
            }
            return
        }
        if (s.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                val me = api.updateProfile(
                    ProfileUpdateRequest(
                        nickname = s.nickname,
                        signature = s.signature,
                        birthday = s.birthday,
                        showcaseBadges = s.showcaseBadges
                    )
                )
                _state.update { applyMeToProfileState(it, me) }
                settingsManager.setNickname(me.nickname)
                _effects.send(PersonalProfileEffect.ProfileSynced)
            } catch (e: Exception) {
                _state.update { it.copy(error = "保存失败：${e.message}") }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    /** 头像裁剪产物：未登录仅落本地 DataStore；登录则压缩上传（成功后服务端 URL 接管显示）。 */
    private fun handleAvatarCrop(imagePath: String) {
        val s = _state.value
        if (!s.isLoggedIn) {
            viewModelScope.launch {
                settingsManager.setAvatarUri(imagePath)
                _state.update { it.copy(localAvatarUri = imagePath, error = "未登录：头像仅保存在本机") }
            }
            return
        }
        if (s.uploadingAvatar) return
        viewModelScope.launch {
            _state.update { it.copy(uploadingAvatar = true, error = null) }
            try {
                val compressed = compressAvatar(appContext, imagePath)
                    ?: throw IllegalStateException("头像压缩失败，请换一张图片")
                val part = MultipartBody.Part.createFormData(
                    "file", compressed.name,
                    compressed.asRequestBody("image/jpeg".toMediaType())
                )
                val resp = api.uploadAvatar(part)
                val url = resp.avatarUrl
                _state.update { it.copy(avatarUrl = url) }
                // 服务端接管头像显示：清掉本地旧缓存（登出后回落首字徽章）。
                settingsManager.setAvatarUri(null)
                _effects.send(PersonalProfileEffect.ProfileSynced)
            } catch (e: Exception) {
                _state.update { it.copy(error = "头像上传失败：${e.message}") }
            } finally {
                _state.update { it.copy(uploadingAvatar = false) }
            }
        }
    }

    class Factory(
        private val appContext: Context,
        private val api: ApiService,
        private val tokenManager: TokenManager,
        private val settingsManager: SettingsManager,
        private val billDao: BillDao,
        private val challengeRepository: ChallengeRepository,
        private val budgetRepository: BudgetRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PersonalProfileViewModel(
                appContext, api, tokenManager, settingsManager, billDao, challengeRepository, budgetRepository
            ) as T
        }
    }
}

/** 头像压缩目标边长（px）：正方形显示足够清晰，上传体积可控。 */
private const val AVATAR_MAX_DIM = 512

/** 服务端上传上限（与 AvatarStorage.MAX_BYTES 一致）：压缩产物超限时逐级降质重压。 */
private const val AVATAR_MAX_BYTES = 2 * 1024 * 1024

/**
 * 把取景框裁剪产物压缩成 ≤512px 的 JPEG（质量从 85 逐级降到 40 直到 ≤2MB）。
 * 输出写到应用缓存目录 avatar_upload.jpg，上传后不清理也无妨（下次覆盖）。
 */
internal suspend fun compressAvatar(context: Context, imagePath: String): File? =
    withContext(Dispatchers.IO) {
        try {
            val file = File(imagePath)
            if (!file.exists()) return@withContext null
            val raw = BitmapFactory.decodeFile(imagePath) ?: return@withContext null
            // 等比缩到目标边长（只缩小不放大）
            val scale = AVATAR_MAX_DIM.toFloat() / maxOf(raw.width, raw.height).coerceAtLeast(1)
            val scaled = if (scale < 1f) {
                Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postScale(scale, scale) }, true
                )
            } else {
                raw
            }
            val out = File(context.cacheDir, "avatar_upload.jpg")
            var quality = 85
            do {
                out.outputStream().use { stream -> scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream) }
                quality -= 15
            } while (out.length() > AVATAR_MAX_BYTES && quality >= 40)
            out
        } catch (_: Exception) {
            null
        }
    }
