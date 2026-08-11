package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.ParseRequest
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.sync.SyncManager
import com.example.rinklnote.util.VoiceParser
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
    val confirmed: Boolean = false,
    val templates: List<BillTemplate> = emptyList(),
    val nlpInput: String = "",
    val isParsing: Boolean = false,
    val suggestion: SuggestionData? = null,
    val suggestionDismissed: Boolean = false
) {
    data class SuggestionData(val label: String, val categoryName: String, val amount: Double)

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
    data class NlpInput(val text: String) : QuickAddEvent
    data object NlpSubmit : QuickAddEvent
    data class TemplateClick(val template: BillTemplate) : QuickAddEvent
    data object SuggestionClick : QuickAddEvent
    data object DismissSuggestion : QuickAddEvent
    data object Confirm : QuickAddEvent
}

sealed interface QuickAddEffect {
    data object ConfirmRequested : QuickAddEffect
    data object FinalConfirmCompleted : QuickAddEffect
}

class QuickAddViewModel(
    private val repository: BillRepository,
    private val syncManager: SyncManager? = null,
    private val api: ApiService? = null
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
        viewModelScope.launch {
            repository.observeTemplates().collect { templates ->
                _state.update { it.copy(templates = templates) }
            }
        }
    }

    fun onEvent(event: QuickAddEvent) {
        when (event) {
            is QuickAddEvent.Digit -> onDigit(event.digit)
            is QuickAddEvent.Clear -> _state.update { it.copy(amount = "", isConfirmEnabled = false) }
            is QuickAddEvent.Backspace -> onBackspace()
            is QuickAddEvent.ToggleType -> toggleType()
            is QuickAddEvent.SelectCategory -> selectCategory(event.category)
            is QuickAddEvent.LongPressCategory -> showSubCategories(event.category)
            is QuickAddEvent.SelectSubCategory -> selectSubCategory(event.subCategory)
            is QuickAddEvent.SelectAccount -> _state.update { it.copy(selectedAccount = event.account) }
            is QuickAddEvent.RemarkChanged -> _state.update { it.copy(remark = event.remark) }
            is QuickAddEvent.DismissSubCategories -> dismissSubCategories()
            is QuickAddEvent.NlpInput -> _state.update { it.copy(nlpInput = event.text) }
            is QuickAddEvent.NlpSubmit -> onNlpSubmit()
            is QuickAddEvent.TemplateClick -> onTemplateClick(event.template)
            is QuickAddEvent.SuggestionClick -> onSuggestionClick()
            is QuickAddEvent.DismissSuggestion -> _state.update {
                it.copy(suggestion = null, suggestionDismissed = true)
            }
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

    // ── NLP ──
    private fun onNlpSubmit() {
        val text = _state.value.nlpInput.trim()
        if (text.isBlank()) return
        _state.update { it.copy(isParsing = true) }
        val svc = api
        if (svc == null) {
            applyLocalParse(text)
            return
        }
        viewModelScope.launch {
            try {
                val result = svc.parseBill(ParseRequest(text))
                if (result.amount.isNotBlank() && result.amount.toDoubleOrNull() != null) {
                    val cat = _state.value.categories.find { it.name == result.categoryName }
                    if (cat != null) {
                        _state.update {
                            it.copy(
                                amount = result.amount,
                                selectedCategory = cat,
                                billType = cat.billType,
                                remark = result.remark.ifBlank { text },
                                nlpInput = "",
                                isParsing = false
                            )
                        }
                        // Skip two-step: NLP intent is explicit enough
                        _effects.trySend(QuickAddEffect.ConfirmRequested)
                        return@launch
                    }
                }
                _state.update { it.copy(isParsing = false) }
            } catch (_: Exception) {
                _state.update { it.copy(isParsing = false) }
                applyLocalParse(text)
            }
        }
    }

    /** Offline/unauth fallback: local rule-based parsing (VoiceParser). */
    private fun applyLocalParse(text: String) {
        val parsed = VoiceParser.parse(text)
        if (parsed.amount == null) {
            _state.update { it.copy(isParsing = false) }
            return
        }
        val cat = parsed.categoryName?.let { name -> _state.value.categories.find { it.name == name } }
        val amountStr = if (parsed.amount == parsed.amount.toLong().toDouble()) {
            parsed.amount.toLong().toString()
        } else {
            parsed.amount.toString()
        }
        _state.update {
            it.copy(
                amount = amountStr,
                selectedCategory = cat ?: it.selectedCategory,
                billType = cat?.billType ?: it.billType,
                remark = parsed.remark.ifBlank { it.remark },
                nlpInput = "",
                isParsing = false
            )
        }
        // Category auto-selected → proceed to CountAfter (fixes voice category bug)
        if (cat != null) {
            _effects.trySend(QuickAddEffect.ConfirmRequested)
        }
    }

    // ── Template ──
    private fun onTemplateClick(template: BillTemplate) {
        val cat = _state.value.categories.find { it.id == template.categoryId } ?: return
        val acct = _state.value.accounts.find { it.id == template.accountId } ?: return
        _state.update {
            it.copy(
                amount = template.amount.toBigDecimal().stripTrailingZeros().toPlainString(),
                selectedCategory = cat,
                billType = cat.billType,
                selectedSubCategory = template.subCategoryName?.let { name ->
                    _state.value.subCategories.find { sc -> sc.name == name }
                },
                selectedAccount = acct,
                remark = ""
            )
        }
        viewModelScope.launch { finalConfirm() }
    }

    // ── Suggestion ──
    private fun onSuggestionClick() {
        val s = _state.value.suggestion ?: return
        val cat = _state.value.categories.find { it.name == s.categoryName } ?: return
        val acct = _state.value.accounts.firstOrNull() ?: return
        _state.update {
            it.copy(
                amount = s.amount.toBigDecimal().stripTrailingZeros().toPlainString(),
                selectedCategory = cat,
                billType = cat.billType,
                selectedAccount = acct,
                remark = "",
                suggestion = null,
                suggestionDismissed = true
            )
        }
        viewModelScope.launch { finalConfirm() }
    }

    fun loadSuggestion() {
        val svc = api ?: return
        viewModelScope.launch {
            try {
                val result = svc.getSuggestion()
                val catName = result["categoryName"]
                val amount = result["amount"]
                if (!catName.isNullOrBlank() && amount != null) {
                    val amt = amount.toDoubleOrNull() ?: return@launch
                    _state.update {
                        it.copy(suggestion = QuickAddState.SuggestionData(
                            label = result["label"] ?: catName,
                            categoryName = catName,
                            amount = amt
                        ))
                    }
                    kotlinx.coroutines.delay(5000)
                    _state.update { it.copy(suggestion = null) }
                }
            } catch (_: Exception) {}
        }
    }

    private var confirming = false

    fun finalConfirm() {
        if (confirming) return
        confirming = true
        val s = _state.value
        val amount = s.amount.toDoubleOrNull()
        val category = s.selectedCategory
        val account = s.selectedAccount
        if (amount == null || category == null || account == null) {
            // Not a valid submission — release the guard so the user can retry.
            confirming = false
            return
        }

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
            val savedId = repository.addBill(bill)
            _effects.send(QuickAddEffect.FinalConfirmCompleted)
            // Background push to server (best-effort, non-blocking).
            // Push the persisted row (with its real auto-generated id) so
            // SyncManager can stamp server_id on it — otherwise it stays
            // unsynced and every full sync re-POSTs a duplicate.
            val saved = bill.copy(id = savedId)
            syncManager?.let { launch { it.pushBill(saved) } }
            // Release the guard so the next QuickAdd can submit again.
            confirming = false
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

    class Factory(
        private val repository: BillRepository,
        private val syncManager: SyncManager? = null,
        private val api: ApiService? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QuickAddViewModel(repository, syncManager, api) as T
        }
    }
}
