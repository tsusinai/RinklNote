package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.local.TokenManager
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.BindQQRequest
import com.example.rinklnote.data.network.dto.ChangePasswordRequest
import com.example.rinklnote.data.network.dto.LoginRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class AuthState(
    val isLoggedIn: Boolean = false,
    val isQQBound: Boolean = false,
    val phone: String = "",
    val password: String = "",
    val qqNumber: String = "",
    val accountPhone: String = "",
    val createdAt: String = "",
    val botBound: Boolean = false,
    val oldPassword: String = "",
    val newPassword: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

sealed interface AuthEvent {
    data class PhoneChanged(val value: String) : AuthEvent
    data class PasswordChanged(val value: String) : AuthEvent
    data class QQNumberChanged(val value: String) : AuthEvent
    data class OldPasswordChanged(val value: String) : AuthEvent
    data class NewPasswordChanged(val value: String) : AuthEvent
    data object Login : AuthEvent
    data object Register : AuthEvent
    data object BindQQ : AuthEvent
    data object FetchProfile : AuthEvent
    data object ChangePassword : AuthEvent
    data object UnbindQQ : AuthEvent
    data object Logout : AuthEvent
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
            _state.update {
                it.copy(
                    isLoggedIn = loggedIn,
                    isQQBound = tokenManager.isQQBound()
                )
            }
            if (loggedIn) fetchProfile()
        }
    }

    fun onEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.PhoneChanged -> _state.update { it.copy(phone = event.value, error = null) }
            is AuthEvent.PasswordChanged -> _state.update { it.copy(password = event.value, error = null) }
            is AuthEvent.QQNumberChanged -> _state.update { it.copy(qqNumber = event.value, error = null) }
            is AuthEvent.OldPasswordChanged -> _state.update { it.copy(oldPassword = event.value, error = null) }
            is AuthEvent.NewPasswordChanged -> _state.update { it.copy(newPassword = event.value, error = null) }
            is AuthEvent.ClearError -> _state.update { it.copy(error = null) }
            is AuthEvent.ClearSuccess -> _state.update { it.copy(successMessage = null) }
            is AuthEvent.Login -> login()
            is AuthEvent.Register -> register()
            is AuthEvent.BindQQ -> bindQQ()
            is AuthEvent.FetchProfile -> fetchProfile()
            is AuthEvent.ChangePassword -> changePassword()
            is AuthEvent.UnbindQQ -> unbindQQ()
            is AuthEvent.Logout -> logout()
        }
    }

    private fun login() {
        val s = _state.value
        if (s.phone.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "手机号或密码不能为空") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val response = api.login(LoginRequest(s.phone, s.password))
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
        if (s.phone.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "手机号或密码不能为空") }
            return
        }
        if (s.password.length < 6) {
            _state.update { it.copy(error = "密码长度至少6位") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val response = api.register(LoginRequest(s.phone, s.password))
                tokenManager.saveAuth(response.token, response.userId)
                _state.update { it.copy(isLoggedIn = true, isLoading = false, error = null) }
                fetchProfile()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "注册失败: ${e.message}") }
            }
        }
    }

    private fun bindQQ() {
        val s = _state.value
        if (s.qqNumber.isBlank()) {
            _state.update { it.copy(error = "请输入QQ号") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                api.bindQQ(BindQQRequest(s.qqNumber))
                tokenManager.saveQQ(s.qqNumber)
                _state.update { it.copy(isQQBound = true, isLoading = false, successMessage = "QQ绑定成功") }
                fetchProfile()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "绑定失败: ${e.message}") }
            }
        }
    }

    private fun fetchProfile() {
        viewModelScope.launch {
            try {
                val me = api.getMe()
                _state.update {
                    it.copy(
                        accountPhone = me.phone,
                        qqNumber = me.qqNumber ?: "",
                        createdAt = me.createdAt ?: "",
                        botBound = !me.qqOpenid.isNullOrBlank(),
                        isQQBound = !me.qqNumber.isNullOrBlank(),
                        isLoggedIn = true
                    )
                }
            } catch (e: Exception) {
                // Token invalid/expired → drop back to logged-out state.
                tokenManager.clearAuth()
                _state.update { it.copy(isLoggedIn = false, isQQBound = false, error = null) }
            }
        }
    }

    private fun changePassword() {
        val s = _state.value
        if (s.oldPassword.isBlank() || s.newPassword.isBlank()) {
            _state.update { it.copy(error = "请填写原密码和新密码") }
            return
        }
        if (s.newPassword.length < 6) {
            _state.update { it.copy(error = "新密码长度至少6位") }
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

    private fun unbindQQ() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, successMessage = null) }
            try {
                api.unbindQQ()
                tokenManager.clearQQ()
                _state.update { it.copy(isLoading = false, isQQBound = false, qqNumber = "", successMessage = "QQ号已解绑") }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "解绑失败: ${e.message}") }
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
