package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.AnomalyAlert
import com.example.rinklnote.data.network.dto.QueryRequest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class AiState(
    val queryInput: String = "",
    val answer: String? = null,
    val isQuerying: Boolean = false,
    val isLoading: Boolean = false,
    val monthlySummary: String? = null,
    val highlights: List<String> = emptyList(),
    val alerts: List<AnomalyAlert> = emptyList(),
    val error: String? = null
)

sealed interface AiEvent {
    data class QueryInputChanged(val value: String) : AiEvent
    data object SubmitQuery : AiEvent
}

class AiViewModel(private val api: ApiService) : ViewModel() {

    private val _state = MutableStateFlow(AiState())
    val state: StateFlow<AiState> = _state.asStateFlow()

    fun onEvent(event: AiEvent) {
        when (event) {
            is AiEvent.QueryInputChanged -> _state.update { it.copy(queryInput = event.value, error = null) }
            AiEvent.SubmitQuery -> submitQuery()
        }
    }

    /** AppNavigation 在进入 AI 页且已登录时调用；幂等，可重复调用。 */
    fun loadAll() {
        loadMonthly()
        loadAnomaly()
    }

    private fun loadMonthly() {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val month = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM"))
            try {
                val r = api.getMonthlySummary(month)
                _state.update { it.copy(monthlySummary = r.summary, highlights = r.highlights, isLoading = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = friendlyError(e, "加载总结失败")) }
            }
        }
    }

    private fun loadAnomaly() {
        viewModelScope.launch {
            try {
                val r = api.getAnomalyAlerts()
                _state.update { it.copy(alerts = r.alerts, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = friendlyError(e, "加载异常提醒失败")) }
            }
        }
    }

    private fun submitQuery() {
        val text = _state.value.queryInput.trim()
        if (text.isBlank()) return
        _state.update { it.copy(isQuerying = true, answer = null, error = null) }
        viewModelScope.launch {
            try {
                val r = api.queryBillData(QueryRequest(text))
                _state.update { it.copy(answer = r.answer, isQuerying = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(isQuerying = false, error = friendlyError(e, "提问失败")) }
            }
        }
    }

    private fun friendlyError(e: Exception, prefix: String): String {
        val code = (e as? retrofit2.HttpException)?.code()
        return when {
            code == 401 -> "登录已失效，请先在「我的」重新登录"
            code == 400 -> "输入内容不能为空"
            else -> "$prefix: ${e.message}"
        }
    }

    class Factory(private val api: ApiService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiViewModel(api) as T
    }
}
