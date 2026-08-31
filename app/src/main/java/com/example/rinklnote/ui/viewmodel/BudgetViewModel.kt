package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.getMonthStart
import com.example.rinklnote.util.getNextMonthStart
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class BudgetState(
    val budget: Budget? = null,
    val monthExpense: Double = 0.0,
    val monthStart: Long = getMonthStart(),
    val isLoading: Boolean = false
) {
    val progress: Float
        get() = if (budget != null && budget.amount > 0) (monthExpense / budget.amount).toFloat().coerceIn(0f, 1f) else 0f

    val isOverBudget: Boolean
        get() = budget != null && budget.amount > 0 && monthExpense > budget.amount

    val overBudgetBy: Double
        get() = (monthExpense - (budget?.amount ?: 0.0)).coerceAtLeast(0.0)

    val remainingDays: Int
        get() {
            val today = LocalDate.now(bookkeepingZone())
            return today.lengthOfMonth() - today.dayOfMonth + 1
        }
}

sealed interface BudgetEvent {
    data class SetBudget(val amount: Double) : BudgetEvent
}

class BudgetViewModel(
    private val repository: BillRepository,
    private val syncManager: SyncManager? = null
) : ViewModel() {

    private val _state = MutableStateFlow(BudgetState())
    val state: StateFlow<BudgetState> = _state.asStateFlow()

    init {
        val monthStart = getMonthStart()
        val nextMonthStart = getNextMonthStart()

        // Reactive budget for the current month
        viewModelScope.launch {
            repository.observeBudgets().collect { budgets ->
                val current = budgets.firstOrNull { it.monthStart == monthStart }
                _state.update { it.copy(budget = current, isLoading = false) }
            }
        }

        // Reactive monthly expense — updates as bills are added/edited/deleted
        viewModelScope.launch {
            repository.observeBillsByMonth(monthStart, nextMonthStart).collect { bills ->
                val expense = bills.filter { it.billType == "EXPENSE" }.sumOf { it.amount }
                _state.update { it.copy(monthExpense = expense) }
            }
        }
    }

    fun onEvent(event: BudgetEvent) {
        when (event) {
            is BudgetEvent.SetBudget -> setBudget(event.amount)
        }
    }

    private fun setBudget(amount: Double) {
        val monthStart = getMonthStart()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updated = _state.value.budget?.copy(amount = amount, updatedAt = now, dirty = true)
                ?: Budget(monthStart = monthStart, amount = amount, updatedAt = now, dirty = true)
            repository.upsertBudget(updated)
            syncManager?.let { launch { it.pushBudget(updated) } }
        }
    }

    class Factory(
        private val repository: BillRepository,
        private val syncManager: SyncManager? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BudgetViewModel(repository, syncManager) as T
        }
    }
}
