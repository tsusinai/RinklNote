package com.example.rinklnote.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.rinklnote.R
import com.example.rinklnote.domain.ACCOUNT_ICON_ALIPAY
import com.example.rinklnote.domain.ACCOUNT_ICON_BANK_CARD
import com.example.rinklnote.domain.ACCOUNT_ICON_CASH
import com.example.rinklnote.domain.ACCOUNT_ICON_CREDIT_CARD
import com.example.rinklnote.domain.ACCOUNT_ICON_INVESTMENT
import com.example.rinklnote.domain.ACCOUNT_ICON_OTHER
import com.example.rinklnote.domain.ACCOUNT_ICON_WALLET
import com.example.rinklnote.domain.ACCOUNT_ICON_WECHAT
import com.example.rinklnote.domain.normalizeAccountIconKey

data class AccountIconOption(
    val key: String,
    val label: String,
    val resource: Int
)

val ACCOUNT_ICON_OPTIONS = listOf(
    AccountIconOption(ACCOUNT_ICON_WALLET, "钱包", R.drawable.ic_account_wallet),
    AccountIconOption(ACCOUNT_ICON_BANK_CARD, "银行卡", R.drawable.ic_account_bank_card),
    AccountIconOption(ACCOUNT_ICON_CASH, "现金", R.drawable.ic_account_cash),
    AccountIconOption(ACCOUNT_ICON_WECHAT, "微信", R.drawable.ic_wechat),
    AccountIconOption(ACCOUNT_ICON_ALIPAY, "支付宝", R.drawable.ic_alipay),
    AccountIconOption(ACCOUNT_ICON_CREDIT_CARD, "信用卡", R.drawable.ic_account_credit_card),
    AccountIconOption(ACCOUNT_ICON_INVESTMENT, "投资", R.drawable.ic_account_investment),
    AccountIconOption(ACCOUNT_ICON_OTHER, "其他", R.drawable.ic_account_other),
)

fun accountIconKeyRes(iconKey: String): Int =
    ACCOUNT_ICON_OPTIONS.firstOrNull { it.key == normalizeAccountIconKey(iconKey) }?.resource
        ?: R.drawable.ic_account_wallet

fun accountColor(hex: String): Color {
    val value = hex.removePrefix("#").toLongOrNull(16) ?: return Color(0xFF64748B)
    return when (hex.removePrefix("#").length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> Color(0xFF64748B)
    }
}

/** 统一账户图标：浅色底 + 账户色单色图标。 */
@Composable
fun AccountIcon(
    iconKey: String,
    colorHex: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val color = accountColor(colorHex)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(accountIconKeyRes(iconKey)),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.56f)
        )
    }
}
