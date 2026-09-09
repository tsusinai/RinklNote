package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.AppDatabase
import com.example.rinklnote.data.db.entity.ChatMessage
import kotlinx.coroutines.flow.Flow

internal class ChatRepositoryImpl(
    private val db: AppDatabase
) : ChatRepository {

    private val chatDao = db.chatMessageDao()

    override fun observeChatMessages(): Flow<List<ChatMessage>> = chatDao.observeAll()

    override suspend fun insertChatMessage(message: ChatMessage): Long = chatDao.insert(message)

    override suspend fun countChatMessages(kind: String, since: Long): Long =
        chatDao.countSince(kind, since)
}