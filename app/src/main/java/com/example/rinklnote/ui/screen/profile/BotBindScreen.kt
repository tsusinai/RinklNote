package com.example.rinklnote.ui.screen.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.ui.component.pressScale
import com.example.rinklnote.ui.viewmodel.AuthEvent
import com.example.rinklnote.ui.viewmodel.AuthViewModel
import com.example.rinklnote.ui.viewmodel.BotChannel

/**
 * 机器人绑定页（Phase D：由旧「绑定QQ号」页改造而来）。
 *
 * 三通道（QQ / 飞书 / 企业微信）共用一个绑定码流程：
 * 1. 在对应 App 里向机器人发送「登录」，机器人回复 6 位绑定码（5 分钟有效）；
 * 2. 本页选中通道 + 输入 6 位码提交，服务端消费一次性码完成账号 ↔ 通道身份绑定。
 *
 * 提交按通道分别调 /api/{qq,feishu,wecom}-bot/bind；绑定成功后经
 * [AuthViewModel] 的 bindSucceeded 一次性状态自动关闭本页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotBindPage(
    viewModel: AuthViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 绑定成功 → 自动关闭（bindSucceeded 是一次性副作用标记，由 VM 提交成功时置位）
    LaunchedEffect(state.bindSucceeded) {
        if (state.bindSucceeded != null) onDismiss()
    }
    BackHandler { onDismiss() }

    // 输入法弹出时整列上移，避免盖住绑定码输入框与提交按钮；收起时无额外内边距。
    // enableEdgeToEdge 下系统不会自动避让，必须显式让位。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        // Top bar with back arrow
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "机器人绑定",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = "绑定后即可在对应 App 里向机器人发消息快捷记账",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 通道选择：QQ / 飞书 / 企业微信 三选一（全中文标签）
            Text(
                text = "选择要绑定的通道",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BotChannel.entries.forEach { channel ->
                    FilterChip(
                        selected = state.bindChannel == channel,
                        onClick = { viewModel.onEvent(AuthEvent.BindChannelSelected(channel)) },
                        label = { Text(channel.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))

            // 引导文案：三通道一致——机器人收到「登录」即回复 6 位绑定码（5 分钟内有效）
            Text(
                text = "在${state.bindChannel.guideApp}里向机器人发送「登录」，即可获取 6 位绑定码",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.bindCode,
                onValueChange = { input ->
                    // 只保留数字且最多 6 位（对应服务端 6 位一次性绑定码）
                    viewModel.onEvent(AuthEvent.BindCodeChanged(input.filter { it.isDigit() }.take(6)))
                },
                label = { Text("6 位绑定码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            if (state.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp
                )
            }
            if (state.successMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.successMessage!!,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            // 大按钮按压反馈：按下缩放 0.97，用同一个 InteractionSource 驱动
            val bindInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = { viewModel.onEvent(AuthEvent.SubmitBind) },
                enabled = !state.isLoading,
                interactionSource = bindInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(bindInteraction)
            ) { Text("绑定") }
            if (state.isLoading) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
