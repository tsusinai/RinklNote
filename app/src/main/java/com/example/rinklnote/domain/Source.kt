package com.example.rinklnote.domain

/** 账单来源：APP / QQ / AUTO。对应 DB 中 source 的 APP / QQ / AUTO 字符串。 */
enum class Source(val value: String) {
    APP("APP"),
    QQ("QQ"),
    // 自动记账（支付通知捕获入账，2026-10-03 方案 C）：落库即同步，服务端暮检按此口径对账。
    AUTO("AUTO");

    companion object {
        fun fromValue(value: String): Source =
            entries.firstOrNull { it.value == value } ?: APP
    }
}
