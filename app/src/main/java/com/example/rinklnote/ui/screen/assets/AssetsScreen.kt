package com.example.rinklnote.ui.screen.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.ui.util.BalancePrivacy
import com.example.rinklnote.ui.viewmodel.AssetsEvent
import com.example.rinklnote.ui.viewmodel.AssetsViewModel

private fun accountIconRes(name: String): Int = when (name) {
    "微信" -> R.drawable.ic_wechat
    "支付宝" -> R.drawable.ic_alipay
    "默认" -> R.drawable.ic_default_account
    else -> R.drawable.ic_default_account
}

@Composable
fun AssetsScreen(viewModel: AssetsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val balanceHidden by BalancePrivacy.hidden.collectAsStateWithLifecycle()

    var editingAccount by remember { mutableStateOf<Account?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "资产管理",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "total") {
                TotalAssetsCard(accounts = state.accounts, hidden = balanceHidden)
            }
            items(state.accounts, key = { it.id }) { account ->
                AccountCard(
                    account = account,
                    hidden = balanceHidden,
                    onClick = { editingAccount = account }
                )
            }
        }
    }

    editingAccount?.let { account ->
        BalanceEditDialog(
            account = account,
            onConfirm = { updated ->
                editingAccount = null
                viewModel.onEvent(AssetsEvent.UpdateBalance(updated))
            },
            onDismiss = { editingAccount = null }
        )
    }
}

@Composable
private fun TotalAssetsCard(accounts: List<Account>, hidden: Boolean) {
    val total = accounts.sumOf { it.balance }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "总资产",
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (hidden) "****" else String.format("%.2f", total),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Icon(
            painter = painterResource(if (hidden) R.drawable.ic_eye_show else R.drawable.ic_eye_hide),
            contentDescription = if (hidden) "显示余额" else "隐藏余额",
            modifier = Modifier
                .size(22.dp)
                .clickable { BalancePrivacy.toggle() },
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun AccountCard(
    account: Account,
    hidden: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(accountIconRes(account.name)),
            contentDescription = account.name,
            modifier = Modifier.size(23.dp),
            tint = Color.Unspecified
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = if (hidden) "***" else String.format("%.2f", account.balance),
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
