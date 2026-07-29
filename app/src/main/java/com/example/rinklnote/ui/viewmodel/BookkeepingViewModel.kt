package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.util.getMonthStart
import com.example.rinklnote.util.getNextMonthStart
import kotlinx.coroutines.Job
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class BookkeepingState(
    val bills: List<Bill> = emptyList(),
    val totalExpense: Double = 0.0,
    val totalIncome: Double = 0.0,
    val isLoading: Boolean = false,
    val currentDate: Long = System.currentTimeMillis()
)

sealed interface BookkeepingEvent {
    data object Refresh : BookkeepingEvent
}

class BookkeepingViewModel(
    private val repository: BillRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BookkeepingState())
    val state: StateFlow<BookkeepingState> = _state.asStateFlow()

    private var billCollectorJob: Job? = null

    init {
        // Single long-lived bill collector — never leaks
        collectBills()
        refreshTotals()
    }

    fun onEvent(event: BookkeepingEvent) {
        when (event) {
            is BookkeepingEvent.Refresh -> refreshTotals()
        }
    }

    private fun collectBills() {
        billCollectorJob?.cancel()
        billCollectorJob = viewModelScope.launch {
            val startDate = LocalDate.now().minusDays(10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val nextMonthStart = getNextMonthStart()
            repository.observeBillsByMonth(startDate, nextMonthStart).collect { bills ->
                _state.update { it.copy(bills = bills, isLoading = false) }
            }
        }
    }

    private fun refreshTotals() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val monthStart = getMonthStart()
            val nextMonthStart = getNextMonthStart()
            val expense = repository.getTotalExpense(monthStart, nextMonthStart)
            val income = repository.getTotalIncome(monthStart, nextMonthStart)
            _state.update { it.copy(totalExpense = expense, totalIncome = income) }
        }
    }

    class Factory(private val repository: BillRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookkeepingViewModel(repository) as T
        }
    }
}
