package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.entity.ChatMessage
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeChatMessages(): Flow<List<ChatMessage>>
    suspend fun insertChatMessage(message: ChatMessage): Long
    suspend fun countChatMessages(kind: String, since: Long): Long
}