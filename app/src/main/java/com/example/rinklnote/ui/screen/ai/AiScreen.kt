package com.example.rinklnote.ui.screen.ai

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.ui.viewmodel.AiEvent
import com.example.rinklnote.ui.viewmodel.AiViewModel
import com.example.rinklnote.ui.viewmodel.QuickAddEvent
import com.example.rinklnote.ui.viewmodel.QuickAddViewModel

@Composable
fun AiScreen(
    viewModel: AiViewModel,
    quickAddVM: QuickAddViewModel,
    isLoggedIn: Boolean
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var nliText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("AI 界面", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        // 卡片 1：AI 记账（无需登录，离线可本地解析）
        item {
            CardContainer {
                Text("AI 记账", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nliText,
                    onValueChange = { nliText = it },
                    singleLine = true,
                    placeholder = { Text("例如：午餐28元", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val text = nliText.trim()
                        if (text.isBlank()) return@Button
                        quickAddVM.resetConfirming() // 防上一次保存后 guard 卡住
                        quickAddVM.onEvent(QuickAddEvent.NlpInput(text))
                        quickAddVM.onEvent(QuickAddEvent.NlpSubmit)
                        nliText = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("记账") }
                Spacer(Modifier.height(4.dp))
                Text("支持自然语言，离线时本地解析", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 卡片 2：综合助手（问账）— 需登录
        item {
            CardContainer {
                Text("综合助手（问账）", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.queryInput,
                    onValueChange = { viewModel.onEvent(AiEvent.QueryInputChanged(it)) },
                    singleLine = true,
                    placeholder = { Text("例如：上个月交通花了多少？", fontSize = 13.sp) },
                    enabled = isLoggedIn,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.onEvent(AiEvent.SubmitQuery) },
                    enabled = isLoggedIn && !state.isQuerying,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("提问") }
                Spacer(Modifier.height(8.dp))
                when {
                    state.isQuerying -> CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    state.answer != null -> Text(
                        state.answer.orEmpty(),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        // 卡片 3：本月总结 — 需登录
        item {
            CardContainer {
                Text("本月总结", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    state.monthlySummary?.let { Text(it, fontSize = 14.sp) }
                    state.highlights.forEach { h ->
                        Row(Modifier.padding(top = 4.dp)) {
                            Text("• ", color = MaterialTheme.colorScheme.primary)
                            Text(h, fontSize = 13.sp)
                        }
                    }
                    if (state.monthlySummary == null && !isLoggedIn) {
                        Text("登录后可查看本月总结", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 卡片 4：异常提醒 — 需登录
        item {
            CardContainer {
                Text("异常提醒", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                if (!isLoggedIn) {
                    Text("登录后可查看异常提醒", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (state.alerts.isEmpty()) {
                    Text("暂无异常提醒", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.alerts.forEach { a ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                if (a.level == "WARN") "⚠ " else "• ",
                                color = if (a.level == "WARN") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            Text(a.message, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        // 未登录横幅
        if (!isLoggedIn) {
            item {
                CardContainer {
                    Text("登录后可使用综合助手（问账/本月总结/异常提醒）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        state.error?.let { err ->
            item {
                Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun CardContainer(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) { content() }
}
