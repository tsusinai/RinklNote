package com.example.rinklnote.domain

/** 聊天消息类型。对应 DB 中 kind 字段的字符串值。 */
enum class MessageKind(val value: String) {
    TEXT("text"),
    BOOKING("booking"),
    SUMMARY("summary"),
    ANOMALY("anomaly"),
    GREETING("greeting"),
    HABIT("habit");

    companion object {
        fun fromValue(value: String): MessageKind =
            entries.firstOrNull { it.value == value } ?: TEXT
    }
}
