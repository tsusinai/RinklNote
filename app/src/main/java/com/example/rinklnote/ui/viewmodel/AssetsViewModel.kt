package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.repository.BillRepository
import com.example.rinklnote.sync.SyncManager
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
    data class AddAccount(val name: String, val iconColor: String, val balance: Double) : AssetsEvent
    data class RenameAccount(val account: Account, val name: String) : AssetsEvent
    data class ChangeBalance(val account: Account, val balance: Double) : AssetsEvent
    data class DeleteAccount(val account: Account) : AssetsEvent
}

/** 资产页 ViewModel：账户清单取自 repository 缓存的 accounts（StateFlow，非 Room 实时流），
 *  每次增/改名/改余额/删除都先本地落库置 dirty，再尽力推送到服务端（pushAccount）。 */
class AssetsViewModel(
    private val repository: BillRepository,
    private val syncManager: SyncManager? = null
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
            is AssetsEvent.AddAccount -> addAccount(event)
            is AssetsEvent.RenameAccount -> renameAccount(event)
            is AssetsEvent.ChangeBalance -> changeBalance(event)
            is AssetsEvent.DeleteAccount -> deleteAccount(event)
        }
    }

    private fun addAccount(event: AssetsEvent.AddAccount) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val account = Account(
                name = event.name,
                balance = event.balance,
                iconColor = event.iconColor,
                updatedAt = now,
                deleted = false,
                dirty = true
            )
            val id = repository.insertAccount(account)
            // Push once the real auto-generated id is known so SyncManager can
            // stamp the server id — otherwise it stays unsynced forever.
            syncManager?.let { launch { it.pushAccount(account.copy(id = id)) } }
        }
    }

    private fun renameAccount(event: AssetsEvent.RenameAccount) {
        viewModelScope.launch {
            val updated = event.account.copy(
                name = event.name,
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
            repository.updateAccountLocal(updated)
            syncManager?.let { launch { it.pushAccount(updated) } }
        }
    }

    private fun changeBalance(event: AssetsEvent.ChangeBalance) {
        viewModelScope.launch {
            val updated = event.account.copy(
                balance = event.balance,
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
            repository.updateAccountLocal(updated)
            syncManager?.let { launch { it.pushAccount(updated) } }
        }
    }

    private fun deleteAccount(event: AssetsEvent.DeleteAccount) {
        viewModelScope.launch {
            // 本地先标记逻辑删除，再把「删除墓碑」推送服务端做同样软删（跨端一致）。
            repository.softDeleteAccount(event.account)
            val tombstone = event.account.copy(
                deleted = true,
                dirty = true,
                updatedAt = System.currentTimeMillis()
            )
            syncManager?.let { launch { it.pushAccount(tombstone) } }
        }
    }

    class Factory(
        private val repository: BillRepository,
        private val syncManager: SyncManager? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AssetsViewModel(repository, syncManager) as T
        }
    }
}
