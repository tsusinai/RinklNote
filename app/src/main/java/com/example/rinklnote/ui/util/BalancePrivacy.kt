package com.example.rinklnote.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory singleton shared by the Assets screen and the QuickAdd drawer.
 * Defaults to hidden (balances shown as ***).
 */
object BalancePrivacy {
    private val _hidden = MutableStateFlow(true)
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    fun toggle() {
        _hidden.value = !_hidden.value
    }
}
