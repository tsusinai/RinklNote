package com.example.rinklnote.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory singleton shared by the Assets screen, the QuickAdd drawer, and the home-screen
 * widget (all run in the app process). Defaults to VISIBLE (balances shown as real amounts).
 *
 * The persistence bridge is injected by [com.example.rinklnote.RinklNoteApp]: `writer` writes
 * the toggle back to DataStore, and `restore()` re-applies the persisted value on app start.
 * This keeps App + widget on one shared switch that survives process death.
 */
object BalancePrivacy {
    private val _hidden = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    /** 注入点：把内存开关同步回持久化存储（DataStore）。 */
    var writer: (Boolean) -> Unit = {}

    /** 仅内存更新（App 启动时从 DataStore 恢复），不触发 writer，避免写回循环。 */
    fun restore(value: Boolean) {
        _hidden.value = value
    }

    /** 设置开关：更新内存并写回持久化。 */
    fun setHidden(value: Boolean) {
        _hidden.value = value
        writer(value)
    }

    fun toggle() {
        setHidden(!_hidden.value)
    }
}
