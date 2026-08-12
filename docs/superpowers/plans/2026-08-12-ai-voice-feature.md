# AI 页语音输入 + 输入框圆角 + 隐藏底部栏 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 AI 聊天页（pager index 4）加语音输入（复用现有底部语音条，识别结果直接走聊天路由）、把聊天输入框改成 24dp 胶囊圆角统一设计语言、并在「我的」(3) 与「AI」(4) 页整条隐藏底部导航栏。

**Architecture:** 全部改动在 App 侧，服务端与 `AiViewModel` 零改动。语音复用现有 `VoiceInputBar`（组件零改动），AppNavigation 加 `VoiceTarget` 枚举区分语音来源，AI 页发起 → `aiVM.send(text)`（走现有 route() 自动分记账/问账），QuickAdd 抽屉发起 → 维持 `NlpInput+NlpSubmit`。`AiScreen` 输入栏加麦克风 `IconButton` + `OutlinedTextField` 改 `RoundedCornerShape(24.dp)`。底部栏渲染外包 `currentPage < tabs.size` 条件（仅 计划/记账/资产 显示）。

**Tech Stack:** Kotlin + Compose Material3。复用 `VoiceInputBar`（设备 SpeechRecognizer + 服务端 Whisper 兜底）与 `R.drawable.ic_mic`。

**Spec:** `docs/superpowers/specs/2026-08-12-ai-voice-design.md`（已获批并提交 9c01b75）

## Global Constraints

- 全部 UI 文案为中文。
- 提交时按路径显式暂存源码，**禁止 `git add -A`**（避免 `server/build` 产物污染）。
- 每个 Task 结束时仓库必须能编译。
- 测试命令：`export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest :server:test`（`:app:test` 不可用）。
- **`VoiceInputBar` 组件零改动**；RECORD_AUDIO 运行时授权逻辑保持单点（AppNavigation `permissionLauncher` + `startVoice`）。
- AI 页 = pager index 4、我的页 = index 3；`tabs = listOf("计划", "记账", "资产")`，`tabs.size == 3`。
- `AiViewModel` 零改动；AI 页语音结果统一走 `aiVM.send(text)`。
- 输入框圆角 `RoundedCornerShape(24.dp)`，胶囊形；麦克风图标 `R.drawable.ic_mic`。
- 底部栏仅 index 0/1/2 显示；index 3/4 整条隐藏。
- 纯 Compose UI 接线无 JVM 单测可写（`AiViewModel` 未动）；验证 = 编译 + 现有全量单测全绿 + assembleDebug。
- 手工验证需真机：`adb shell input tap/keyevent` 在本设备抛 INJECT_EVENTS SecurityException（注入被拦截），交互由人工操作，界面用 `uiautomator dump` 验证。

---

### Task 1: 隐藏底部导航栏（我的/AI 页）

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt:269-273`

**Interfaces:**
- Consumes: `tabs`（文件级 `private val tabs = listOf("计划", "记账", "资产")`，L87）、`pagerState`、`tabWidth`、`onTabClick`。
- Produces: 无新接口；底部栏仅 index 0/1/2 渲染。

- [ ] **Step 1: 修改 CustomBottomBar 调用点加条件**

`app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt` 中，`Column { HorizontalPager(...) ... }` 内 `// Custom bottom navigation` 注释下的调用（当前 L269-273）：

```kotlin
            // Custom bottom navigation
            CustomBottomBar(
                currentIndex = pagerState.currentPage,
                tabWidth = tabWidth,
                onTabClick = onTabClick
            )
```

改为：

```kotlin
            // Custom bottom navigation — 仅 计划/记账/资产(0-2) 显示；我的(3)/AI(4) 整条隐藏
            if (pagerState.currentPage < tabs.size) {
                CustomBottomBar(
                    currentIndex = pagerState.currentPage,
                    tabWidth = tabWidth,
                    onTabClick = onTabClick
                )
            }
```

- [ ] **Step 2: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt
git commit -m "feat: 我的/AI 页隐藏底部导航栏（仅计划/记账/资产显示）"
```

---

### Task 2: AI 页语音输入 + 输入框胶囊圆角

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/ai/AiScreen.kt`
- Modify: `app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt`

**Interfaces:**
- Consumes: `AiViewModel`（`send(text)`，语音结果直接发消息）；`VoiceInputBar`（`api`、`onResult: (String)->Unit`、`onDismiss`，组件零改动）；`quickAddVM.onEvent(QuickAddEvent.NlpInput/NlpSubmit)`；`R.drawable.ic_mic`。
- Produces: `AiScreen(viewModel: AiViewModel, isLoggedIn: Boolean, onVoiceInput: () -> Unit)`；AppNavigation 新增 `private enum class VoiceTarget { QUICK_ADD, AI }`、`startVoice(target)`、`voiceTarget` 状态；`VoiceInputBar` onResult 按来源分派。

- [ ] **Step 1: AiScreen 签名 + 输入栏（麦克风 + 圆角输入框）**

`app/src/main/java/com/example/rinklnote/ui/screen/ai/AiScreen.kt`：

(a) 新增 imports（在现有 `androidx.compose.material3.OutlinedTextField` 等 import 之后）：

```kotlin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import com.example.rinklnote.R
```

(b) 签名（L40-44）加 `onVoiceInput` 参数：

```kotlin
@Composable
fun AiScreen(
    viewModel: AiViewModel,
    isLoggedIn: Boolean,
    onVoiceInput: () -> Unit
) {
```

(c) 输入栏 Row（L90-109，含 `OutlinedTextField` + `Spacer` + `Button("发送")`）整体替换为——左侧麦克风 `IconButton`，输入框加 `shape = RoundedCornerShape(24.dp)`：

```kotlin
        // 输入栏：麦克风 + 圆角胶囊输入框 + 发送
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onVoiceInput) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = "语音输入",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = state.input,
                onValueChange = { viewModel.onEvent(AiEvent.InputChanged(it)) },
                placeholder = { Text("输入记账或问题，如「午餐28元」", fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.onEvent(AiEvent.Send) },
                enabled = state.input.isNotBlank() && !state.isWaiting
            ) { Text("发送") }
        }
```

- [ ] **Step 2: AppNavigation 语音路由 + 接线**

`app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt`：

(a) 文件级 `tabs`（L87）后新增枚举：

```kotlin
private val tabs = listOf("计划", "记账", "资产")

private enum class VoiceTarget { QUICK_ADD, AI }
```

(b) 状态区（`var voiceActive by remember { mutableStateOf(false) }`，L99 附近）后新增：

```kotlin
    var voiceTarget by remember { mutableStateOf(VoiceTarget.QUICK_ADD) }
```

(c) 现有 `val onVoiceInput: () -> Unit = { ... }`（L213-222）整体替换为带目标的 `startVoice`：

```kotlin
    val startVoice: (VoiceTarget) -> Unit = { target ->
        voiceTarget = target
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showKeypad = false
            voiceActive = true
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
```

(d) `QuickAddDrawer` 调用（`onVoiceInput = onVoiceInput`）改为：

```kotlin
            onVoiceInput = { startVoice(VoiceTarget.QUICK_ADD) },
```

(e) `VoiceInputBar` 的 `onResult`（当前 L363-371）改为按来源分派——AI 页发起走聊天路由，QuickAdd 维持现状：

```kotlin
                VoiceInputBar(
                    api = app.apiService,
                    onResult = { text ->
                        voiceActive = false
                        val target = voiceTarget
                        voiceTarget = VoiceTarget.QUICK_ADD
                        if (target == VoiceTarget.AI) {
                            // 语音在 AI 页发起 → 走聊天路由（记账/问账自动判定）
                            aiVM.send(text)
                        } else {
                            // 抽屉语音记账：NLP 直填并提交
                            quickAddVM.onEvent(QuickAddEvent.NlpInput(text))
                            quickAddVM.onEvent(QuickAddEvent.NlpSubmit)
                        }
                    },
                    onDismiss = { voiceActive = false }
                )
```

(f) AI 页映射（`4 -> AiScreen(...)`）加 `onVoiceInput`：

```kotlin
                    4 -> AiScreen(
                        viewModel = aiVM,
                        isLoggedIn = authState.isLoggedIn,
                        onVoiceInput = { startVoice(VoiceTarget.AI) }
                    )
```

- [ ] **Step 3: 编译 + 全量单测**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（若编译器报未用 import 警告无碍，非 error）。

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest :server:test`
Expected: BUILD SUCCESSFUL（AiViewModelTest 9 / QuickAddViewModelTest 8 / BookkeepingViewModelTest 3 等全绿——AiViewModel 未改动）。

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL，产出 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/ui/screen/ai/AiScreen.kt app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt
git commit -m "feat: AI 页语音输入（复用语音条，结果走聊天路由）+ 输入框 24dp 胶囊圆角"
```

---

### Task 3: 真机手工验证（需用户操作）

输入注入被系统拦截，交互由人工完成，界面用 `adb shell uiautomator dump` 验证。

- [ ] **Step 1: 安装 + 进入 AI 页**

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell monkey -p com.example.rinklnote -c android.intent.category.LAUNCHER 1
```

人工：点击记账页右上角 AI 图标进入 AI 页。

- [ ] **Step 2: 逐项验证**

1. AI 输入框为胶囊圆角（24dp），与语音条圆角一致。
2. AI 页点麦克风 → 底部语音条出现 → 说「午餐28元」→ 自动发送 → user 气泡 + 「已记账：28元（三餐）」确认气泡。
3. AI 页点麦克风 → 说「上个月交通花了多少」→ user 气泡 + 「思考中…」→ AI 回答气泡。
4. QuickAdd 抽屉语音维持现状（录音 → 自动记账）。
5. 「我的」「AI」页底部导航栏整条隐藏；计划/记账/资产 仍显示。
6. AI 页返回键回记账页；顶栏 AI 图标可进入。
