package com.example.rinklnote.ui.screen.autocapture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.data.db.entity.BillCapture
import com.example.rinklnote.data.db.entity.BillCaptureStates
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.notification.PayNotifyListenerService
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.util.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 自动记账页（2026-10-03 方案 C）：支付捕获的权限引导、总开关、模式选择
 * （确认 / 静默，渐进信任解锁提示）与最近捕获台账（随手确认 / 忽略）。
 */
@Composable
fun AutoCaptureScreen(
    onBack: () -> Unit,
    viewModel: AutoCaptureViewModel
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 权限态在回到前台时刷新（用户可能刚从系统设置授予回来）
    var listenerGranted by remember { mutableStateOf(PayNotifyListenerService.listenerGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listenerGranted = PayNotifyListenerService.listenerGranted(context)
                viewModel.refreshPermission(listenerGranted)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AutoCaptureTopBar(onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            PermissionCard(granted = listenerGranted)
            Spacer(Modifier.height(12.dp))
            MasterSwitchCard(enabled = state.masterEnabled, onToggle = viewModel::setMaster)
            Spacer(Modifier.height(12.dp))
            ModeCard(
                mode = state.mode,
                suggestSilent = state.suggestSilent,
                trustBookedCount = state.trustBookedCount,
                trustKeptRatio = state.trustKeptRatio,
                onPick = viewModel::setMode
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "最近捕获",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            if (state.captures.isEmpty()) {
                Text(
                    text = "还没有捕获到支付通知。开启开关并在微信/支付宝支付后，小盘会自动在这里帮你记账～",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.captures.forEach { capture ->
                    CaptureRow(
                        capture = capture,
                        onConfirm = { viewModel.confirmCapture(capture.id) },
                        onIgnore = { viewModel.ignoreCapture(capture.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AutoCaptureTopBar(onBack: () -> Unit) {
    val textColor = LocalRinklColors.current.topBarTitleColor
    RinklTopBar(scrimAlpha = 0f, horizontalPadding = 8.dp) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = "自动记账",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** 卡片容器：圆角 + 边框令牌（BORDER 默认透明时回落 surface 变体背景）。 */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    val colors = LocalRinklColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun PermissionCard(granted: Boolean) {
    val context = LocalContext.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (granted) "通知使用权已开启" else "需要「通知使用权」",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (granted) "小盘正在监听微信/支付宝/银行卡的支付通知（纯本地解析，不上传）"
                    else "授予后小盘才能读到支付通知并帮你记账",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!granted) {
                TextButton(onClick = { PayNotifyListenerService.openListenerSettings(context) }) {
                    Text("去开启")
                }
            }
        }
    }
}

@Composable
private fun MasterSwitchCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "自动记账",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "开启后按下方模式处理支付通知",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ModeCard(
    mode: String,
    suggestSilent: Boolean,
    trustBookedCount: Int,
    trustKeptRatio: Int,
    onPick: (String) -> Unit
) {
    SectionCard {
        ModeOption(
            title = "确认模式",
            desc = "每笔捕获弹通知，「记上 / 忽略」一键处置",
            selected = mode == SettingsManager.AUTO_CAPTURE_MODE_CONFIRM,
            onClick = { onPick(SettingsManager.AUTO_CAPTURE_MODE_CONFIRM) }
        )
        Spacer(Modifier.height(8.dp))
        ModeOption(
            title = "静默模式",
            desc = "捕获直接入账，不弹通知；每晚小盘对账兜底",
            selected = mode == SettingsManager.AUTO_CAPTURE_MODE_SILENT,
            onClick = { onPick(SettingsManager.AUTO_CAPTURE_MODE_SILENT) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                suggestSilent -> "🎉 小盘看你自动记的 $trustBookedCount 笔都很稳（保留 $trustKeptRatio%），放心开静默模式啦"
                else -> "静默模式建议先攒信任：已自动入账 $trustBookedCount 笔，保留率 $trustKeptRatio%"
            },
            fontSize = 12.sp,
            color = if (suggestSilent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModeOption(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = desc,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CaptureRow(capture: BillCapture, onConfirm: () -> Unit, onIgnore: () -> Unit) {
    val colors = LocalRinklColors.current
    val isIncome = capture.billType == "INCOME"
    val amountText = when {
        capture.amountMinor == null -> "金额未知"
        else -> (if (isIncome) "+" else "-") + "¥" + Money.format(capture.amountMinor)
    }
    val (stateLabel, stateColor) = when (capture.state) {
        BillCaptureStates.PENDING -> "待确认" to MaterialTheme.colorScheme.primary
        BillCaptureStates.BOOKED -> "已入账" to MaterialTheme.colorScheme.onSurfaceVariant
        BillCaptureStates.MERGED -> "已并入" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "已忽略" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = capture.merchant?.takeIf { it.isNotBlank() } ?: capture.notifTitle ?: "支付通知",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatCaptureTime(capture.occurredAt) + " · " + stateLabel,
                fontSize = 12.sp,
                color = stateColor
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = amountText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isIncome) colors.incomeColor else colors.expenseColor
        )
        if (capture.state == BillCaptureStates.PENDING) {
            TextButton(onClick = onConfirm) { Text("记上") }
            TextButton(onClick = onIgnore) { Text("忽略") }
        }
    }
}

/** 捕获时间展示（业务时区 M/d HH:mm）。 */
private fun formatCaptureTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("M/d HH:mm"))
