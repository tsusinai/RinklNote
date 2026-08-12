package com.example.rinklnote.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@androidx.compose.runtime.Immutable
@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,   // "user" | "assistant"
    val kind: String,   // "text" | "booking" | "summary" | "anomaly" | "greeting"
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
