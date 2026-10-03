package com.example.rinklnote.ui.screen.autocapture

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.BillCapture
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.data.repository.CaptureRepository
import com.example.rinklnote.data.repository.CaptureRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 自动记账页状态机（2026-10-03 方案 C）：权限/开关/模式 + 捕获台账 + 渐进信任提示。
 * 权限（系统「通知使用权」）是 UI 态，由 Screen 在 ON_RESUME 时刷新（[refreshPermission]）。
 */
@Immutable
data class AutoCaptureState(
    /** 系统「通知使用权」是否已授予（UI 态）。 */
    val listenerGranted: Boolean = false,
    /** 总开关（我的页同一 DataStore 键）。 */
    val masterEnabled: Boolean = false,
    /** CONFIRM / SILENT（词表见 SettingsManager）。 */
    val mode: String = SettingsManager.AUTO_CAPTURE_MODE_CONFIRM,
    /** 最近捕获记录（≤50 条）。 */
    val captures: List<BillCapture> = emptyList(),
    /** 渐进信任：是否建议解锁静默模式。 */
    val suggestSilent: Boolean = false,
    /** 渐进信任明细（提示文案用）：累计自动入账笔数 / AUTO 账单保留率。 */
    val trustBookedCount: Int = 0,
    val trustKeptRatio: Int = 0
)

class AutoCaptureViewModel(
    private val captureRepository: CaptureRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val _state = MutableStateFlow(AutoCaptureState())
    val state: StateFlow<AutoCaptureState> = _state.asStateFlow()

    /** 渐进信任重算的触发器（确认/忽略/模式切换后刷新）。 */
    private val trustRefresh = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(
                settingsManager.payNotifyEnabled,
                settingsManager.autoCaptureMode,
                trustRefresh
            ) { master, mode, _ -> master to mode }.collect { (master, mode) ->
                _state.update {
                    it.copy(masterEnabled = master, mode = mode)
                }
                refreshTrust()
            }
        }
        viewModelScope.launch {
            captureRepository.observeRecent().collect { captures ->
                _state.update { it.copy(captures = captures) }
            }
        }
    }

    fun refreshPermission(granted: Boolean) {
        _state.update { it.copy(listenerGranted = granted) }
    }

    fun setMaster(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setPayNotifyEnabled(enabled) }
    }

    fun setMode(mode: String) {
        viewModelScope.launch {
            settingsManager.setAutoCaptureMode(mode)
        }
    }

    fun confirmCapture(captureId: Long) {
        viewModelScope.launch {
            captureRepository.confirmCapture(captureId)
            trustRefresh.update { it + 1 }
        }
    }

    fun ignoreCapture(captureId: Long) {
        viewModelScope.launch {
            captureRepository.ignoreCapture(captureId)
            trustRefresh.update { it + 1 }
        }
    }

    private suspend fun refreshTrust() {
        val stats = captureRepository.trustStats()
        _state.update {
            it.copy(
                suggestSilent = CaptureRepositoryImpl.shouldSuggestSilent(stats),
                trustBookedCount = stats.captureBooked,
                trustKeptRatio = if (stats.autoTotal > 0) stats.autoKept * 100 / stats.autoTotal else 0
            )
        }
    }

    /** 主会话接线用：AutoCaptureViewModel.Factory(app.captureRepository, app.settingsManager)。 */
    class Factory(
        private val captureRepository: CaptureRepository,
        private val settingsManager: SettingsManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AutoCaptureViewModel(captureRepository, settingsManager) as T
    }
}
