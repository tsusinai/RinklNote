# AI 助手接口（App 端）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App 端配套：设置页「AI 助手接口」生成/列/作废个人访问令牌（沿用 JWT），并支持 `rinklnote://add` 深链一键预填快速记账抽屉（金额/分类/备注/类型），App 内确认保存。

**Architecture:** 复用现有 `RetrofitClient`（JWT Authorization 拦截器）调服务器 `/api/ai/tokens*`（令牌管理走 JWT）。深链复用现有「小组件点分类 → 打开抽屉预选」通路：把 `RinklNoteApp` 上单一的 `pendingQuickAddCategoryId: StateFlow<Long?>` 泛化为 `pendingQuickAdd: StateFlow<PendingQuickAdd?>`（携带 amount/categoryId/categoryName/remark/billType），`MainActivity` 从 Intent（深链 URI 或小组件 extra）构造 `PendingQuickAdd`，`AppNavigation` 消费后落到记账页并打开抽屉，`QuickAddViewModel` 新增 `applyQuickAddPrefill(prefill)` 应用预填。无新增第三方依赖。

**Tech Stack:** Kotlin + Jetpack Compose + Material3 + Retrofit/kotlinx.serialization + Android 深链（自定义 scheme）+ Clipboard。

## Global Constraints

- Kotlin 2.0.21 / AGP 8.13.0 / minSdk 28；无 DI，单例经 `RinklNoteApp`（service locator）。
- 令牌管理端点走现有登录 JWT（`RetrofitClient.create` 已加 `Authorization: Bearer <jwt>`），App 侧直接调，无需另加头部。
- `rinklnote://add` 后用 Android `Uri.getQueryParameter` 解析；参数缺省时**打开抽屉并落到默认分类（三餐）**，不阻塞。
- 深链 scheme 用 `rinklnote`、host 用 `add`；`MainActivity` 已是 `launchMode="singleTop"`，走 `onCreate` + `onNewIntent` 双路径。
- 个人令牌明文**仅生成时返回一次**；列表接口不含明文。App 端生成后立即展示并「复制」，并提示只显示这一次。
- 设置页沿用现有 `ProfileScreen` 的 `GroupCard`/`SettingsRow`/`AlertDialog` 模式；新增的令牌 UI 为独立 `AiTokenDialog`（可滚动 AlertDialog），避免继续膨胀 `ProfileScreen`。
- **本机联跑受限**：App 无便捷 JVM 单测目标；且本机 Gradle JVM 测试常因 Java24 + 用户路径含撇号 `a'su's` 而失败。验证以 `./gradlew :app:assembleDebug`（编译打包）+ `./gradlew :app:lint`（无新增错误）+ 模拟器 `adb`/手动实测为主。所有任务验证给出确切命令。

---

### Task 1: 深链 `rinklnote://add` —— 统一 `PendingQuickAdd` 预填管道

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/RinklNoteApp.kt`（`pendingQuickAddCategoryId` → `pendingQuickAdd: StateFlow<PendingQuickAdd?>` + `PendingQuickAdd` 数据类）
- Modify: `app/src/main/AndroidManifest.xml`（`MainActivity` 加 VIEW intent-filter）
- Modify: `app/src/main/java/com/example/rinklnote/MainActivity.kt`（解析深链/小组件 extra → `PendingQuickAdd`）
- Modify: `app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt`（新增 `applyQuickAddPrefill` + `pendingPrefill`）
- Modify: `app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt`（消费 `PendingQuickAdd`）

**Interfaces:**
- Consumes: `EXTRA_OPEN_QUICK_ADD`/`EXTRA_CATEGORY_ID`（`RinklNoteAppWidgetReceiver`）；`QuickAddViewModel.preselectCategory(Long)`（现有）；现有 expense/income collector。
- Produces:
  - `data class PendingQuickAdd(amount: String?, categoryId: Long?, categoryName: String?, remark: String?, billType: String?)`（`RinklNoteApp.kt` 顶层）
  - `RinklNoteApp.pendingQuickAdd: StateFlow<PendingQuickAdd?>`、`fun setPendingQuickAdd(PendingQuickAdd?)`
  - `QuickAddViewModel.applyQuickAddPrefill(prefill: PendingQuickAdd)`

- [ ] **Step 1: `RinklNoteApp.kt` — 定义 `PendingQuickAdd` 并替换状态**

把现有 `RinklNoteApp.kt:42-47` 的状态（`_pendingQuickAddCategoryId` / `pendingQuickAddCategoryId` / `setPendingQuickAdd(categoryId: Long?)`）替换为：

```kotlin
    // 主屏小组件点分类 / 深链 rinklnote://add → 欲预填快速记账抽屉的参数；
    // MainActivity 从 Intent extra / deep-link URI 装入，AppNavigation 消费后清空。
    private val _pendingQuickAdd = MutableStateFlow<PendingQuickAdd?>(null)
    val pendingQuickAdd: StateFlow<PendingQuickAdd?> = _pendingQuickAdd.asStateFlow()

    fun setPendingQuickAdd(pending: PendingQuickAdd?) {
        _pendingQuickAdd.value = pending
    }
```

文件顶部（`RinklNoteApp` 类外，`package` 后）新增：

```kotlin
/**
 * 打开快速记账抽屉时的预填参数。`categoryId` 来自主屏小组件；其余字段来自
 * `rinklnote://add` 深链。全部字段可空：全空表示「打开抽屉并落到默认分类」。
 */
data class PendingQuickAdd(
    val amount: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val remark: String? = null,
    val billType: String? = null
)
```

- [ ] **Step 2: `AndroidManifest.xml` — 加 VIEW intent-filter**

在 `MainActivity`（`launchMode="singleTop"`）的 `<activity>` 内，现有 `<intent-filter>`（MAIN/LAUNCHER）之后追加一个：

```xml
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />

                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />

                <data
                    android:scheme="rinklnote"
                    android:host="add" />
            </intent-filter>
```

- [ ] **Step 3: `MainActivity.kt` — 解析深链与小组件 extra**

把 `consumeLaunchIntent` 及其调用改为统一产出 `PendingQuickAdd`，并新增两个解析函数：

```kotlin
    private fun consumeLaunchIntent(app: RinklNoteApp, intent: Intent?) {
        app.setPendingQuickAdd(parseDeepLink(intent) ?: parseWidgetExtra(intent))
    }

    /** `rinklnote://add?amount=&category=&remark=&type=` → 预填参数；命中即返回（即使全缺省也打开抽屉）。 */
    private fun parseDeepLink(intent: Intent?): PendingQuickAdd? {
        val data = intent?.data ?: return null
        if (!data.scheme.equals("rinklnote", ignoreCase = true) || data.host != "add") return null
        return PendingQuickAdd(
            amount = data.getQueryParameter("amount")?.takeIf { it.isNotBlank() },
            categoryName = data.getQueryParameter("category")?.takeIf { it.isNotBlank() },
            remark = data.getQueryParameter("remark")?.takeIf { it.isNotBlank() },
            billType = data.getQueryParameter("type")?.takeIf { it.isNotBlank() }
        )
    }

    /** 主屏小组件点分类 → 预选分类 id；未指定分类则不打扰（返回 null，不开抽屉）。 */
    private fun parseWidgetExtra(intent: Intent?): PendingQuickAdd? {
        if (intent?.getBooleanExtra(EXTRA_OPEN_QUICK_ADD, false) == true) {
            val catId = if (intent.hasExtra(EXTRA_CATEGORY_ID)) intent.getLongExtra(EXTRA_CATEGORY_ID, -1L) else -1L
            return if (catId >= 0) PendingQuickAdd(categoryId = catId) else null
        }
        return null
    }
```

`consumeLaunchIntent` 现在可直接复用；`onCreate`/`onNewIntent` 的调用不变。

- [ ] **Step 4: `QuickAddViewModel.kt` — 新增 `applyQuickAddPrefill`（深链预填）**

新增字段（放在现有 `pendingPreselectId` 旁）与两个方法。字段：

```kotlin
    // 深链预填参数：expense/income 分类冷启动尚未加载时暂存，待加载到位后再应用。
    private var pendingPrefill: PendingQuickAdd? = null
```

新增方法（放在 `preselectCategory` 之后）：

```kotlin
    /**
     * `rinklnote://add` 深链 → 预填金额/备注/类型并选中分类（按 id 或名称）。
     * 分类全集未加载完成（冷启动）时先暂存 pendingPrefill，由 collector 加载到位后应用。
     * 全空参数 → 打开抽屉并落到当前类型的默认首分类（保持「缺省不阻塞」语义）。
     */
    fun applyQuickAddPrefill(prefill: PendingQuickAdd) {
        subCategoryLoadJob?.cancel()
        pendingPrefill = prefill
        applyPendingPrefillIfReady()
    }

    /** 分类已加载到位时应用暂存的预填；未就绪（目标类型分类还没加载）则等待下次 collector。 */
    private fun applyPendingPrefillIfReady() {
        val p = pendingPrefill ?: return
        val s = _state.value
        val type = p.billType ?: p.categoryName?.let { n ->
            (s.expenseCategories + s.incomeCategories).firstOrNull { it.name == n }?.billType
        } ?: "EXPENSE"
        val expectedCats = if (type == "INCOME") s.incomeCategories else s.expenseCategories
        val allCats = s.expenseCategories + s.incomeCategories
        val hasCategoryRef = p.categoryId != null || p.categoryName != null
        val target = when {
            p.categoryId != null -> allCats.firstOrNull { it.id == p.categoryId }
            p.categoryName != null -> allCats.firstOrNull { it.name == p.categoryName }
            else -> null
        }
        // 指定了分类但目标类型的分类还没加载 → 等 collector，避免拿空列表兜底成首分类。
        if (hasCategoryRef && target == null && expectedCats.isEmpty()) return
        _state.update {
            it.copy(
                amount = p.amount ?: "",
                billType = type,
                remark = p.remark ?: "",
                selectedCategory = target ?: expectedCats.firstOrNull(),
                selectedSubCategory = null,
                showSubCategories = false,
                expandedParentId = null,
                subCategories = emptyList()
            )
        }
        pendingPrefill = null
    }
```

在 `expenseCategories.collect` 与 `incomeCategories.collect` 两个 `_state.update { ... }` 之后各追加一行 `applyPendingPrefillIfReady()`（让冷启动时分类到位后触发一次预填）。

- [ ] **Step 5: `AppNavigation.kt` — 消费 `PendingQuickAdd`**

把第 168 行改为：

```kotlin
    val pendingQuickAdd by app.pendingQuickAdd.collectAsStateWithLifecycle()
```

把第 199-207 行 `LaunchedEffect(pendingQuickAdd)` 改为：

```kotlin
    // 主屏小组件点分类 / 深链 rinklnote://add：落到记账页并打开 QuickAdd 抽屉应用预填。
    // 起点即 "bookkeeping"，导航到同 start 路由不会触发上面「离开记账页才关抽屉」的效果，故时序安全。
    LaunchedEffect(pendingQuickAdd) {
        val pending = pendingQuickAdd ?: return@LaunchedEffect
        navController.navigate("bookkeeping") { launchSingleTop = true }
        showDrawer = true
        if (pending.categoryId != null) {
            // 小组件：仅带分类 id → 复用现有预选通路（行为不变）。
            quickAddVM.preselectCategory(pending.categoryId)
        } else {
            // 深链：金额/分类名/备注/类型 → 应用完整预填。
            quickAddVM.applyQuickAddPrefill(pending)
        }
        app.setPendingQuickAdd(null)
    }
```

- [ ] **Step 6: 编译 + lint**

Run: `./gradlew :app:assembleDebug`（编译打包通过）＋ `./gradlew :app:lint`（无新增错误）。
Expected: 两命令 PASS。若本机 Gradle 因环境受限，以 `assembleDebug` 编译通过为准。

- [ ] **Step 7: 实测深链**

```bash
# 安装 debug 到模拟器/真机
./gradlew :app:installDebug

# 1) 带金额+分类：应打开抽屉并预填金额 20、选中 三餐、备注"午餐"
adb shell am start -a android.intent.action.VIEW -d 'rinklnote://add?amount=20&category=三餐&remark=午餐'

# 2) 只带分类：预选分类
adb shell am start -a android.intent.action.VIEW -d 'rinklnote://add?category=交通'

# 3) 全缺省：打开抽屉落到默认分类（三菜）
adb shell am start -a android.intent.action.VIEW -d 'rinklnote://add'

# 4) 冷启动（进程未运行）直接深链：应打开 App 并预填（验证 onCreate + 冷启动 collector 兜底）
adb shell am force-stop com.example.rinklnote
adb shell am start -a android.intent.action.VIEW -d 'rinklnote://add?amount=20&category=三餐'
```

Expected: 每次打开抽屉预填正确；确认保存后正常入库；主屏小组件金额随之刷新（复用现有 `onBillMutated`）。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/RinklNoteApp.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/java/com/example/rinklnote/MainActivity.kt \
        app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt \
        app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt
git commit -m "feat(app): rinklnote://add 深链预填快速记账抽屉（PendingQuickAdd 统一小组件/深链管道）"
```

---

### Task 2: 设置页「AI 助手接口」—— 个人令牌管理 UI

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt`（新增 3 个 DTO）
- Modify: `app/src/main/java/com/example/rinklnote/data/network/ApiService.kt`（4 个令牌管理端点）
- Create: `app/src/main/java/com/example/rinklnote/ui/viewmodel/AiTokenViewModel.kt`
- Create: `app/src/main/java/com/example/rinklnote/ui/screen/profile/AiTokenDialog.kt`
- Modify: `app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt`（创建 `AiTokenViewModel` 传入 ProfileScreen）
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/profile/ProfileScreen.kt`（+`aiTokenViewModel` 参数、`AccountCard` +「AI 助手接口」行、`showAiToken` 弹窗）

**Interfaces:**
- Consumes: `ApiService`（JWT）；`RetrofitClient.BASE_URL`；`MessageResponse`。
- Produces:
  - DTO: `AiGenerateTokenRequest(name = "小爱")`、`AiTokenResponse(id, token, name, createdAt)`、`AiTokenItem(id, name, createdAt, revoked, revokedAt?)`
  - `ApiService.generateAiToken/listAiTokens/revokeAiToken/revokeAllAiTokens`
  - `AiTokenViewModel(api)` + `Factory(api)`；`AiTokenState`/`AiTokenEvent`
  - `AiTokenDialog(viewModel: AiTokenViewModel, onDismiss: () -> Unit)`

- [ ] **Step 1: DTOs.kt — 新增令牌 DTO**

在 `app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt` 末尾追加：

```kotlin
@Serializable
data class AiGenerateTokenRequest(val name: String = "小爱")

/** 生成结果：明文 token 仅在此时返回一次，服务端只存 SHA-256 哈希。 */
@Serializable
data class AiTokenResponse(
    val id: Long,
    val token: String,
    val name: String,
    val createdAt: Long
)

/** 列表项：不含明文 token。 */
@Serializable
data class AiTokenItem(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val revoked: Boolean,
    val revokedAt: Long? = null
)
```

- [ ] **Step 2: ApiService.kt — 新增端点**

在 `app/src/main/java/com/example/rinklnote/data/network/ApiService.kt` 的 `getHabit` 之后追加：

```kotlin
    // AI 助手接口 — 个人访问令牌管理（沿用 JWT 登录态）
    @POST("api/ai/tokens")
    suspend fun generateAiToken(@Body request: AiGenerateTokenRequest): AiTokenResponse

    @GET("api/ai/tokens")
    suspend fun listAiTokens(): List<AiTokenItem>

    @POST("api/ai/tokens/{id}/revoke")
    suspend fun revokeAiToken(@Path("id") id: Long): MessageResponse

    @POST("api/ai/tokens/revoke-all")
    suspend fun revokeAllAiTokens(): MessageResponse
```

- [ ] **Step 3: AiTokenViewModel.kt**

`app/src/main/java/com/example/rinklnote/ui/viewmodel/AiTokenViewModel.kt`：

```kotlin
package com.example.rinklnote.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rinklnote.data.network.ApiService
import com.example.rinklnote.data.network.dto.AiGenerateTokenRequest
import com.example.rinklnote.data.network.dto.AiTokenItem
import com.example.rinklnote.data.network.dto.AiTokenResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class AiTokenState(
    val tokens: List<AiTokenItem> = emptyList(),
    val name: String = "小爱",
    val newToken: AiTokenResponse? = null,   // 刚生成的明文，仅展示一次
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: String? = null
)

sealed interface AiTokenEvent {
    data object Load : AiTokenEvent
    data class NameChanged(val value: String) : AiTokenEvent
    data object Generate : AiTokenEvent
    data class Revoke(val id: Long) : AiTokenEvent
    data object RevokeAll : AiTokenEvent
    data object DismissToken : AiTokenEvent
}

class AiTokenViewModel(private val api: ApiService) : ViewModel() {

    private val _state = MutableStateFlow(AiTokenState())
    val state: StateFlow<AiTokenState> = _state.asStateFlow()

    init { load() }

    fun onEvent(event: AiTokenEvent) {
        when (event) {
            AiTokenEvent.Load -> load()
            is AiTokenEvent.NameChanged -> _state.update { it.copy(name = event.value, error = null, success = null) }
            AiTokenEvent.Generate -> generate()
            is AiTokenEvent.Revoke -> revoke(event.id)
            AiTokenEvent.RevokeAll -> revokeAll()
            // 明文只展示一次：关闭后从状态里清掉，避免误留屏。
            AiTokenEvent.DismissToken -> _state.update { it.copy(newToken = null) }
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, error = null, success = null) }
        viewModelScope.launch {
            try {
                val tokens = api.listAiTokens()
                _state.update { it.copy(tokens = tokens, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = friendly(e, "加载令牌失败")) }
            }
        }
    }

    private fun generate() {
        val name = _state.value.name.trim().ifBlank { "小爱" }
        _state.update { it.copy(isLoading = true, error = null, success = null, newToken = null) }
        viewModelScope.launch {
            try {
                val resp = api.generateAiToken(AiGenerateTokenRequest(name))
                _state.update { it.copy(isLoading = false, newToken = resp, success = "已生成，请立即复制") }
                load()   // 刷新列表
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = friendly(e, "生成令牌失败")) }
            }
        }
    }

    private fun revoke(id: Long) {
        viewModelScope.launch {
            try {
                api.revokeAiToken(id)
                _state.update { it.copy(success = "已作废") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = friendly(e, "作废失败")) }
            } finally {
                load()
            }
        }
    }

    private fun revokeAll() {
        viewModelScope.launch {
            try {
                api.revokeAllAiTokens()
                _state.update { it.copy(success = "已全部作废") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = friendly(e, "作废失败")) }
            } finally {
                load()
            }
        }
    }

    private fun friendly(e: Exception, prefix: String): String =
        when ((e as? retrofit2.HttpException)?.code()) {
            401 -> "登录已失效，请先在「我的」重新登录"
            else -> "$prefix: ${e.message}"
        }

    class Factory(private val api: ApiService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiTokenViewModel(api) as T
    }
}
```

- [ ] **Step 4: AiTokenDialog.kt**

`app/src/main/java/com/example/rinklnote/ui/screen/profile/AiTokenDialog.kt`：

```kotlin
package com.example.rinklnote.ui.screen.profile

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.data.network.RetrofitClient
import com.example.rinklnote.ui.viewmodel.AiTokenEvent
import com.example.rinklnote.ui.viewmodel.AiTokenViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AiTokenDialog(viewModel: AiTokenViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.onEvent(AiTokenEvent.Load) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 助手接口") },
        text = {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                UsageCard()
                Spacer(Modifier.height(12.dp))
                // 生成新令牌
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = { viewModel.onEvent(AiTokenEvent.NameChanged(it)) },
                        label = { Text("令牌名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { viewModel.onEvent(AiTokenEvent.Generate) },
                        enabled = !state.isLoading
                    ) { Text("生成") }
                }
                if (state.newToken != null) {
                    Spacer(Modifier.height(10.dp))
                    NewTokenCard(
                        token = state.newToken!!,
                        onCopy = {
                            clipboard.setText(AnnotatedString(state.newToken!!.token))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                        onDone = { viewModel.onEvent(AiTokenEvent.DismissToken) }
                    )
                }
                state.success?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Text("已生成令牌", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                TokenList(
                    tokens = state.tokens,
                    onRevoke = { viewModel.onEvent(AiTokenEvent.Revoke(it)) },
                    onRevokeAll = { viewModel.onEvent(AiTokenEvent.RevokeAll) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun UsageCard() {
    val endpoint = RetrofitClient.BASE_URL.trimEnd('/') + "/api/ai/ask"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("给小爱等手机 AI 用：", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text("接口地址：$endpoint", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "请求头 Authorization: Bearer <令牌>，body {\"text\":\"午餐20元\"}。",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "在小爱技能里粘贴令牌即可免打扰记账/问账。",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NewTokenCard(
    token: com.example.rinklnote.data.network.dto.AiTokenResponse,
    onCopy: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("新令牌（只显示这一次，请立即复制）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            token.token,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCopy) { Text("复制") }
            OutlinedButton(onClick = onDone) { Text("我已保存") }
        }
    }
}

@Composable
private fun TokenList(
    tokens: List<com.example.rinklnote.data.network.dto.AiTokenItem>,
    onRevoke: (Long) -> Unit,
    onRevokeAll: () -> Unit
) {
    if (tokens.isEmpty()) {
        Text("暂无令牌", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        tokens.forEach { t ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !t.revoked) { onRevoke(t.id) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${t.name}${if (t.revoked) "（已作废）" else ""}", fontSize = 14.sp)
                    Text(
                        resolveStamp(t.createdAt),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!t.revoked) {
                    TextButton(onClick = { onRevoke(t.id) }) { Text("作废", color = MaterialTheme.colorScheme.error) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onRevokeAll,
            modifier = Modifier.fillMaxWidth()
        ) { Text("全部作废", color = MaterialTheme.colorScheme.error) }
    }
}

private fun resolveStamp(epochMillis: Long): String =
    if (epochMillis <= 0) "从未"
    else Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
```

- [ ] **Step 5: AppNavigation.kt — 创建并传入 AiTokenViewModel**

在 AppNavigation.kt 的 `aiVM` 声明之后新增：

```kotlin
    val aiTokenVM: com.example.rinklnote.ui.viewmodel.AiTokenViewModel = viewModel(
        factory = com.example.rinklnote.ui.viewmodel.AiTokenViewModel.Factory(app.apiService)
    )
```

在 `composable("profile") { ProfileScreen(...) }` 里给 `ProfileScreen` 传参 `aiTokenViewModel = aiTokenVM`。

- [ ] **Step 6: ProfileScreen.kt — 接收参数 + 加「AI 助手接口」行 + 弹窗**

签名加参（第 77-86 行）：

```kotlin
@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    settingsManager: SettingsManager,
    tokenManager: TokenManager,
    syncManager: SyncManager,
    repository: BillRepository,
    aiTokenViewModel: com.example.rinklnote.ui.viewmodel.AiTokenViewModel,
    onLoginClick: () -> Unit,
    onBindQQClick: () -> Unit,
    onQqBotGuideClick: () -> Unit
)
```

函数体加状态（`showUnsynced` 附近）：

```kotlin
    var showAiToken by remember { mutableStateOf(false) }
```

`item(key = "account")` 的 `AccountCard(...)` 传参追加 `onAiTokenClick = { showAiToken = true }`。

`AccountCard`（第 468-533 行）签名加 `onAiTokenClick: () -> Unit`，在「AI 主动推送」行之前插入：

```kotlin
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        SettingsRow(
            icon = R.drawable.ic_ai,
            label = "AI 助手接口",
            value = "个人访问令牌",
            onClick = onAiTokenClick
        )
```

在弹窗区末尾（`showUnsynced` 块之后）加：

```kotlin
    if (showAiToken) {
        AiTokenDialog(viewModel = aiTokenViewModel, onDismiss = { showAiToken = false })
    }
```

文件顶部补 import：`import com.example.rinklnote.ui.screen.profile.AiTokenDialog`（同包可省略）——新增文件在同包 `ui.screen.profile`，无需 import。

- [ ] **Step 7: 编译 + lint + 实测**

Run: `./gradlew :app:assembleDebug`（编译打包通过）＋ `./gradlew :app:lint`。
Expected: PASS。本机受限则以 `assembleDebug` 通过为准。

实测（登录后）：「我的」→ 「账户与个性化」→「AI 助手接口」→ 输入名称 → 生成 → 复制令牌 → 列表出现该令牌；点「作废」后状态变「已作废」；「全部作废」清空所有令牌；关掉对话框再开，列表仍反映服务端最新状态；未登录时点生成/列表提示 401 → 走「登录已失效」文案。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt \
        app/src/main/java/com/example/rinklnote/data/network/ApiService.kt \
        app/src/main/java/com/example/rinklnote/ui/viewmodel/AiTokenViewModel.kt \
        app/src/main/java/com/example/rinklnote/ui/screen/profile/AiTokenDialog.kt \
        app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt \
        app/src/main/java/com/example/rinklnote/ui/screen/profile/ProfileScreen.kt
git commit -m "feat(app): 设置页「AI 助手接口」个人令牌生成/列/作废/全部作废 + 用法展示"
```

---

## Self-Review（对照 spec）

**Spec 覆盖：**
- 「App 设置页生成/列/作废/全部作废令牌 + 复制 + 显示接口地址与用法」→ Task 2。
- 「深链 rinklnote://add?amount=&category=&remark=&type= → 预填快速记账抽屉，App 内确认」→ Task 1（`applyQuickAddPrefill` 应用金额/分类名/备注/类型）。
- 「深链缺参 → 默认打开快速记账抽屉（预选默认分类 三餐），不阻塞」→ Task 1 `parseDeepLink` 命中即返回非空 + `applyPendingPrefillIfReady` 全空兜底到 `expectedCats.firstOrNull()`。
- 「onCreate + onNewIntent 双路径、singleTop」→ Task 1 Step 2（manifest 已 singleTop）+ Step 3（`consumeLaunchIntent` 复用）。
- 「令牌仅生成展示一次明文、列表不含明文」→ Task 2 `AiTokenState.newToken` + `DismissToken` 清空；`listAiTokens` 返回 `AiTokenItem`（无 token 字段）。

**占位符：** 无 TODO/TBD；每步含实际代码与命令。

**类型一致性：**
- `PendingQuickAdd(amount/categoryId/categoryName/remark/billType)` 在 RinklNoteApp（Task1 Step1）定义，MainActivity（Step3）与 AppNavigation（Step5）与 QuickAddViewModel（Step4）一致引用。
- `QuickAddViewModel.applyQuickAddPrefill(PendingQuickAdd)` 与 `preselectCategory(Long)` 共存，AppNavigation Step5 按 `categoryId != null` 分派。
- `AiTokenViewModel(api)` / `Factory(api)` / `AiTokenState` / `AiTokenEvent` 在 Task2 Step3 定义，Step4 的 `AiTokenDialog(viewModel, onDismiss)` 与 Step5/6 的创建传参一致。
- DTO `AiTokenResponse`/`AiTokenItem`/`AiGenerateTokenRequest` 在 Step1 定义，ApiService（Step2）与 ViewModel（Step3）与 Dialog（Step4）一致引用。

**与服务器计划对齐：** `/api/ai/tokens`（POST，body `{name}` → `{id,token,name,createdAt}`）、`GET /api/ai/tokens`（→ `[{id,name,createdAt,revoked,revokedAt?}]`）、`POST /api/ai/tokens/{id}/revoke`、`POST /api/ai/tokens/revoke-all`，均映射到服务器计划 Task 4 的 `AIAssistantRoutes` 定义。

**已知缺口（为何未覆盖）：** 小爱技能侧如何带 `Authorization: Bearer` 属于小爱配置，非本仓库代码；若小爱转交受限，spec 已有退化路径（技能做 NLU 再调 `/api/ai/record`），不影响 App 端实现。

---

## 执行交接

App 计划（2 个 Task）已保存到 `docs/superpowers/plans/2026-09-06-ai-assistant-interface-app.md`。

至此该特性的两个独立计划齐备：
- **服务器**：`2026-09-06-ai-assistant-interface-server.md`（4 个 Task）
- **App**：`2026-09-06-ai-assistant-interface-app.md`（2 个 Task）

两者依赖关系：App Task 2 的令牌管理依赖服务器 Task 1/4 已上线（否则 404/无法生成）；App Task 1 深链依赖服务器 Task 3 `record` 之外的 NLU 管线（`/ask`）已就绪。建议先执行服务器计划，再执行 App 计划。

**执行选项：**

1. **Subagent-Driven（推荐）** — 每个 Task 派发一个全新 subagent，任务间人工复审，快速迭代。
2. **Inline Execution** — 在本会话用 executing-plans 批量执行，带检查点逐段复审。

选哪种执行方式？（以及先执行服务器计划、完成后再进行 App 计划，是否按此顺序？）
