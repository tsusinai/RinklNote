package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.repository.BillRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class QuickAddState(
    val amount: String = "",
    val billType: String = "EXPENSE",
    val selectedCategory: Category? = null,
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList(),
    val subCategories: List<SubCategory> = emptyList(),
    val selectedSubCategory: SubCategory? = null,
    val selectedAccount: Account? = null,
    val accounts: List<Account> = emptyList(),
    val remark: String = "",
    val showSubCategories: Boolean = false,
    val isConfirmEnabled: Boolean = false,
    val confirmed: Boolean = false
) {
    val categories: List<Category>
        get() = if (billType == "EXPENSE") expenseCategories else incomeCategories
}

sealed interface QuickAddEvent {
    data class Digit(val digit: String) : QuickAddEvent
    data object Clear : QuickAddEvent
    data object Backspace : QuickAddEvent
    data object ToggleType : QuickAddEvent
    data class SelectCategory(val category: Category) : QuickAddEvent
    data class LongPressCategory(val category: Category) : QuickAddEvent
    data class SelectSubCategory(val subCategory: SubCategory?) : QuickAddEvent
    data class SelectAccount(val account: Account) : QuickAddEvent
    data class RemarkChanged(val remark: String) : QuickAddEvent
    data object DismissSubCategories : QuickAddEvent
    data object Confirm : QuickAddEvent
}

sealed interface QuickAddEffect {
    data object ConfirmRequested : QuickAddEffect
    data object FinalConfirmCompleted : QuickAddEffect
}

class QuickAddViewModel(
    private val repository: BillRepository
) : ViewModel() {

    private val _state = MutableStateFlow(QuickAddState())
    val state: StateFlow<QuickAddState> = _state.asStateFlow()

    private val _effects = Channel<QuickAddEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        // Collect from cached repository StateFlows — no Room reactive Flow
        viewModelScope.launch {
            repository.expenseCategories.collect { cats ->
                _state.update {
                    it.copy(
                        expenseCategories = cats,
                        selectedCategory = it.selectedCategory ?: cats.firstOrNull()
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.incomeCategories.collect { cats ->
                _state.update { it.copy(incomeCategories = cats) }
            }
        }
        viewModelScope.launch {
            repository.accounts.collect { accounts ->
                _state.update {
                    it.copy(accounts = accounts, selectedAccount = accounts.firstOrNull())
                }
            }
        }
    }

    fun onEvent(event: QuickAddEvent) {
        when (event) {
            is QuickAddEvent.Digit -> onDigit(event.digit)
            is QuickAddEvent.Clear -> _state.update { it.copy(amount = "") }
            is QuickAddEvent.Backspace -> onBackspace()
            is QuickAddEvent.ToggleType -> toggleType()
            is QuickAddEvent.SelectCategory -> selectCategory(event.category)
            is QuickAddEvent.LongPressCategory -> showSubCategories(event.category)
            is QuickAddEvent.SelectSubCategory -> selectSubCategory(event.subCategory)
            is QuickAddEvent.SelectAccount -> _state.update { it.copy(selectedAccount = event.account) }
            is QuickAddEvent.RemarkChanged -> _state.update { it.copy(remark = event.remark) }
            is QuickAddEvent.DismissSubCategories -> dismissSubCategories()
            is QuickAddEvent.Confirm -> confirm()
        }
    }

    private fun onDigit(digit: String) {
        _state.update { current ->
            if (current.amount.contains(".") && digit == ".") return
            val newAmount = current.amount + digit
            current.copy(amount = newAmount, isConfirmEnabled = newAmount.toDoubleOrNull() != null && (newAmount.toDoubleOrNull() ?: 0.0) > 0)
        }
    }

    private fun onBackspace() {
        _state.update { current ->
            val newAmount = current.amount.dropLast(1)
            current.copy(amount = newAmount, isConfirmEnabled = newAmount.toDoubleOrNull()?.let { it > 0 } ?: false)
        }
    }

    private fun toggleType() {
        _state.update { current ->
            val newType = if (current.billType == "EXPENSE") "INCOME" else "EXPENSE"
            current.copy(billType = newType, selectedCategory = null, selectedSubCategory = null)
        }
    }

    private fun selectCategory(category: Category) {
        _state.update { it.copy(selectedCategory = category, selectedSubCategory = null, showSubCategories = false) }
    }

    private fun showSubCategories(category: Category) {
        _state.update { it.copy(showSubCategories = true) }
        viewModelScope.launch {
            val subs = repository.getSubCategories(category.id)
            _state.update { it.copy(subCategories = subs) }
        }
    }

    private fun selectSubCategory(subCategory: SubCategory?) {
        _state.update { it.copy(selectedSubCategory = subCategory, showSubCategories = false) }
    }

    private fun dismissSubCategories() {
        _state.update { it.copy(showSubCategories = false, subCategories = emptyList()) }
    }

    private fun confirm() {
        // Only mark confirmed — shows CountAfter, bill NOT saved yet
        val s = _state.value
        if (s.amount.toDoubleOrNull() == null) return
        if (s.selectedCategory == null) return
        if (s.selectedAccount == null) return
        _state.update { it.copy(confirmed = true) }
        _effects.trySend(QuickAddEffect.ConfirmRequested)
    }

    private var confirming = false

    fun finalConfirm() {
        if (confirming) return
        confirming = true
        val s = _state.value
        val amount = s.amount.toDoubleOrNull() ?: return
        val category = s.selectedCategory ?: return
        val account = s.selectedAccount ?: return

        viewModelScope.launch {
            val bill = Bill(
                amount = amount,
                billType = s.billType,
                categoryId = category.id,
                categoryName = category.name,
                subCategoryName = s.selectedSubCategory?.name,
                accountId = account.id,
                remark = s.remark.ifBlank { null },
                date = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )
            repository.addBill(bill)
            _effects.send(QuickAddEffect.FinalConfirmCompleted)
        }
    }

    fun resetConfirming() {
        confirming = false
    }

    fun reset() {
        _state.update { s ->
            QuickAddState(
                accounts = s.accounts,
                expenseCategories = s.expenseCategories,
                incomeCategories = s.incomeCategories,
                selectedAccount = s.accounts.firstOrNull()
            )
        }
    }

    class Factory(private val repository: BillRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QuickAddViewModel(repository) as T
        }
    }
}
