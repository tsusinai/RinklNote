package com.example.rinklnote.ui.screen.assets

import com.example.rinklnote.data.db.entity.ACCOUNT_BUCKET_NAME
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.util.Money

const val ACCOUNT_NAME_MAX_LENGTH = 50

fun validateAccountName(
    rawName: String,
    accounts: List<Account>,
    editingAccountId: Long? = null
): String? {
    val name = rawName.trim()
    return when {
        name.isEmpty() -> "请输入账户名称"
        name == ACCOUNT_BUCKET_NAME -> "该名称不可用"
        name.length > ACCOUNT_NAME_MAX_LENGTH -> "账户名称不能超过50个字符"
        accounts.any { it.id != editingAccountId && !it.deleted && it.name == name } -> "账户名称已存在"
        else -> null
    }
}

/** 空输入按 0 元处理；非法或负数返回 null。 */
fun parseBalanceInput(raw: String): Long? =
    if (raw.isBlank()) 0L else Money.parseMinor(raw)

fun validateBalanceInput(raw: String): String? {
    val value = raw.trim()
    return when {
        value.isEmpty() -> null
        value.startsWith("-") -> "余额不能为负"
        parseBalanceInput(value) == null -> "余额最多保留两位小数"
        else -> null
    }
}
