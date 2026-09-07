package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.AiGenerateTokenRequest
import com.example.rinklnote.data.network.dto.AiTokenItem
import com.example.rinklnote.data.network.dto.AiTokenResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class AiTokenState(
    val tokens: List<AiTokenItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 刚生成的令牌纯文本（仅展示一次，关闭对话框即清空）。 */
    val generatedToken: AiTokenResponse? = null,
    val newTokenName: String = "小爱"
)

sealed interface AiTokenEvent {
    data object Load : AiTokenEvent
    data class NewTokenNameChanged(val name: String) : AiTokenEvent
    data object Generate : AiTokenEvent
    data class Revoke(val id: Long) : AiTokenEvent
    data object RevokeAll : AiTokenEvent
    data object DismissGenerated : AiTokenEvent
    data object DismissError : AiTokenEvent
}

class AiTokenViewModel(
    private val api: ApiService
) : ViewModel() {

    private val _state = MutableStateFlow(AiTokenState())
    val state: StateFlow<AiTokenState> = _state.asStateFlow()

    init { loadTokens() }

    fun onEvent(event: AiTokenEvent) {
        when (event) {
            is AiTokenEvent.Load -> loadTokens()
            is AiTokenEvent.NewTokenNameChanged -> _state.update { it.copy(newTokenName = event.name) }
            is AiTokenEvent.Generate -> generate()
            is AiTokenEvent.Revoke -> revoke(event.id)
            is AiTokenEvent.RevokeAll -> revokeAll()
            is AiTokenEvent.DismissGenerated -> _state.update { it.copy(generatedToken = null) }
            is AiTokenEvent.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    private fun loadTokens() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val tokens = api.listAiTokens()
                _state.update { it.copy(tokens = tokens, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "加载失败：${e.message}") }
            }
        }
    }

    private fun generate() {
        val name = _state.value.newTokenName.trim().ifBlank { "小爱" }.take(60)
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val response = api.generateAiToken(AiGenerateTokenRequest(name))
                _state.update {
                    it.copy(
                        generatedToken = response,
                        loading = false,
                        newTokenName = "小爱"
                    )
                }
                loadTokens() // refresh list
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "生成失败：${e.message}") }
            }
        }
    }

    private fun revoke(id: Long) {
        viewModelScope.launch {
            try {
                api.revokeAiToken(id)
                loadTokens()
            } catch (e: Exception) {
                _state.update { it.copy(error = "作废失败：${e.message}") }
            }
        }
    }

    private fun revokeAll() {
        viewModelScope.launch {
            try {
                api.revokeAllAiTokens()
                loadTokens()
            } catch (e: Exception) {
                _state.update { it.copy(error = "操作失败：${e.message}") }
            }
        }
    }

    class Factory(private val api: ApiService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AiTokenViewModel(api) as T
        }
    }
}
