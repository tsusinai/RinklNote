# AI 页语音输入 + 输入框圆角 + 隐藏底部栏 — Design

**Date:** 2026-08-12
**Status:** Approved

## 目标

对 AI 聊天页（pager index 4）做三项改进：
1. 支持语音输入（复用现有语音条），识别结果直接作为聊天消息发送，自动路由记账/问账。
2. 输入框改为胶囊形圆角，统一应用圆角设计语言。
3. 在「我的」（index 3）与「AI」（index 4）页隐藏底部导航栏，内容占满全屏。

全部改动在 App 侧，服务端零改动。`AiViewModel` 零改动（语音走现有 `send(text)` → `route()`）。

## 1. AI 页语音输入

**交互流：** AI 输入栏右侧（发送按钮左侧）加麦克风图标 → 点击回调 `onVoiceInput()` → AppNavigation 复用现有 RECORD_AUDIO 运行时授权流程 + 底部 `VoiceInputBar` 悬浮条（设备 SpeechRecognizer 实时识别流式反馈，失败自动上传服务端 Whisper 兜底）→ 识别完成 `onResult(text)` → 按来源路由。

**路由规则：**
- AI 页发起：`aiVM.send(text)` → 现有 `route()` 判定——带金额且含 元/块/分类 → 记账（对话内确认消息）；否则 → 问账（AI 回答）。语音「午餐二十八」→ 含「午餐」分类 → 记账；「上个月交通花了多少」→ 无金额 → 问账。
- QuickAdd 抽屉发起：维持现状 `NlpInput(text)` + `NlpSubmit(text)`。

**改动点：**
- `AppNavigation.kt`：
  - 新增 `var voiceTarget by remember { mutableStateOf(VoiceTarget.QUICK_ADD) }`（`private enum class VoiceTarget { QUICK_ADD, AI }`，文件级）。
  - 现有 `onVoiceInput` 抽成 `startVoice(target: VoiceTarget)`：设 `voiceTarget = target`，再做权限检查/授权 → `voiceActive = true`。
  - `VoiceInputBar` 的 `onResult`：`voiceActive = false` 后按 `voiceTarget` 路由（AI → `aiVM.send(text)`；QUICK_ADD → 现状）。路由后用 `voiceTarget = QUICK_ADD` 复位。
- `AiScreen.kt`：
  - 签名 `AiScreen(viewModel, isLoggedIn, onVoiceInput: () -> Unit)`。
  - 输入栏改为 `Row { 麦克风IconButton(onVoiceInput) / 胶囊输入框(weight 1f) / 发送Button }`。麦克风图标用现有 `R.drawable.ic_mic`。
- `VoiceInputBar.kt`：**零改动**。

**已知取舍：** 语音条悬浮定位 `padding(bottom = 56.dp)` 原为清开 32dp 底部栏；AI 页底部栏隐藏后语音条会略偏高（约 56dp），视觉可接受，v1 不做参数化。

## 2. 输入框圆角（统一规范）

- `AiScreen` 的 `OutlinedTextField` 增加 `shape = RoundedCornerShape(24.dp)`（胶囊形），边框沿用 `MaterialTheme.colorScheme.outline` 默认即可。
- 圆角语言统一：聊天气泡 18dp / 语音条 24dp / 输入框 24dp。
- 发送按钮维持 Material3 默认 `Button`（本身已是胶囊）。
- 输入框内 `singleLine = true` 维持；高度维持 M3 `OutlinedTextField` 默认 56dp，24dp 圆角在 56dp 高度上呈圆角胶囊，不改高度。

## 3. 隐藏底部导航栏（我的 + AI 页）

- `AppNavigation.kt`：`CustomBottomBar` 渲染外包一层 `if (pagerState.currentPage < tabs.size)`（`tabs.size == 3`），即仅 计划(0)/记账(1)/资产(2) 显示；我的(3)/AI(4) 整条隐藏。
- 现有 `CustomBottomBar` 内部 `if (currentIndex < tabs.size)` 的 indicator 隐藏逻辑保留（防御性，隐藏整条后不再触发）。
- 隐藏后 pager 占满整屏，聊天更沉浸。返回键逻辑不受影响（非记账页返回 → 记账页）。

## 4. 验证

- 单测：`AiViewModel` 无改动，跑全量 `./gradlew :app:testDebugUnitTest :server:test` 全绿。
- 构建：`./gradlew assembleDebug` 成功，产出 app-debug.apk。
- 真机手工清单：
  1. AI 页点麦克风 → 底部语音条出现 → 说「午餐28元」→ 自动发消息 → user 气泡 + 「已记账：28元（三餐）」确认气泡。
  2. AI 页点麦克风 → 说「上个月交通花了多少」→ user 气泡 + 「思考中…」→ AI 回答气泡。
  3. QuickAdd 抽屉语音维持现状（录音 → 自动记账）。
  4. AI 页输入框为胶囊圆角，与语音条圆角一致。
  5. 「我的」「AI」页底部导航栏整条隐藏；计划/记账/资产仍显示。
  6. AI 页返回键回记账页；顶栏 AI 图标可进入。
