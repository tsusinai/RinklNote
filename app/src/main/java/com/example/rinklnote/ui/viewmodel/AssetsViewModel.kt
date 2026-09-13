package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.repository.AccountRepository
import com.example.rinklnote.sync.SyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class AssetsState(
    val accounts: List<Account> = emptyList(),
    val loaded: Boolean = false
)

sealed interface AssetsEvent {
    data class AddAccount(
        val name: String,
        val iconKey: String,
        val iconColor: String,
        val balanceMinor: Long
    ) : AssetsEvent
    data class RenameAccount(val account: Account, val name: String) : AssetsEvent
    data class ChangeBalance(val account: Account, val balanceMinor: Long) : AssetsEvent
    data class DeleteAccount(val account: Account) : AssetsEvent

    /** 单账户对账：期末余额 = openingOffset + 该账户账单收支合计。 */
    data class ReconcileAccount(val account: Account, val openingOffsetMinor: Long) : AssetsEvent
    /** 全部对账：每个账户余额重算为各自账单合计（期初偏移 0）。 */
    data object ReconcileAll : AssetsEvent
}

/** 资产页 ViewModel：账户清单取自 repository 缓存的 accounts（StateFlow，非 Room 实时流），
 *  每次增/改名/改余额/删除都先本地落库置 dirty，再尽力推送到服务端（pushAccount）。 */
class AssetsViewModel(
    private val accountRepository: AccountRepository,
    private val syncManager: SyncManager? = null
) : ViewModel() {

    private val _state = MutableStateFlow(AssetsState())
    val state: StateFlow<AssetsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.observeAccounts().collect { accounts ->
                _state.update { it.copy(accounts = accounts, loaded = true) }
            }
        }
    }

    fun onEvent(event: AssetsEvent) {
        when (event) {
            is AssetsEvent.AddAccount -> addAccount(event)
            is AssetsEvent.RenameAccount -> renameAccount(event)
            is AssetsEvent.ChangeBalance -> changeBalance(event)
            is AssetsEvent.DeleteAccount -> deleteAccount(event)
            is AssetsEvent.ReconcileAccount -> reconcileAccount(event)
            is AssetsEvent.ReconcileAll -> reconcileAll()
        }
    }

    /** 供对账对话框加载该账户的账单收支合计（非删除账单）。 */
    suspend fun getAccountNet(accountId: Long): Long = accountRepository.getAccountNet(accountId)

    private fun addAccount(event: AssetsEvent.AddAccount) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val account = Account(
                name = event.name,
                balanceMinor = event.balanceMinor,
                iconColor = event.iconColor,
                iconKey = event.iconKey,
                updatedAt = now,
                deleted = false,
                dirty = true
            )
            val id = accountRepository.insertAccount(account)
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
            accountRepository.updateAccountLocal(updated)
            syncManager?.let { launch { it.pushAccount(updated) } }
        }
    }

    private fun changeBalance(event: AssetsEvent.ChangeBalance) {
        viewModelScope.launch {
            val updated = event.account.copy(
                balanceMinor = event.balanceMinor,
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
            accountRepository.updateAccountLocal(updated)
            syncManager?.let { launch { it.pushAccount(updated) } }
        }
    }

    private fun deleteAccount(event: AssetsEvent.DeleteAccount) {
        viewModelScope.launch {
            // 本地先标记逻辑删除，再把「删除墓碑」推送服务端做同样软删（跨端一致）。
            accountRepository.softDeleteAccount(event.account)
            val tombstone = event.account.copy(
                deleted = true,
                dirty = true,
                updatedAt = System.currentTimeMillis()
            )
            syncManager?.let { launch { it.pushAccount(tombstone) } }
        }
    }

    private fun reconcileAccount(event: AssetsEvent.ReconcileAccount) {
        viewModelScope.launch {
            val updated = accountRepository.reconcileAccount(event.account, event.openingOffsetMinor)
            syncManager?.let { launch { it.pushAccount(updated) } }
        }
    }

    private fun reconcileAll() {
        viewModelScope.launch {
            accountRepository.reconcileAllAccounts().forEach { updated ->
                syncManager?.let { launch { it.pushAccount(updated) } }
            }
        }
    }

    class Factory(
        private val accountRepository: AccountRepository,
        private val syncManager: SyncManager? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AssetsViewModel(accountRepository, syncManager) as T
        }
    }
}
