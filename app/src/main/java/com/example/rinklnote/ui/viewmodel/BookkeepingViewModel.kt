package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.util.getMonthStart
import com.example.rinklnote.util.getNextMonthStart
import kotlinx.coroutines.Job
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
    val currentDate: Long = System.currentTimeMillis(),
    // Offset of the month currently shown: 0 = current month, -1 = previous.
    // Drives the main list, the chart and the totals together.
    val selectedMonthOffset: Int = 0,
    val editingBill: Bill? = null,
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList()
) {
    val categories: List<Category>
        get() = if (editingBill?.billType == "INCOME") incomeCategories else expenseCategories
}

sealed interface BookkeepingEvent {
    data object Refresh : BookkeepingEvent
    data class EditBill(val bill: Bill) : BookkeepingEvent
    data object CancelEdit : BookkeepingEvent
    data class ConfirmEdit(val bill: Bill) : BookkeepingEvent
    data class DeleteBill(val bill: Bill) : BookkeepingEvent
    data class SelectMonth(val offset: Int) : BookkeepingEvent
}

class BookkeepingViewModel(
    private val repository: BillRepository,
    private val syncManager: SyncManager? = null
) : ViewModel() {

    private val _state = MutableStateFlow(BookkeepingState())
    val state: StateFlow<BookkeepingState> = _state.asStateFlow()

    private var billCollectorJob: Job? = null

    init {
        // Single long-lived bill collector — never leaks
        collectBills()
        refreshTotals()

        // Reference data for the edit overlay
        viewModelScope.launch {
            repository.expenseCategories.collect { cats ->
                _state.update { it.copy(expenseCategories = cats) }
            }
        }
        viewModelScope.launch {
            repository.incomeCategories.collect { cats ->
                _state.update { it.copy(incomeCategories = cats) }
            }
        }
        viewModelScope.launch {
            repository.accounts.collect { accts ->
                _state.update { it.copy(accounts = accts) }
            }
        }
    }

    fun onEvent(event: BookkeepingEvent) {
        when (event) {
            is BookkeepingEvent.Refresh -> refreshTotals()
            is BookkeepingEvent.EditBill -> _state.update { it.copy(editingBill = event.bill) }
            is BookkeepingEvent.CancelEdit -> _state.update { it.copy(editingBill = null) }
            is BookkeepingEvent.ConfirmEdit -> confirmEdit(event.bill)
            is BookkeepingEvent.DeleteBill -> deleteBill(event.bill)
            is BookkeepingEvent.SelectMonth -> selectMonth(event.offset)
        }
    }

    private fun collectBills() {
        billCollectorJob?.cancel()
        val offset = _state.value.selectedMonthOffset
        billCollectorJob = viewModelScope.launch {
            val monthStart = getMonthStart(offset)
            val nextMonthStart = getNextMonthStart(offset)
            repository.observeBillsByMonth(monthStart, nextMonthStart).collect { bills ->
                _state.update { it.copy(bills = bills, isLoading = false) }
            }
        }
    }

    /** Switches every month-scoped view (list/chart/totals) to a different month. */
    fun selectMonth(offset: Int) {
        if (_state.value.selectedMonthOffset == offset) return
        _state.update { it.copy(selectedMonthOffset = offset) }
        collectBills()
        refreshTotals()
    }

    private fun refreshTotals() {
        viewModelScope.launch {
            val offset = _state.value.selectedMonthOffset
            _state.update { it.copy(isLoading = true) }
            val monthStart = getMonthStart(offset)
            val nextMonthStart = getNextMonthStart(offset)
            val expense = repository.getTotalExpense(monthStart, nextMonthStart)
            val income = repository.getTotalIncome(monthStart, nextMonthStart)
            _state.update { it.copy(totalExpense = expense, totalIncome = income, isLoading = false) }
        }
    }

    private fun confirmEdit(bill: Bill) {
        val dirtyBill = bill.copy(dirty = true, updatedAt = System.currentTimeMillis())
        viewModelScope.launch {
            repository.updateBill(dirtyBill)
            syncManager?.let { launch { it.pushBill(dirtyBill) } }
            _state.update { it.copy(editingBill = null) }
        }
    }

    private fun deleteBill(bill: Bill) {
        viewModelScope.launch {
            repository.deleteBill(bill) // soft delete locally (dirty=1, deleted=1)
            syncManager?.let { launch { it.pushBill(bill.copy(deleted = true, dirty = true)) } }
        }
    }

    class Factory(
        private val repository: BillRepository,
        private val syncManager: SyncManager? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookkeepingViewModel(repository, syncManager) as T
        }
    }
}
