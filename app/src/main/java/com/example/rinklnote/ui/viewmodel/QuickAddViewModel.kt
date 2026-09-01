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
import com.example.rinklnote.util.bookkeepingZone
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val expandedParentId: Long? = null,
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
    data object FinalConfirmCompleted : QuickAddEffect
    data class FinalConfirmFailed(val message: String) : QuickAddEffect
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
            is QuickAddEvent.Clear -> _state.update { it.copy(amount = "") }
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

    // 二级分类异步加载的当前任务：连按多个母标签时取消旧任务，避免旧父级结果覆盖新父级
    private var subCategoryLoadJob: Job? = null

    private fun onDigit(digit: String) {
        _state.update { current ->
            if (current.amount.contains(".") && digit == ".") return
            val newAmount = current.amount + digit
            current.copy(amount = newAmount)
        }
    }

    private fun onBackspace() {
        _state.update { current ->
            current.copy(amount = current.amount.dropLast(1))
        }
    }

    private fun toggleType() {
        _state.update { current ->
            val newType = if (current.billType == "EXPENSE") "INCOME" else "EXPENSE"
            current.copy(
                billType = newType,
                selectedCategory = null,
                selectedSubCategory = null,
                showSubCategories = false,
                expandedParentId = null,
                subCategories = emptyList()
            )
        }
    }

    private fun selectCategory(category: Category) {
        subCategoryLoadJob?.cancel()
        _state.update {
            it.copy(
                selectedCategory = category,
                selectedSubCategory = null,
                showSubCategories = false,
                expandedParentId = null,
                subCategories = emptyList()
            )
        }
    }

    private fun showSubCategories(category: Category) {
        // 先清空旧二级分类并记录本次请求的父级 id，结果只在"仍是当前展开父级"时才应用，
        // 避免快速连按多个母标签时旧结果覆盖新父级、弹层闪错位置。
        subCategoryLoadJob?.cancel()
        val parentId = category.id
        _state.update { it.copy(showSubCategories = true, expandedParentId = parentId, subCategories = emptyList()) }
        subCategoryLoadJob = viewModelScope.launch {
            val subs = repository.getSubCategories(parentId)
            _state.update { if (it.expandedParentId == parentId) it.copy(subCategories = subs) else it }
        }
    }

    private fun selectSubCategory(subCategory: SubCategory?) {
        // 选中后保持二级分类弹层展开，便于查看/切换；点母标签或长按其它分类时再收起。
        _state.update { it.copy(selectedSubCategory = subCategory) }
    }

    private fun dismissSubCategories() {
        subCategoryLoadJob?.cancel()
        _state.update { it.copy(showSubCategories = false, expandedParentId = null, subCategories = emptyList()) }
    }

    private fun confirm() {
        // One-step: keypad confirm saves immediately (anti-misclick two-phase removed).
        val s = _state.value
        if (s.amount.toDoubleOrNull() == null) return
        if (s.selectedCategory == null) {
            _effects.trySend(QuickAddEffect.FinalConfirmFailed("请先选择分类"))
            return
        }
        if (s.selectedAccount == null) {
            _effects.trySend(QuickAddEffect.FinalConfirmFailed("请先选择账户"))
            return
        }
        finalConfirm()
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
                        // One-step: NLP intent saves directly
                        finalConfirm()
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
        // Category auto-selected → save directly (one-step)
        if (cat != null) {
            finalConfirm()
        }
    }

    // ── Template ──
    private fun onTemplateClick(template: BillTemplate) {
        val cat = _state.value.categories.find { it.id == template.categoryId } ?: return
        val acct = _state.value.accounts.find { it.id == template.accountId } ?: return
        // 子分类必须从"模板自身分类"解析，而不是当前弹层快照（可能为空/属于别的父级），
        // 否则模板携带的子分类会被静默丢弃。逐条异步拉取模板分类的二分类。
        viewModelScope.launch {
            val subs = repository.getSubCategories(cat.id)
            val sub = template.subCategoryName?.let { name -> subs.find { it.name == name } }
            _state.update {
                it.copy(
                    amount = template.amount.toBigDecimal().stripTrailingZeros().toPlainString(),
                    selectedCategory = cat,
                    billType = cat.billType,
                    selectedSubCategory = sub,
                    selectedAccount = acct,
                    remark = ""
                )
            }
            finalConfirm()
        }
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
            _effects.trySend(QuickAddEffect.FinalConfirmFailed("请完整填写金额、分类和账户"))
            return
        }

        viewModelScope.launch {
            try {
                val bill = Bill(
                    amount = amount,
                    billType = s.billType,
                    categoryId = category.id,
                    categoryName = category.name,
                    subCategoryName = s.selectedSubCategory?.name,
                    accountId = account.id,
                    remark = s.remark.ifBlank { null },
                    date = LocalDate.now(bookkeepingZone()).atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()
                )
                val savedId = repository.addBill(bill)
                _effects.send(QuickAddEffect.FinalConfirmCompleted)
                // Background push to server (best-effort, non-blocking).
                // Push the persisted row (with its real auto-generated id) so
                // SyncManager can stamp server_id on it — otherwise it stays
                // unsynced and every full sync re-POSTs a duplicate.
                val saved = bill.copy(id = savedId)
                syncManager?.let { launch { it.pushBill(saved) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 保存失败也要给反馈，否则「确认」看起来无响应。
                _effects.send(QuickAddEffect.FinalConfirmFailed("记账失败，请重试"))
            } finally {
                // 无论成败都释放守卫：否则 addBill 抛异常时 confirming 常驻 true，
                // 键盘「确认」此后永久无响应（「点不动」的根因之一）。
                confirming = false
            }
        }
    }

    fun resetConfirming() {
        confirming = false
    }

    fun reset() {
        // 重置后恢复默认选中（支出首个分类），否则下次开抽屉无预选导致「确认」要重挑分类。
        // 仅支出 collector 会补默认，income 不会（收入默认留空），故此处用支出首个兜底。
        _state.update { s ->
            QuickAddState(
                accounts = s.accounts,
                expenseCategories = s.expenseCategories,
                incomeCategories = s.incomeCategories,
                selectedAccount = s.accounts.firstOrNull(),
                selectedCategory = s.expenseCategories.firstOrNull()
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
