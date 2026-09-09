package com.example.rinklnote.domain

/** 账单来源：APP / QQ。对应 DB 中 source 的 APP / QQ 字符串。 */
enum class Source(val value: String) {
    APP("APP"),
    QQ("QQ");

    companion object {
        fun fromValue(value: String): Source =
            entries.firstOrNull { it.value == value } ?: APP
    }
}
