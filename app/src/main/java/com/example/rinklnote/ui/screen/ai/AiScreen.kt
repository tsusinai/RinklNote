package com.example.rinklnote.ui.screen.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.rinklnote.ui.component.rinkShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.domain.MessageKind
import com.example.rinklnote.ui.component.pressScale
import com.example.rinklnote.ui.viewmodel.AiEvent
import com.example.rinklnote.ui.viewmodel.AiViewModel
import com.example.rinklnote.ui.theme.DefaultCardBorder
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.util.bookkeepingZone
import java.time.Instant
import java.time.LocalDate

@Composable
fun AiScreen(
    viewModel: AiViewModel,
    isLoggedIn: Boolean,
    onVoiceInput: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 新消息（或历史加载）到达时自动滚到底部
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // 标题栏：左侧返回键 + 居中标题（与搜索账单/自定义主题等二级页一致）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = LocalRinklColors.current.topBarTitleColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                "AI 助手",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = LocalRinklColors.current.topBarTitleColor
            )
        }
        if (!isLoggedIn) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "未登录：可直接记账，问账需先登录",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val zone = bookkeepingZone()
        // 预计算每条消息的日期与"是否需日期分隔"，避免每条目内重复 toLocalDate 并按 index 读前一条
        val messagesWithDay = remember(state.messages, zone) {
            state.messages.mapIndexed { index, msg ->
                val day = Instant.ofEpochMilli(msg.createdAt).atZone(zone).toLocalDate()
                val prevDay = if (index > 0) {
                    Instant.ofEpochMilli(state.messages[index - 1].createdAt).atZone(zone).toLocalDate()
                } else null
                Triple(msg, day, prevDay == null || prevDay != day)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        ) {
            items(messagesWithDay, key = { it.first.id }) { (msg, day, showDivider) ->
                if (showDivider) {
                    DateDivider(date = day)
                }
                ChatBubble(message = msg)
            }
            if (state.isWaiting) {
                item(key = "typing") {
                    TypingBubble()
                }
            }
        }

        // 快捷询问 chips：点击以预设文案直接走现有发送链路（AiViewModel.send），
        // 文案措辞对齐服务端意图关键词（总结 / 异常 / 分析），横排三个，小屏可横向滚动。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickAsks.forEach { (label, query) ->
                QuickAskChip(text = label) { viewModel.send(query) }
            }
        }

        // 输入栏：麦克风 + 胶囊输入框（内含右对齐箭头发送图标）
        // 避让链：navigationBarsPadding 先吃掉导航栏内边距，imePadding 只补键盘剩余高度——
        // 输入法弹出时输入栏正好落在键盘上沿（ insets 消费机制保证不叠加导航栏高度）；
        // 收起时仅保留导航栏间距。列表占 weight(1f)，高度变化由它吸收，滚动位置不受影响。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onVoiceInput) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = "语音输入",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 胶囊输入框：BasicTextField 自绘描边，紧凑高度 44dp 且不裁剪文字
            val aiInteraction = remember { MutableInteractionSource() }
            val aiFocused by aiInteraction.collectIsFocusedAsState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .border(
                        width = 1.dp,
                        color = if (aiFocused) MaterialTheme.colorScheme.primary
                                else LocalRinklColors.current.borderColor.takeIf { it.alpha > 0f } ?: DefaultCardBorder,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 16.dp, end = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize()
                ) {
                    BasicTextField(
                        value = state.input,
                        onValueChange = { viewModel.onEvent(AiEvent.InputChanged(it)) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        interactionSource = aiInteraction,
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                if (state.input.isEmpty()) {
                                    Text(
                                        text = "输入记账或问题，如「午餐28元」",
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    val canSend = state.input.isNotBlank() && !state.isWaiting
                    // 发送主按钮的按压缩放反馈
                    val sendInteraction = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = { viewModel.onEvent(AiEvent.Send) },
                        enabled = canSend,
                        interactionSource = sendInteraction,
                        modifier = Modifier.pressScale(sendInteraction)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (canSend) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 快捷询问 chip：文案措辞已按服务端意图正则（总结/异常/分析）挑选，点击即整句发送。 */
private val quickAsks = listOf(
    "支出总结" to "帮我总结本月支出",
    "支出异常" to "本月有支出异常吗",
    "支出分析" to "分析一下我的支出"
)

/** 胶囊询问 chip：边框走边框令牌（未自定义时回落内置描边灰，与输入框同款处理），文字走字体令牌。 */
@Composable
private fun QuickAskChip(text: String, onClick: () -> Unit) {
    val rinkl = LocalRinklColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = rinkl.borderColor.takeIf { it.alpha > 0f } ?: DefaultCardBorder,
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text = text, fontSize = 13.sp, color = rinkl.fontColor)
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val kindLabel: String? = when (message.kind) {
        MessageKind.SUMMARY -> "月结"
        MessageKind.ANOMALY -> "异常"
        MessageKind.HABIT -> "习惯"
        MessageKind.BOOKING -> "记账"
        else -> null
    }
    val dotColor: Color? = when (message.kind) {
        MessageKind.SUMMARY, MessageKind.BOOKING -> MaterialTheme.colorScheme.primary
        MessageKind.ANOMALY -> MaterialTheme.colorScheme.error
        MessageKind.HABIT -> MaterialTheme.colorScheme.tertiary
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .rinkShadow(RoundedCornerShape(15.dp))
                .clip(RoundedCornerShape(15.dp))
                .background(if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                if (kindLabel != null && dotColor != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = kindLabel,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = message.content,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun DateDivider(date: LocalDate) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = "${date.monthValue}月${date.dayOfMonth}日",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TypingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier
                .rinkShadow(RoundedCornerShape(15.dp))
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("帮你想一下…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
