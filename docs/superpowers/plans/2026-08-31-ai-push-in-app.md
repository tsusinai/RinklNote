# AI 主动推送打入 App 内落地 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AI 主动推送（月结 FR4 / 异常 FR5 / 习惯 FR6）在 App 内（AI 页聊天流）落地：新增习惯气泡与 `GET /api/insights/habit`、App 遵守「关闭 AI 主动推送」开关、并按「简洁美观、视觉良好」规格给系统气泡加标签与日期分隔。

**Architecture:** 复用已有 pull 模型：进入 AI 页 `onEnter` 按需注入。三处改动互不耦合——(1) 服务端加一个只读习惯端点 `habitForApp`（先 `habitReminder` 判空，再 `polishHabitCopy` 润色，抽象成可测 suspend 函数）；(2) App `AiViewModel.onEnter` 增加 `aiDisabled` 门控并新增 `loadHabitIfStale()`，同时把异常空态与尾换行清干净；(3) App `AiScreen` 按 kind 给系统气泡加「彩点 + 文本」标签、跨天日期分隔、胶囊内箭头图标发送、未登录 banner。`kind` 新增 `habit`（纯字符串，无 schema 迁移）。

**Tech Stack:** Ktor + Exposed (server)；Android Kotlin/Compose + Retrofit + Room (App)。测试命令 `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest :server:test`。

## Global Constraints

- 时区：一律 `Asia/Shanghai`（App 用现有 `bookkeepingZone()`，服务端用 `InsightService.SHANGHAI`）。
- `aiDisabled=true` 只抑制「主动推送」——月结/异常/习惯；**问账 + 聊天注入 + 欢迎语不受影响**（Q4=B）。
- 去重：App 本地 `countChatMessages`（月=yyyy-MM、天=yyyy-MM-dd）；QQ 端 `push_log` 独立（Q5=A）。
- 习惯端点返回 `{"content": string?}`，`content=null` 表示今日无习惯 → App 不插入（Q3=A）。
- 后端新 DTO 名 `HabitResponse(val content: String? = null)`，服务端 + App 各定义一份。
- 测试：App 用 Kotlin Coroutines Test；服务端用 `TestDatabase.connect(prefix)` + JUnit4。
- 提交：按路径显式暂存源码，不用 `add -A`，避免 server/build、app/build 产物入库。

---

## 已锁定的设计规格（下文按此实现，不再逐条询问）

### 视觉规格（简洁 / 美观 / 层级清楚）
- **系统消息气泡顶部加「彩点 + 文本」标签行**：8dp 圆点 + 12sp muted 文本，位于内容上方。仅「月结 summary / 异常 anomaly / 习惯 habit / 记账 booking」加；普通对话（user + AI 答问）不加，保持清爽。
  - 月结/记账 = `primary`（主色）；异常 = `error`（警示）；习惯 = `tertiary`。
- **跨天居中日期分隔**：如 `8月31日`，12sp muted，仅跨天时插入（首条消息也显示，用于锚定会话）。
- **异常空态**：`alerts` 为空就不插入气泡（不再显示「暂无异常提醒」），且不占去重位。
- **习惯内容为空**：`content=null/blank` 时不插入（不占去重位）；加载失败静默（soft 提醒，不打扰）。
- **未登录顶部 banner**：浅色圆角小卡片，替代原本 12sp 注脚。
- **底部输入区**：胶囊内右对齐箭头图标发送（`Icons.AutoMirrored.Filled.Send`），去掉实心大按钮，行更轻（mic 图标保留）。
- **微清理**：异常内容 `joinToString` 去尾部换行；月结/异常/习惯错误气泡仅月结、异常保留友好错误，习惯静默。

### 行为规格
- `onEnter(isLoggedIn, aiDisabled = false)`：greeting 全局一次（不受 aiDisabled 影响）；`if (isLoggedIn && !aiDisabled)` → 依次注入 月结(每窗=月一次) / 异常(每天一次) / 习惯(每天一次)。
- `ChatMessage.kind` 新增 `habit`；现有注释同步。

---

## File Structure

### 服务端 (server/src/main/kotlin/com/example/rinklnote/server)
- `services/insight/InsightService.kt` — 加 `HabitResponse` DTO 与 `habitForApp(userId, now)` suspend 函数。
- `routes/InsightRoutes.kt` — 加 `get("/habit")` 只读端点。

### App (app/src/main/java/com/example/rinklnote)
- `data/network/dto/DTOs.kt` — 加 `HabitResponse`。
- `data/network/ApiService.kt` — 加 `getHabit()`。
- `ui/viewmodel/AiViewModel.kt` — `onEnter` 加 `aiDisabled` 门控 + `loadHabitIfStale()`；异常空态 + 尾换行清理。
- `navigation/AppNavigation.kt` — 传 `authState.aiDisabled` 给 `aiVM.onEnter`。
- `ui/screen/ai/AiScreen.kt` — 视觉规格（标签行 / 日期分隔 / 图标发送 / banner）。

### 测试
- 服务端：`services/insight/InsightServiceTest.kt`（加 `habitForApp` 两用例）。
- App：`ui/viewmodel/AiViewModelTest.kt`（`FakeApiService` 加 `getHabit`；加习惯注入 / aiDisabled 抑制 / 异常空态不插 / 习惯空不插 四用例）。

---

## Task 1: 服务端 habitForApp + HabitResponse + /api/insights/habit

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/insight/InsightService.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/InsightRoutes.kt`
- Test: `server/src/test/kotlin/com/example/rinklnote/server/services/insight/InsightServiceTest.kt`

**Interfaces:**
- Produces: `InsightService.HabitResponse(content: String? = null)`（`@Serializable`，顶层 data class）；`InsightService.habitForApp(userId: Long, now: ZonedDateTime = ZonedDateTime.now(SHANGHAI)): HabitResponse`（suspend，判空 + 润色）；`GET /api/insights/habit`（JWT 内，返回 `HabitResponse`）。
- Consumes: `habitReminder(userId, now)`、`polishHabitCopy(habit)`（已存在）。

- [ ] **Step 1: 写失败测试 — habitForApp 有习惯时返回 content，无习惯时返回 null**

在 `InsightServiceTest.kt` 顶部补 import，并加两个用例：

```kotlin
import kotlinx.coroutines.runBlocking   // 顶部
import org.junit.Assert.assertNotNull   // 顶部（与已有 assertNull 并列）
```

```kotlin
@Test
fun `habitForApp returns content when habit present`() {
    val now = ZonedDateTime.of(2026, 8, 31, 12, 0, 0, 0, shanghai)
    insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 25))
    insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 26))
    insertBill(1L, 28.0, "三餐", startOfDay(2026, 8, 27))
    // polishHabitCopy 用 dummy LLM 会失败 → 回退模板文案，content 仍非空且含分类
    val r = runBlocking { insight.habitForApp(1L, now) }
    assertNotNull(r.content)
    assertTrue(r.content!!.contains("三餐"))
}

@Test
fun `habitForApp returns null content when no habit`() {
    val now = ZonedDateTime.of(2026, 8, 31, 12, 0, 0, 0, shanghai)
    val r = runBlocking { insight.habitForApp(1L, now) }
    assertNull(r.content)
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.insight.InsightServiceTest"`
Expected: FAIL — `habitForApp` 未定义。

- [ ] **Step 3: 实现 HabitResponse + habitForApp**

在 `InsightService.kt` 的 `QueryResponse` 之后加：

```kotlin
@Serializable
data class HabitResponse(
    val content: String? = null
)
```

在类内 `polishHabitCopy` 之后加（`habitForApp` 抽象「判空 + 润色」，路由与测试共用）：

```kotlin
/** App 端习惯提醒：无习惯 → null；有习惯 → 已润色文案。与 QQ 端算法/文案一致。 */
suspend fun habitForApp(userId: Long, now: ZonedDateTime = ZonedDateTime.now(SHANGHAI)): HabitResponse {
    val habit = habitReminder(userId, now)
    if (habit == null) return HabitResponse(null)
    return HabitResponse(polishHabitCopy(habit))
}
```

- [ ] **Step 4: 加路由 get("/habit")**

在 `InsightRoutes.kt` 的 `route("/api/insights")` 内、`get("/suggest")` 之前加：

```kotlin
get("/habit") {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("userId")?.asLong()
        ?: return@get call.respond(HttpStatusCode.Unauthorized)

    val result = insightService.habitForApp(userId)
    call.respond(result)
}
```

- [ ] **Step 5: 跑测试验证通过**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.insight.InsightServiceTest"`
Expected: PASS（原 4 用例 + 新 2 用例）。

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/insight/InsightService.kt \
        server/src/main/kotlin/com/example/rinklnote/server/routes/InsightRoutes.kt \
        server/src/test/kotlin/com/example/rinklnote/server/services/insight/InsightServiceTest.kt
git commit -m "feat(server): GET /api/insights/habit 只读端点(habitForApp 判空+润色)"
```

---

## Task 2: App DTO + ApiService.getHabit + 测试桩

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/network/ApiService.kt`
- Modify: `app/src/test/java/com/example/rinklnote/ui/viewmodel/AiViewModelTest.kt`（加 `getHabit` 桩，保证接口变更后仍编译）

**Interfaces:**
- Produces: `HabitResponse(content: String? = null)`（App 版）；`ApiService.getHabit(): HabitResponse`。

- [ ] **Step 1: App DTO**

在 `app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt` 加：

```kotlin
@Serializable
data class HabitResponse(val content: String? = null)
```

- [ ] **Step 2: ApiService 加端点**

```kotlin
@GET("api/insights/habit")
suspend fun getHabit(): HabitResponse
```

- [ ] **Step 3: 更新 AiViewModelTest 的 FakeApiService 桩 + import**

在 `AiViewModelTest.kt` 顶部 import 区（`AccountDTO`/`aiDisabledRequest` 附近）加：

```kotlin
import com.example.rinklnote.data.network.dto.HabitResponse
```

在 `FakeApiService` 实例字段区加：

```kotlin
var habitContent: String? = "「午餐」你常记 三餐 ¥28.00，今天记了吗？"
```

在 `FakeApiService` 方法区加：

```kotlin
override suspend fun getHabit(): HabitResponse = HabitResponse(habitContent)
```

- [ ] **Step 4: 跑 App 测试验证编译通过**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（既有用例不回归）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt \
        app/src/main/java/com/example/rinklnote/data/network/ApiService.kt \
        app/src/test/java/com/example/rinklnote/ui/viewmodel/AiViewModelTest.kt
git commit -m "feat(app): ApiService.getHabit + HabitResponse DTO（含测试桩）"
```

---

## Task 3: App AiViewModel — habit 注入 + aiDisabled 门控 + 异常空态/尾换行

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/AiViewModel.kt`
- Test: `app/src/test/java/com/example/rinklnote/ui/viewmodel/AiViewModelTest.kt`

**Interfaces:**
- Produces: `AiViewModel.onEnter(isLoggedIn: Boolean, aiDisabled: Boolean = false)`；私有 `loadHabitIfStale()`。
- Consumes: `ApiService.getHabit()`、`BillRepository.countChatMessages`。

- [ ] **Step 1: onEnter 加 aiDisabled 门控 + 调 habit**

改 `onEnter`（原 77-93 行）：

```kotlin
fun onEnter(isLoggedIn: Boolean, aiDisabled: Boolean = false) {
    viewModelScope.launch {
        if (repository.countChatMessages("greeting", 0L) == 0L) {
            repository.insertChatMessage(
                ChatMessage(
                    role = "assistant", kind = "greeting",
                    content = "你好，我是 AI 记账助手。直接发「午餐28元」记账；问我问题，例如「上个月交通花了多少」；登录后还能自动生成月总结和异常提醒。",
                    createdAt = now()
                )
            )
        }
        if (isLoggedIn && !aiDisabled) {
            loadMonthlyIfStale()
            loadAnomalyIfStale()
            loadHabitIfStale()
        }
    }
}
```

- [ ] **Step 2: 异常空态 + 尾换行清理（loadAnomalyIfStale）**

替换 `loadAnomalyIfStale`（原 173-199 行）内容：

```kotlin
private fun loadAnomalyIfStale() {
    viewModelScope.launch {
        val zone = bookkeepingZone()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        if (repository.countChatMessages("anomaly", dayStart) > 0) return@launch
        try {
            val r = api.getAnomalyAlerts()
            if (r.alerts.isEmpty()) return@launch   // 无异常不插「暂无」，不占去重位
            val content = buildString {
                append("异常提醒\n")
                append(r.alerts.joinToString("\n") { "• " + it.message })
            }
            repository.insertChatMessage(
                ChatMessage(role = "assistant", kind = "anomaly", content = content, createdAt = now())
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repository.insertChatMessage(
                ChatMessage(role = "assistant", kind = "text", content = friendlyError(e, "加载异常提醒失败"), createdAt = now())
            )
        }
    }
}
```

- [ ] **Step 3: 新增 loadHabitIfStale**

在 `loadAnomalyIfStale` 之后加：

```kotlin
/** 习惯提醒：每天一次；无内容/失败静默（soft 提醒，不打扰）。 */
private fun loadHabitIfStale() {
    viewModelScope.launch {
        val zone = bookkeepingZone()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        if (repository.countChatMessages("habit", dayStart) > 0) return@launch
        try {
            val r = api.getHabit()
            val content = r.content
            if (content.isNullOrBlank()) return@launch   // 今日无习惯 → 不插，不占去重位
            repository.insertChatMessage(
                ChatMessage(role = "assistant", kind = "habit", content = content, createdAt = now())
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 静默：习惯提醒加载失败不打扰用户，下次进入重试
        }
    }
}
```

- [ ] **Step 4: 新增测试用例**

在 `AiViewModelTest` 加：

```kotlin
@Test
fun `habit is injected once per day when logged in`() = runTest(dispatcher) {
    val vm = newVM()
    vm.onEnter(true, false)
    advanceUntilIdle()
    assertEquals(1, repo.chatMessages.value.count { it.kind == "habit" })

    vm.onEnter(true, false)
    advanceUntilIdle()
    assertEquals(1, repo.chatMessages.value.count { it.kind == "habit" })
}

@Test
fun `aiDisabled suppresses summary anomaly and habit but not greeting`() = runTest(dispatcher) {
    val vm = newVM()
    vm.onEnter(true, true)
    advanceUntilIdle()
    assertEquals(1, repo.chatMessages.value.count { it.kind == "greeting" })
    assertEquals(0, repo.chatMessages.value.count { it.kind == "summary" })
    assertEquals(0, repo.chatMessages.value.count { it.kind == "anomaly" })
    assertEquals(0, repo.chatMessages.value.count { it.kind == "habit" })
}

@Test
fun `empty anomaly is not injected`() = runTest(dispatcher) {
    fake.anomalyResult = AnomalyResponse(alerts = emptyList())
    val vm = newVM()
    vm.onEnter(true, false)
    advanceUntilIdle()
    assertEquals(0, repo.chatMessages.value.count { it.kind == "anomaly" })
}

@Test
fun `habit with null content is not injected`() = runTest(dispatcher) {
    fake.habitContent = null
    val vm = newVM()
    vm.onEnter(true, false)
    advanceUntilIdle()
    assertEquals(0, repo.chatMessages.value.count { it.kind == "habit" })
}
```

- [ ] **Step 5: 跑测试验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest --tests "com.example.rinklnote.ui.viewmodel.AiViewModelTest"`
Expected: PASS（原用例因 `onEnter` 默认参数 `aiDisabled=false` 不变；新增 4 用例通过）。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/ui/viewmodel/AiViewModel.kt \
        app/src/test/java/com/example/rinklnote/ui/viewmodel/AiViewModelTest.kt
git commit -m "feat(app): AI 页习惯提醒注入 + onEnter aiDisabled 门控 + 异常空态清理"
```

---

## Task 4: AppNavigation 传 aiDisabled

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt`

**Interfaces:**
- Consumes: `AuthState.aiDisabled`（已有）、`AiViewModel.onEnter(isLoggedIn, aiDisabled)`。

- [ ] **Step 1: 改 onEnter 调用**

把 `AppNavigation.kt` 原 162-166 行的：

```kotlin
LaunchedEffect(pagerState.currentPage, authState.isLoggedIn) {
    if (pagerState.currentPage == 4) {
        aiVM.onEnter(authState.isLoggedIn)
    }
}
```

改为：

```kotlin
LaunchedEffect(pagerState.currentPage, authState.isLoggedIn, authState.aiDisabled) {
    if (pagerState.currentPage == 4) {
        aiVM.onEnter(authState.isLoggedIn, authState.aiDisabled)
    }
}
```

- [ ] **Step 2: 编译 + 单测**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt
git commit -m "feat(app): AI 页入口传 authState.aiDisabled 进 onEnter"
```

---

## Task 5: App AiScreen 视觉规格落地

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/ai/AiScreen.kt`

**Interfaces:**
- Consumes: `ChatMessage.kind`（`text/booking/summary/anomaly/greeting/habit`）、`createdAt`。

- [ ] **Step 1: 补 import**

在 `AiScreen.kt` import 区加：

```kotlin
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.graphics.Color
import com.example.rinklnote.util.bookkeepingZone
import java.time.Instant
import java.time.LocalDate
```

- [ ] **Step 2: 未登录 banner + 日期分隔 + 系统气泡标签**

把 `AiScreen` 从「未登录注脚」到「LazyColumn items」替换为以下（注意：新增 `DateDivider`、`kindMeta` 仅在 ChatBubble 内联计算；日期分隔用 `itemsIndexed`）。

替换原 77-100 行（未登录提示 + LazyColumn items）：

```kotlin
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
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            itemsIndexed(state.messages, key = { _, msg -> msg.id }) { index, msg ->
                val msgDay = Instant.ofEpochMilli(msg.createdAt).atZone(zone).toLocalDate()
                val prevDay = if (index > 0) {
                    Instant.ofEpochMilli(state.messages[index - 1].createdAt).atZone(zone).toLocalDate()
                } else null
                if (prevDay == null || prevDay != msgDay) {
                    DateDivider(date = msgDay)
                }
                ChatBubble(message = msg)
            }
            if (state.isWaiting) {
                item(key = "typing") {
                    TypingBubble()
                }
            }
        }
```

- [ ] **Step 3: ChatBubble 加标签行 + DateDivider**

替换 `ChatBubble`（原 166-188 行），并在其下方新增 `DateDivider`：

```kotlin
@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val kindLabel: String? = when (message.kind) {
        "summary" -> "月结"
        "anomaly" -> "异常"
        "habit" -> "习惯"
        "booking" -> "记账"
        else -> null
    }
    val dotColor: Color? = when (message.kind) {
        "summary", "booking" -> MaterialTheme.colorScheme.primary
        "anomaly" -> MaterialTheme.colorScheme.error
        "habit" -> MaterialTheme.colorScheme.tertiary
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .shadow(2.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
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
```

- [ ] **Step 4: 底部输入区 — 胶囊内图标发送**

替换 `AiScreen` 的输入 Row（原 103-161 行）为：

```kotlin
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
            val aiInteraction = remember { MutableInteractionSource() }
            val aiFocused by aiInteraction.collectIsFocusedAsState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .border(
                        width = 1.dp,
                        color = if (aiFocused) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
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
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    val canSend = state.input.isNotBlank() && !state.isWaiting
                    IconButton(
                        onClick = { viewModel.onEvent(AiEvent.Send) },
                        enabled = canSend
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
```

> 说明：`Button("发送")` 移除，发送改为胶囊内箭头图标；`Spacer(width=8.dp)` 与 `Button` 一并删除。`BasicTextField` 高度由外层 `Box` 的 44dp 约束。

- [ ] **Step 5: 编译验证**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。（若有 `Icons.AutoMirrored.Filled.Send` 不可用，退回 `Icons.Filled.Send`——二者都在 material-icons-core。）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/ui/screen/ai/AiScreen.kt
git commit -m "feat(app): AI 页标签定位系统气泡+跨天日期分隔+胶囊内图标发送+未登录banner"
```

---

## Task 6: 全量验证 + 汇报

- [ ] **Step 1:** `export GRADLE_USER_HOME=D:/gradle && ./gradlew :app:testDebugUnitTest :server:test` 全绿。
- [ ] **Step 2:** `./gradlew :app:assembleDebug` 编译通过。
- [ ] **Step 3:** 冒烟（部署后）：登录状态进入 AI 页 → 月结/异常/习惯按需注入一次；「我的」关闭 AI 主动推送 → 重新进入 AI 页不再注入月结/异常/习惯（欢迎语仍在）；QQ 端主动推送不受 App 开关影响。
- [ ] **Step 4: 提交说明**：若 AI 页习惯不出现、报错或无内容，属预期（`suggest_config` 开启 + 今日未记 + 频次达标才触发；`content=null` 不插）。习惯端点需服务端部署后才可用；未部署时 App `getHabit()` 报错被静默吞掉，仅月结/异常可用。
