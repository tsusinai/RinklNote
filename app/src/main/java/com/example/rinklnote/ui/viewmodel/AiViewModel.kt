package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.QueryRequest
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.util.VoiceParser
import com.example.rinklnote.util.bookkeepingZone
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class AiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isWaiting: Boolean = false
)

sealed interface AiEvent {
    data class InputChanged(val value: String) : AiEvent
    data object Send : AiEvent
}

class AiViewModel(
    private val api: ApiService,
    private val repository: BillRepository,
    private val quickAddVM: QuickAddViewModel
) : ViewModel() {

    private val _state = MutableStateFlow(AiState())
    val state: StateFlow<AiState> = _state.asStateFlow()

    // 聊天发起的记账在途标记：FinalConfirmCompleted handler 用 consumeBookingPending() 消费
    private var pendingBooking = false

    init {
        viewModelScope.launch {
            repository.observeChatMessages().collect { messages ->
                _state.update { it.copy(messages = messages) }
            }
        }
    }

    fun onEvent(event: AiEvent) {
        when (event) {
            is AiEvent.InputChanged -> _state.update { it.copy(input = event.value) }
            AiEvent.Send -> send(_state.value.input)
        }
    }

    /** 发送一条消息：清空输入框 → 落库 user 消息 → 路由（记账 or 问账）。 */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _state.value.isWaiting) return
        _state.update { it.copy(input = "") }
        viewModelScope.launch {
            repository.insertChatMessage(
                ChatMessage(role = "user", kind = "text", content = trimmed, createdAt = now())
            )
            route(trimmed)
        }
    }

    /**
     * AppNavigation 每次进入 AI 页时调用。
     * 欢迎语全局一次（kind=greeting）；已登录且未关闭 AI 主动推送时按需注入本月总结（每月一次）、
     * 异常提醒（每天一次）与习惯提醒（每天一次）。
     */
    fun onEnter(isLoggedIn: Boolean, aiDisabled: Boolean = false) {
        viewModelScope.launch {
            if (repository.countChatMessages("greeting", 0L) == 0L) {
                repository.insertChatMessage(
                    ChatMessage(
                        role = "assistant", kind = "greeting",
                        content = "你好呀，我是你的 AI 记账小帮手。直接发「午餐28元」就帮你记一笔；想算账就问我，比如「上个月交通花了多少」；登录后我还会自动帮你捋月结和异常提醒～",
                        createdAt = now()
                    )
                )
            }
            if (isLoggedIn && !aiDisabled) {
                loadMonthlyIfStale()
                loadAnomalyIfStale()
                loadHabitIfStale()
            }
        }
    }

    /** 记账/问账路由。含 元/块/分类 判定，避免把「8月花了多少」误判为记账。 */
    private fun route(text: String) {
        val local = VoiceParser.parse(text)
        val isBooking = local.amount != null &&
            (text.contains("元") || text.contains("块") || local.categoryName != null)
        if (isBooking) {
            pendingBooking = true
            quickAddVM.resetConfirming()
            quickAddVM.onEvent(QuickAddEvent.NlpInput(text))
            quickAddVM.onEvent(QuickAddEvent.NlpSubmit)
            // 记账成功的对话内确认消息由 AppNavigation 的 FinalConfirmCompleted handler 注入
        } else {
            answerQuestion(text)
        }
    }

    /** AppNavigation 在 FinalConfirmCompleted 时调用：true = 本次记账由聊天发起，应插确认消息而非 Toast。 */
    fun consumeBookingPending(): Boolean {
        val wasPending = pendingBooking
        pendingBooking = false
        return wasPending
    }

    /** AppNavigation 在记账确认时调用，插入一条对话内确认消息（kind=booking）。 */
    fun appendBookingConfirmed(text: String) {
        viewModelScope.launch {
            repository.insertChatMessage(
                ChatMessage(role = "assistant", kind = "booking", content = text, createdAt = now())
            )
        }
    }

    private fun answerQuestion(text: String) {
        _state.update { it.copy(isWaiting = true) }
        viewModelScope.launch {
            try {
                val r = api.queryBillData(QueryRequest(text))
                repository.insertChatMessage(
                    ChatMessage(role = "assistant", kind = "text", content = r.answer, createdAt = now())
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.insertChatMessage(
                    ChatMessage(role = "assistant", kind = "text", content = friendlyError(e, "提问失败"), createdAt = now())
                )
            } finally {
                _state.update { it.copy(isWaiting = false) }
            }
        }
    }

    private fun loadMonthlyIfStale() {
        viewModelScope.launch {
            val zone = bookkeepingZone()
            val monthStart = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (repository.countChatMessages("summary", monthStart) > 0) return@launch
            val month = LocalDate.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM"))
            try {
                val r = api.getMonthlyReview(month)
                val content = buildString {
                    append("这个月收支给你捋一捋👇\n")
                    append(r.summary)
                    r.highlights.forEach { append("\n• ").append(it) }
                    // 并入异常事实：超标日 / 最大单笔 / 消费集中
                    if (r.spikeDays.isNotEmpty()) {
                        append("\n⚠️ 超标日")
                        r.spikeDays.take(5).forEach {
                            append("\n- ").append(it.date).append(" ¥").append("%.2f".format(it.amount))
                                .append("（超日均").append(it.ratioPct).append("%）")
                        }
                    }
                    r.biggestSingle?.let {
                        append("\n🔍 最大单笔：").append(it.categoryName).append(" ¥")
                            .append("%.2f".format(it.amount)).append("（").append(it.date).append("）")
                    }
                    if (r.topCategories.isNotEmpty()) {
                        append("\n🧾 消费集中：")
                        append(r.topCategories.joinToString("、") { it.name + " ¥" + "%.2f".format(it.amount) })
                    }
                }
                repository.insertChatMessage(
                    ChatMessage(role = "assistant", kind = "summary", content = content, createdAt = now())
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.insertChatMessage(
                    ChatMessage(role = "assistant", kind = "text", content = friendlyError(e, "加载总结失败"), createdAt = now())
                )
            }
        }
    }

    private fun loadAnomalyIfStale() {
        viewModelScope.launch {
            val zone = bookkeepingZone()
            val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            if (repository.countChatMessages("anomaly", dayStart) > 0) return@launch
            try {
                val r = api.getAnomalyAlerts()
                if (r.alerts.isEmpty()) return@launch   // 无异常不插「暂无」，不占去重位
                val content = buildString {
                    append("发现点小动静，提你一下～\n")
                    append(r.alerts.joinToString("\n") { "• " + it.message })
                }
                repository.insertChatMessage(
                    ChatMessage(role = "assistant", kind = "anomaly", content = content, createdAt = now())
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.insertChatMessage(
                    ChatMessage(role = "assistant", kind = "text", content = friendlyError(e, "加载异常提醒失败"), createdAt = now())
                )
            }
        }
    }

    /** 习惯提醒：每天一次；无内容/失败静默（soft 提醒，不打扰）。 */
    private fun loadHabitIfStale() {
        viewModelScope.launch {
            val zone = bookkeepingZone()
            val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            if (repository.countChatMessages("habit", dayStart) > 0) return@launch
            try {
                val r = api.getHabit()
                val content = r.content
                if (content.isNullOrBlank()) return@launch   // 今日无习惯 → 不插，不占去重位
                repository.insertChatMessage(
                    ChatMessage(role = "assistant", kind = "habit", content = content, createdAt = now())
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 静默：习惯提醒加载失败不打扰用户，下次进入重试
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

    private fun now(): Long = System.currentTimeMillis()

    class Factory(
        private val api: ApiService,
        private val repository: BillRepository,
        private val quickAddVM: QuickAddViewModel
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiViewModel(api, repository, quickAddVM) as T
    }
}
