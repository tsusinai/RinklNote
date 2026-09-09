package com.example.rinklnote.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory singleton shared by the Assets screen, the QuickAdd drawer, and the home-screen
 * widget (all run in the app process). Defaults to VISIBLE (balances shown as real amounts).
 *
 * The persistence bridge is injected by [com.example.rinklnote.RinklNoteApp]: listeners are
 * notified when the toggle changes, and `restore()` re-applies the persisted value on app start.
 * This keeps App + widget on one shared switch that survives process death.
 */
object BalancePrivacy {
    private val _hidden = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    /** 注册监听器：当开关状态变化时回调。 */
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    /** 移除监听器。 */
    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    /** 仅内存更新（App 启动时从 DataStore 恢复），不触发 listeners，避免写回循环。 */
    fun restore(value: Boolean) {
        _hidden.value = value
    }

    /** 设置开关：更新内存并通知所有监听器。 */
    fun setHidden(value: Boolean) {
        _hidden.value = value
        listeners.forEach { it(value) }
    }

    fun toggle() {
        setHidden(!_hidden.value)
    }
}
