package com.example.rinklnote.ui.screen.autocapture

import androidx.compose.foundation.background
import com.example.rinklnote.ui.component.motionClickable
import com.example.rinklnote.ui.component.selectionColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import com.example.rinklnote.ui.theme.Motion
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.example.rinklnote.ui.component.RinklTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.BillCapture
import com.example.rinklnote.data.db.entity.BillCaptureStates
import com.example.rinklnote.data.local.SettingsManager
import com.example.rinklnote.notification.PayNotifyListenerService
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.SettingsGroupCard
import com.example.rinklnote.ui.component.SettingsRow
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import dev.chrisbanes.haze.HazeState
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 自动记账页（2026-10-03 方案 C）：支付通知捕获的权限引导、总开关、模式选择
 * （确认 / 静默，渐进信任解锁提示）与最近捕获台账（随手确认 / 忽略）。
 * 视觉规范收敛（V1）：字阶清零 SemiBold、统一 15dp 圆角与玻璃卡片材质、无重复货币符号。
 */
@Composable
fun AutoCaptureScreen(
    onBack: () -> Unit,
    viewModel: AutoCaptureViewModel,
    backgroundUri: String? = null,
    hazeState: HazeState? = null
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val effectiveHazeState = hazeState ?: remember { HazeState() }

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

    Box(modifier = Modifier.fillMaxSize()) {
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = effectiveHazeState)
        }
        Column(modifier = Modifier.fillMaxSize()) {
            AutoCaptureTopBar(hasBackground = backgroundUri != null, onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
            ) {
                Spacer(Modifier.height(10.dp))

                // ---- 权限与总开关卡片 ----
                SettingsGroupCard(title = "权限与开关") {
                    SettingsRow(
                        icon = R.drawable.ic_notification,
                        label = "通知使用权",
                        value = if (listenerGranted) "已开启" else "去开启",
                        onClick = if (!listenerGranted) {
                            { PayNotifyListenerService.openListenerSettings(context) }
                        } else null
                    )
                    RinklDivider()
                    SettingsRow(
                        label = "自动记账",
                        value = "监听微信/支付宝/银行支付通知",
                        trailing = {
                            Switch(
                                checked = state.masterEnabled,
                                onCheckedChange = viewModel::setMaster
                            )
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ---- 记账模式卡片 ----
                SettingsGroupCard(title = "处理模式") {
                    ModeSelectRow(
                        title = "确认模式",
                        desc = "每笔捕获弹通知，「记上 / 忽略」一键处置",
                        selected = state.mode == SettingsManager.AUTO_CAPTURE_MODE_CONFIRM,
                        onClick = { viewModel.setMode(SettingsManager.AUTO_CAPTURE_MODE_CONFIRM) }
                    )
                    RinklDivider()
                    ModeSelectRow(
                        title = "静默模式",
                        desc = "捕获直接入账不打扰，每晚小盘对账兜底",
                        selected = state.mode == SettingsManager.AUTO_CAPTURE_MODE_SILENT,
                        onClick = { viewModel.setMode(SettingsManager.AUTO_CAPTURE_MODE_SILENT) }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            state.suggestSilent -> "🎉 小盘看你自动记的 ${state.trustBookedCount} 笔都很稳（保留 ${state.trustKeptRatio}%），放心开静默模式啦"
                            else -> "静默模式建议先攒信任：已自动入账 ${state.trustBookedCount} 笔，保留率 ${state.trustKeptRatio}%"
                        },
                        fontSize = 12.sp,
                        color = if (state.suggestSilent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ---- 最近捕获台账 ----
                Text(
                    text = "最近捕获",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                Spacer(Modifier.height(8.dp))
                if (state.captures.isEmpty()) {
                    Text(
                        text = "还没有捕获到支付通知。开启开关并在微信/支付宝支付后，小盘会自动在这里帮你记账～",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                } else {
                    state.captures.forEach { capture ->
                        CaptureCard(
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
}

@Composable
private fun AutoCaptureTopBar(hasBackground: Boolean, onBack: () -> Unit) {
    val textColor = if (hasBackground) Color.White else LocalRinklColors.current.topBarTitleColor
    RinklTopBar(scrimAlpha = 0f, horizontalPadding = 8.dp) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .motionClickable(onClick = onBack),
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
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** 单选模式行（对齐标准 SettingsRow 行高与无障碍点击热区，无原生 RadioButton 边距冲突）。 */
@Composable
private fun ModeSelectRow(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalRinklColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .motionClickable(onClick = onClick)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 自定义单选指示小圆点
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(selectionColor(selected, colors.themeColor, MaterialTheme.colorScheme.surfaceVariant)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(visible = selected,
                enter = fadeIn(Motion.Reveal) + scaleIn(animationSpec = Motion.Reveal),
                exit = fadeOut(Motion.Reveal) + scaleOut(animationSpec = Motion.Reveal)) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 捕获记录卡片（统一 15dp 圆角与玻璃质感，边框联动，无硬编码 surfaceVariant）。 */
@Composable
private fun CaptureCard(capture: BillCapture, onConfirm: () -> Unit, onIgnore: () -> Unit) {
    val colors = LocalRinklColors.current
    val isIncome = capture.billType == "INCOME"
    // 严格遵循 Money.format 契约：Money.format 已自带或不带 ¥ 符号，不重复手写字面量 ¥
    val amountText = when {
        capture.amountMinor == null -> "金额未知"
        else -> (if (isIncome) "+" else "-") + Money.format(capture.amountMinor)
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
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = capture.merchant?.takeIf { it.isNotBlank() } ?: capture.notifTitle ?: "支付通知",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
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
            fontWeight = FontWeight.Bold,
            color = if (isIncome) colors.incomeColor else colors.expenseColor
        )
        if (capture.state == BillCaptureStates.PENDING) {
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onConfirm) { Text("记上", fontSize = 13.sp) }
            TextButton(onClick = onIgnore) { Text("忽略", fontSize = 13.sp) }
        }
    }
}

private val CAPTURE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")

/** 捕获时间展示（统一业务时区 M/d HH:mm）。 */
private fun formatCaptureTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(bookkeepingZone())
        .format(CAPTURE_TIME_FORMATTER)
