package com.example.rinklnote.domain

/** 账单类型：支出 / 收入。对应 DB 中 bill_type 的 EXPENSE / INCOME 字符串。 */
enum class BillType(val value: String) {
    EXPENSE("EXPENSE"),
    INCOME("INCOME");

    companion object {
        fun fromValue(value: String): BillType =
            entries.firstOrNull { it.value == value } ?: EXPENSE
    }
}
