package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.repository.BillRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class AssetsState(
    val accounts: List<Account> = emptyList()
)

sealed interface AssetsEvent {
    data class UpdateBalance(val account: Account) : AssetsEvent
}

class AssetsViewModel(
    private val repository: BillRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AssetsState())
    val state: StateFlow<AssetsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.accounts.collect { accounts ->
                _state.update { it.copy(accounts = accounts) }
            }
        }
    }

    fun onEvent(event: AssetsEvent) {
        when (event) {
            is AssetsEvent.UpdateBalance -> {
                viewModelScope.launch {
                    repository.updateAccount(event.account)
                }
            }
        }
    }

    class Factory(private val repository: BillRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AssetsViewModel(repository) as T
        }
    }
}
