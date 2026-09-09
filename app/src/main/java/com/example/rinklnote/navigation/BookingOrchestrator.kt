package com.example.rinklnote.navigation

import com.example.rinklnote.data.db.entity.Category
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 聊天发起的记账命令：解耦 AiViewModel 和 QuickAddViewModel。
 * AiViewModel 发出记账请求，QuickAddViewModel 消费并执行。
 */
sealed interface BookingCommand {
    data class ParseAndBook(
        val text: String,
        val category: Category? = null,
        val amount: Double? = null
    ) : BookingCommand
}

/**
 * 聊天记账编排器：在 AppNavigation 层面创建，连接 AiViewModel 的记账请求
 * 和 QuickAddViewModel 的执行能力。
 */
class BookingOrchestrator {
    private val _commands = Channel<BookingCommand>(Channel.BUFFERED)
    val commands = _commands.receiveAsFlow()

    fun requestBooking(text: String) {
        _commands.trySend(BookingCommand.ParseAndBook(text = text))
    }
}
