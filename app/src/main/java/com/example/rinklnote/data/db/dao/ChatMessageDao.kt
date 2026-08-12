package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.rinklnote.data.db.entity.ChatMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insert(message: ChatMessage): Long

    @Query("SELECT * FROM chat_messages ORDER BY created_at ASC, id ASC")
    fun observeAll(): Flow<List<ChatMessage>>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE kind = :kind AND created_at >= :since")
    suspend fun countSince(kind: String, since: Long): Long

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}
