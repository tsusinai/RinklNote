package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.AiDisabledRequest
import com.example.rinklnote.data.network.dto.BotBindCodeRequest
import com.example.rinklnote.data.network.dto.BotBindStatusResponse
import com.example.rinklnote.data.network.dto.ChangePasswordRequest
import com.example.rinklnote.data.network.dto.DailyReportSettingDto
import com.example.rinklnote.data.network.dto.LoginRequest
import com.example.rinklnote.domain.parseShowcaseBadges
import com.example.rinklnote.util.PasswordRules
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 机器人通道（Phase D 三通道收敛）：QQ / 飞书 / 企业微信。
 * 绑定流程同构——在对应 App 里向机器人发「登录」拿 6 位绑定码，App 端输码调
 * /api/{channel}-bot/bind 完成绑定；解绑走 /api/{channel}-bot/unbind。
 */
enum class BotChannel(val label: String, val guideApp: String) {
    QQ("QQ", "QQ"),
    FEISHU("飞书", "飞书"),
    WECOM("企业微信", "企业微信")
}

/** 单通道绑定状态（服务端 bind-status 的回显；本地不持久化，一律以服务端为准）。 */
@androidx.compose.runtime.Immutable
data class BotBinding(val bound: Boolean = false, val maskedId: String = "")

/**
 * 登录身份模式（2026-09-17 优化登录方式）：手机号 | 邮箱 同级切换。
 * 邮箱模式下 phone 字段传空串、email 非空，服务端据此选择身份校验。
 */
enum class AuthMode { PHONE, EMAIL }

@androidx.compose.runtime.Immutable
data class AuthState(
    val isLoggedIn: Boolean = false,
    val phone: String = "",
    // 邮箱登录（2026-09-17）：与 phone 同级的身份输入；authMode 决定登录/注册用哪个身份。
    val email: String = "",
    val authMode: AuthMode = AuthMode.PHONE,
    val password: String = "",
    val accountPhone: String = "",
    val createdAt: String = "",
    /** QQ 官方 openid 绑定（/api/auth/me 只读回显，供「QQ 机器人引导」弹窗展示）。 */
    val botBound: Boolean = false,
    /** 三通道绑定状态（/api/{channel}-bot/bind-status），key 缺省视为未绑定。 */
    val bindings: Map<BotChannel, BotBinding> = emptyMap(),
    // ── 个人资料（2026-09-17）：/api/auth/me 回显，服务端为唯一可信源；旧服务端全为 null ──
    val nickname: String? = null,
    val signature: String? = null,
    val birthday: String? = null,
    /** 头像相对 URL（拼 RetrofitClient.BASE_URL 加载）；未上传为 null。 */
    val avatarUrl: String? = null,
    /** 展示徽章 key 列表（服务端选择 ∩ 实时解锁态后的展示在 UI 层做交集）。 */
    val showcaseBadges: List<String> = emptyList(),
    val oldPassword: String = "",
    val newPassword: String = "",
    val aiDisabled: Boolean = false,
    // 绑定页：当前选中通道 + 6 位绑定码
    val bindChannel: BotChannel = BotChannel.QQ,
    val bindCode: String = "",
    /** 绑定成功后短暂置为该通道，绑定页据此自动关闭（一次性副作用）。 */
    val bindSucceeded: BotChannel? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
) {
    /** 读取某通道绑定状态（未拉取到时视为未绑定）。 */
    fun bindingOf(channel: BotChannel): BotBinding = bindings[channel] ?: BotBinding()
}

sealed interface AuthEvent {
    data class PhoneChanged(val value: String) : AuthEvent
    data class EmailChanged(val value: String) : AuthEvent
    /** 手机号 | 邮箱 身份切换（清错误提示，保留两个输入框各自的已输内容）。 */
    data class AuthModeChanged(val mode: AuthMode) : AuthEvent
    data class PasswordChanged(val value: String) : AuthEvent
    data class OldPasswordChanged(val value: String) : AuthEvent
    data class NewPasswordChanged(val value: String) : AuthEvent
    data object Login : AuthEvent
    data object Register : AuthEvent
    data object FetchProfile : AuthEvent
    data object ChangePassword : AuthEvent
    data object Logout : AuthEvent
    // 机器人绑定（三通道同构）
    data class BindChannelSelected(val channel: BotChannel) : AuthEvent
    data class BindCodeChanged(val value: String) : AuthEvent
    data object SubmitBind : AuthEvent
    data class UnbindBot(val channel: BotChannel) : AuthEvent
    data class SetAiDisabled(val disabled: Boolean) : AuthEvent
    /** QQ 端日报推送设置同步到服务端（本地 DataStore 由 ProfileScreen 自己写）。 */
    data class SetDailyReportQq(val enabled: Boolean, val hour: Int, val minute: Int) : AuthEvent
    data object ClearError : AuthEvent
    data object ClearSuccess : AuthEvent
}

class AuthViewModel(
    private val api: ApiService,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val loggedIn = tokenManager.isLoggedIn()
            // 旧版「QQ 号本地缓存」只清理不迁移：绑定状态一律以服务端 bind-status 为准
            tokenManager.clearLegacyQQCache()
            _state.update { it.copy(isLoggedIn = loggedIn) }
            if (loggedIn) fetchProfile()
        }
    }

    fun onEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.PhoneChanged -> _state.update { it.copy(phone = event.value, error = null) }
            is AuthEvent.EmailChanged -> _state.update { it.copy(email = event.value, error = null) }
            is AuthEvent.AuthModeChanged -> _state.update { it.copy(authMode = event.mode, error = null) }
            is AuthEvent.PasswordChanged -> _state.update { it.copy(password = event.value, error = null) }
            is AuthEvent.OldPasswordChanged -> _state.update { it.copy(oldPassword = event.value, error = null) }
            is AuthEvent.NewPasswordChanged -> _state.update { it.copy(newPassword = event.value, error = null) }
            is AuthEvent.ClearError -> _state.update { it.copy(error = null) }
            is AuthEvent.ClearSuccess -> _state.update { it.copy(successMessage = null) }
            is AuthEvent.Login -> login()
            is AuthEvent.Register -> register()
            is AuthEvent.FetchProfile -> fetchProfile()
            is AuthEvent.ChangePassword -> changePassword()
            is AuthEvent.Logout -> logout()
            is AuthEvent.BindChannelSelected -> _state.update {
                it.copy(bindChannel = event.channel, bindCode = "", error = null, successMessage = null)
            }
            is AuthEvent.BindCodeChanged -> _state.update { it.copy(bindCode = event.value, error = null) }
            is AuthEvent.SubmitBind -> submitBind()
            is AuthEvent.UnbindBot -> unbindBot(event.channel)
            is AuthEvent.SetAiDisabled -> setAiDisabled(event.disabled)
            is AuthEvent.SetDailyReportQq -> setDailyReportQq(event.enabled, event.hour, event.minute)
        }
    }

    private fun login() {
        val s = _state.value
        // 邮箱模式：只校验邮箱非空（格式由登录页 UI 层校验，与手机号同分工）；手机号模式保持旧文案。
        if (s.authMode == AuthMode.EMAIL) {
            if (s.email.isBlank()) {
                _state.update { it.copy(error = "邮箱或密码不能为空") }
                return
            }
        } else if (s.phone.isBlank()) {
            _state.update { it.copy(error = "手机号或密码不能为空") }
            return
        }
        if (s.password.isBlank()) {
            _state.update { it.copy(error = if (s.authMode == AuthMode.EMAIL) "邮箱或密码不能为空" else "手机号或密码不能为空") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val response = api.login(s.toLoginRequest())
                tokenManager.saveAuth(response.token, response.userId)
                _state.update { it.copy(isLoggedIn = true, isLoading = false, error = null) }
                fetchProfile()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "登录失败: ${e.message}") }
            }
        }
    }

    private fun register() {
        val s = _state.value
        if (s.authMode == AuthMode.EMAIL) {
            if (s.email.isBlank()) {
                _state.update { it.copy(error = "邮箱或密码不能为空") }
                return
            }
        } else if (s.phone.isBlank()) {
            _state.update { it.copy(error = "手机号或密码不能为空") }
            return
        }
        if (s.password.isBlank()) {
            _state.update { it.copy(error = if (s.authMode == AuthMode.EMAIL) "邮箱或密码不能为空" else "手机号或密码不能为空") }
            return
        }
        // 密码规则（2026-09-17）：≥6 位且同时含大小写字母（与服务端 PasswordPolicy 同构），
        // 提交前本地拦截避免无效请求；存量老密码不受影响，只约束新设定。
        PasswordRules.validate(s.password)?.let {
            _state.update { st -> st.copy(error = it) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val response = api.register(s.toLoginRequest())
                tokenManager.saveAuth(response.token, response.userId)
                _state.update { it.copy(isLoggedIn = true, isLoading = false, error = null) }
                fetchProfile()
            } catch (e: Exception) {
                // Server rejects re-registering an existing identity with 409 — tell the
                // user the account exists and to log in instead of a raw HTTP code.
                val identityDuplicated = if (s.authMode == AuthMode.EMAIL) "该邮箱已注册，请直接登录" else "该手机号已注册，请直接登录"
                val msg = if (e is retrofit2.HttpException && e.code() == 409) identityDuplicated
                else "注册失败: ${e.message}"
                _state.update { it.copy(isLoading = false, error = msg) }
            }
        }
    }

    /** 当前身份模式 → LoginRequest：邮箱模式 phone 传空串、email 非空（服务端据此选身份）。 */
    private fun AuthState.toLoginRequest(): LoginRequest = if (authMode == AuthMode.EMAIL) {
        LoginRequest(phone = "", password = password, email = email.trim())
    } else {
        LoginRequest(phone = phone, password = password)
    }

    /** 提交绑定码：按当前选中通道调 /api/{channel}-bot/bind（服务端消费一次性码完成绑定）。 */
    private fun submitBind() {
        val s = _state.value
        val code = s.bindCode.trim()
        if (code.length != 6 || code.any { !it.isDigit() }) {
            _state.update { it.copy(error = "请输入 6 位数字绑定码") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, successMessage = null) }
            try {
                val msg = when (s.bindChannel) {
                    BotChannel.QQ -> api.bindQQBot(BotBindCodeRequest(code)).message
                    BotChannel.FEISHU -> api.bindFeishuBot(BotBindCodeRequest(code)).message
                    BotChannel.WECOM -> api.bindWecomBot(BotBindCodeRequest(code)).message
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        successMessage = msg ?: "${s.bindChannel.label}绑定成功",
                        bindCode = "",
                        bindSucceeded = s.bindChannel,
                        bindings = it.bindings + (s.bindChannel to BotBinding(bound = true))
                    )
                }
                fetchProfile()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "绑定失败: ${e.message}") }
            }
        }
    }

    /** 解绑指定通道：调 /api/{channel}-bot/unbind（服务端直接清身份列，幂等）。 */
    private fun unbindBot(channel: BotChannel) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, successMessage = null) }
            try {
                val msg = when (channel) {
                    BotChannel.QQ -> api.unbindQQBot().message
                    BotChannel.FEISHU -> api.unbindFeishuBot().message
                    BotChannel.WECOM -> api.unbindWecomBot().message
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        successMessage = msg ?: "已解绑${channel.label}",
                        bindings = it.bindings + (channel to BotBinding(bound = false))
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "解绑失败: ${e.message}") }
            }
        }
    }

    private fun fetchProfile() {
        viewModelScope.launch {
            try {
                val me = api.getMe()
                _state.update {
                    it.copy(
                        // 纯邮箱 / QQ openid 用户无手机号 → 回落空串（展示层自判「未绑定」）
                        accountPhone = me.phone ?: "",
                        createdAt = me.createdAt ?: "",
                        botBound = !me.qqOpenid.isNullOrBlank(),
                        aiDisabled = me.aiDisabled,
                        isLoggedIn = true,
                        // 个人资料回显：服务端为唯一可信源，旧服务端不下发时保持 null/空
                        nickname = me.nickname,
                        signature = me.signature,
                        birthday = me.birthday,
                        avatarUrl = me.avatarUrl,
                        showcaseBadges = parseShowcaseBadges(me.showcaseBadges)
                    )
                }
                refreshBotBindings()
            } catch (e: Exception) {
                // Token invalid/expired → drop back to logged-out state.
                tokenManager.clearAuth()
                _state.update {
                    it.copy(
                        isLoggedIn = false, botBound = false, bindings = emptyMap(), error = null,
                        nickname = null, signature = null, birthday = null, avatarUrl = null,
                        showcaseBadges = emptyList()
                    )
                }
            }
        }
    }

    /** 并行拉三通道 bind-status；单通道失败（如服务端未部署该路由）不阻塞其余通道。 */
    private fun refreshBotBindings() {
        viewModelScope.launch {
            val updated = mutableMapOf<BotChannel, BotBinding>()
            suspend fun fetch(channel: BotChannel, call: suspend () -> BotBindStatusResponse) {
                try {
                    val r = call()
                    updated[channel] = BotBinding(bound = r.isBound, maskedId = r.maskedId)
                } catch (_: Exception) {
                    // 查询失败保留该通道旧值，避免网络抖动把「已绑定」闪成「未绑定」
                }
            }
            coroutineScope {
                launch { fetch(BotChannel.QQ) { api.qqBotBindStatus() } }
                launch { fetch(BotChannel.FEISHU) { api.feishuBotBindStatus() } }
                launch { fetch(BotChannel.WECOM) { api.wecomBotBindStatus() } }
            }
            _state.update { it.copy(bindings = it.bindings + updated) }
        }
    }

    private fun changePassword() {
        val s = _state.value
        if (s.oldPassword.isBlank() || s.newPassword.isBlank()) {
            _state.update { it.copy(error = "请填写原密码和新密码") }
            return
        }
        // 新密码规则与注册一致（≥6 位 + 大小写字母，2026-09-17），本地预校验避免无效请求；
        // 老密码不受影响，只约束本次新设定。
        PasswordRules.validate(s.newPassword)?.let {
            _state.update { st -> st.copy(error = it) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, successMessage = null) }
            try {
                api.changePassword(ChangePasswordRequest(s.oldPassword, s.newPassword))
                _state.update {
                    it.copy(isLoading = false, oldPassword = "", newPassword = "", successMessage = "密码修改成功")
                }
            } catch (e: Exception) {
                val msg = if (e is retrofit2.HttpException && e.code() == 401) "原密码错误" else "修改失败: ${e.message}"
                _state.update { it.copy(isLoading = false, error = msg) }
            }
        }
    }

    private fun setAiDisabled(disabled: Boolean) {
        viewModelScope.launch {
            try {
                api.setAiDisabled(AiDisabledRequest(disabled))
                _state.update { it.copy(aiDisabled = disabled) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "设置失败: ${e.message}") }
            }
        }
    }

    private fun setDailyReportQq(enabled: Boolean, hour: Int, minute: Int) {
        viewModelScope.launch {
            try {
                api.setDailyReportSetting(DailyReportSettingDto(enabled, hour, minute))
            } catch (e: Exception) {
                _state.update { it.copy(error = "日报设置同步失败: ${e.message}") }
            }
        }
    }

    private fun logout() {
        viewModelScope.launch {
            tokenManager.clearAuth()
            _state.update { AuthState() }
        }
    }

    class Factory(
        private val api: ApiService,
        private val tokenManager: TokenManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuthViewModel(api, tokenManager) as T
        }
    }
}
