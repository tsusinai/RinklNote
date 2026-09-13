# App 端流畅度优化 实施计划

> **For Claude:** Use `${SUPERPOWERS_SKILLS_ROOT}/skills/collaboration/executing-plans/SKILL.md` to implement this plan task-by-task.

**目标：** 在视觉零变化的前提下，修复 app 端三处掉帧：冷启动慢、nav 切页掉帧、列表滚动掉帧。

**架构：** 测量驱动（方案 A）——先在 release 包上建立三场景基线，再按数据逐项应用修复弹药，每项修复独立提交并用同一套采集方法复测对比；测量没指向的修复不做。

**技术栈：** Kotlin 2.0.21 + Compose（BOM 2024.09.00）+ Haze 1.5.2 + Coil 2.7.0 + DataStore + Room 2.6.1；真机 89a5f9e1（Android 16 / 澎湃 OS）。

**关键约束（红线）：** 毛玻璃、背景图、动画曲线的**参数值一个不动**，只改工作量发生在哪里。视觉一致性用截图对比作为验收证据。

**隔离方式说明：** 本仓库为单人 trunk 开发（历史提交均在 main 直接进行），且 main 上有未提交的 `AGENTS.md` 文档更新——开 worktree 会导致文档不同步与 Gradle 缓存重建成本。故以 **feature 分支** 作为隔离手段，保持设计可回滚的精神不变。

**环境注意：**
- `adb` 不在 PATH：统一用 `ADB="/c/Users/a'su's/AppData/Local/Android/Sdk/platform-tools/adb.exe"`（路径含撇号，必须双引号）。
- Android 16 禁止 shell 注入输入事件：`input swipe` 会抛 `SecurityException`。滚动/切页场景需人工操作设备，脚本只负责 `gfxinfo reset` / `dumpsys`。
- 性能数据一律用 **release 包** 采集；debug 包数据只定性。

---

## Task 0: 分支与工作区准备

**Files:** 无代码改动。

**Step 1: 提交 AGENTS.md 文档更新（与性能工作无关，先落账）**

```bash
git add AGENTS.md
git commit -m "docs: 按最新代码校正 AGENTS.md（server 回归 Gradle、Room v13、整数分、一步确认）"
```

**Step 2: 建 feature 分支**

```bash
git checkout -b perf/app-fluency
```

**Step 3: 确认工作区干净**

```bash
git status
```
Expected: `nothing to commit, working tree clean`

---

## Task 1: release 构建可用性 + 测量脚本

**Files:**
- Modify: `app/build.gradle.kts`（buildTypes.release 块，约 23-31 行）

**Step 1: 给 release 补 debug 签名（否则 installRelease 无法安装）**

```kotlin
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
```

**Step 2: 构建 + 安装 release**

```bash
./gradlew :app:installRelease
```
Expected: `BUILD SUCCESSFUL`，设备上出现 release 版 `com.example.rinklnote`。
注意：debug 与 release 同 applicationId，先 `./gradlew :app:uninstallDebug` 或直接让 installRelease 覆盖安装。

**Step 3: 写基线采集脚本 `docs/plans/perf-capture.sh`**

```bash
#!/usr/bin/env bash
# 用法: perf-capture.sh cold | start-scroll | stop-scroll | dump
# 场景: cold=冷启动3次取中位; start-scroll=重置帧统计(随后人工滚5屏); stop-scroll=输出帧统计
ADB="/c/Users/a'su's/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="89a5f9e1"
PKG="com.example.rinklnote"
case "$1" in
  cold)
    for i in 1 2 3; do
      "$ADB" -s $DEV shell am force-stop $PKG; sleep 2
      "$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity | grep -E "^TotalTime"
      sleep 3
    done ;;
  start-scroll) "$ADB" -s $DEV shell dumpsys gfxinfo $PKG reset ;;
  stop-scroll)  "$ADB" -s $DEV shell dumpsys gfxinfo $PKG | sed -n '/Total frames rendered/,/95th/p' ;;
esac
```

**Step 4: 提交**

```bash
git add app/build.gradle.kts docs/plans/perf-capture.sh
git commit -m "chore(perf): release 补 debug 签名 + 基线采集脚本"
```

---

## Task 2: 基线采集与归档

**Files:**
- Create: `docs/plans/2026-09-14-app-fluency-baseline.md`

**Step 1: 冷启动基线**

```bash
bash docs/plans/perf-capture.sh cold
```
Expected: 3 行 `TotalTime`，取中位数记入文档。

**Step 2: 滚动基线（需人工配合）**

运行 `start-scroll` 后，**请用户**在记账页手动匀速滚动 5 屏，随即 `stop-scroll`。记录 Janky 比例、90th/95th 帧时长。

**Step 3: 切页基线（需人工配合）**

同上：reset → 人工按 计划→记账→资产→我的→记账 走一圈 → dump。记录同上。

**Step 4: 归档**

`2026-09-14-app-fluency-baseline.md` 记录：日期、构建号（`git rev-parse --short HEAD`）、三场景数据。此文档是全程对照物。

**Step 5: 提交**

```bash
git add docs/plans/2026-09-14-app-fluency-baseline.md
git commit -m "docs(perf): 记录 release 基线（冷启动/滚动/切页）"
```

---

## Task 3: 冷启动①——主题同步直读，消除首帧二次重组

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/data/local/SettingsManager.kt`
- Modify: `app/src/main/java/com/example/rinklnote/MainActivity.kt:26-34`

**现状：** `MainActivity` 用 `initialValue = ThemeMode.SYSTEM` / `emptyMap()` 组合首帧，DataStore 异步到达后整树重组、主题可能闪变。

**Step 1: `SettingsManager` 增加一次性快照（插入在 `customThemeColors` 属性之后）**

```kotlin
    /** 启动关键路径用：一次性读出主题相关键，避免首帧后二次重组（磁盘首读约几十毫秒，可接受）。 */
    suspend fun themeSnapshot(): Pair<ThemeMode, Map<RinklThemeSlot, Color>> {
        val prefs = context.settingsDataStore.data.first()
        val mode = prefs[KEY_THEME_MODE]?.let {
            runCatching { ThemeMode.valueOf(it) }.getOrNull()
        } ?: ThemeMode.SYSTEM
        return mode to customColorsFrom(prefs)
    }
```
（`customColorsFrom(prefs)` 即现 `customThemeColors` map 逻辑提取的同名私有函数，二者复用同一份解析，别复制两份逻辑；`KEY_THEME_MODE` 以实际字段名为准。）

**Step 2: `MainActivity.onCreate` 在 `setContent` 前同步取初值**

```kotlin
        val initialTheme = runBlocking { app.settingsManager.themeSnapshot() }
        setContent {
            val themeMode by app.settingsManager.themeMode
                .collectAsStateWithLifecycle(initialValue = initialTheme.first)
            ...
            val customColors by app.settingsManager.customThemeColors
                .collectAsStateWithLifecycle(initialValue = initialTheme.second)
```
需要 `import kotlinx.coroutines.runBlocking`。后续 Flow 热更新路径不变（改主题实时生效的行为保留）。

**Step 3: 真机验证**

- release 安装后冷启动：主题（深色/自定义色）首帧即正确，无闪变；
- 进入 我的→主题设置 切换主题：实时生效（热更新路径未被破坏）；
- 桌面小组件、深链 `rinklnote://add` 入口 smoke 正常。

**Step 4: 跑全量单测**

```bash
./gradlew :app:test
```
Expected: 全绿（套件当前基线即全绿，保持住）。

**Step 5: 提交**

```bash
git add app/src/main/java/com/example/rinklnote/data/local/SettingsManager.kt app/src/main/java/com/example/rinklnote/MainActivity.kt
git commit -m "perf(startup): 主题键启动同步直读，消除首帧二次重组与主题闪变"
```

---

## Task 4: 冷启动②——Room 与 Coil 后台预热

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/RinklNoteApp.kt`（类尾部）

**Step 1: Application.onCreate 追加后台预热协程**

```kotlin
    override fun onCreate() {
        super.onCreate()
        // 首帧前在后台把首屏必经的初始化做完，主线程组合时只等结果不干活。
        applicationScope.launch(Dispatchers.IO) {
            database // 触发 Room 构建（含迁移检查）
            repository.ensureCategoriesLoaded()   // 以实际方法名为准：分类/账户 StateFlow 预热
            settingsManager.backgroundUri.value?.let { uri ->
                // 背景图提前进 Coil 内存缓存，首帧 AppBackground 不再同步解码
                // （预热 key 必须与 AppBackground 的 AsyncImage model 完全一致）
            }
        }
    }
```
注意三点：
1. `applicationScope` 若不存在，按项目习惯新建 `val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`；
2. **先读 `Repository`/`SettingsManager` 实际 API 再落笔**，`ensureCategoriesLoaded`/`backgroundUri` 是示意，找不到同名方法就调用真实等价物，别发明 API；
3. Coil 预热用 `ImageLoader.execute(ImageRequest.Builder(this).data(uri).size(SIZE_ORIGINAL 或屏宽).memoryCacheKey(uri).build())`——先确认 Coil 对 content:// 的默认 memoryCacheKey 与 AsyncImage 一致，不一致就放弃此步（Coil 默认已按 View 尺寸异步解码，预热收益可能有限，测量没指向就不做）。

**Step 2: 真机验证**

冷启动无 ANR、无 SQLite 主线程异常；首次进入记账页分类/账户立即可见。

**Step 3: 跑单测 + 提交**

```bash
./gradlew :app:test
git add app/src/main/java/com/example/rinklnote/RinklNoteApp.kt
git commit -m "perf(startup): Room/分类/账户后台预热，首帧组合不再等待初始化"
```

---

## Task 5: 滚动①——LazyColumn 补 contentType

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BookkeepingScreen.kt:191-243`

**Step 1:** 给 `items()` / `item()` 补 `contentType` 参数：账单项 `"bill"`、日期分组头 `"day-header"`、spacer/banner `"static"`。

**Step 2:** 视觉与行为零变化验证：滚动前后截图对比（`adb shell screencap -p /sdcard/s.png`）。

**Step 3:** 滚动场景复测（`start-scroll` → 人工滚 5 屏 → `stop-scroll`），与基线对比。

**Step 4:** 提交

```bash
git add app/src/main/java/com/example/rinklnote/ui/screen/bookkeeping/BookkeepingScreen.kt
git commit -m "perf(scroll): 月度列表 LazyColumn 补 contentType 复用分型"
```

---

## Task 6: 中期复测 + Baseline Profile 收益判定

**Step 1:** 重跑 Task 2 三场景，把数据追加到 baseline 文档（标注 commit）。

**Step 2:** 模拟 Baseline Profile 收益：

```bash
ADB="/c/Users/a'su's/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s 89a5f9e1 shell cmd package compile -m speed -f com.example.rinklnote
bash docs/plans/perf-capture.sh cold
```
Expected: TotalTime 相比 Task 6 Step 1 明显下降。**若降幅 < 15%，Baseline Profile 正式引入（新模块/CI）不做**，仅归档数据；若 ≥ 15%，另立计划引入 `androidx.profileinstaller`（本计划不含）。

**Step 3:** 归档 + 提交。

---

## Task 7（条件任务）: nav 切页——重组面收窄与过渡期 blur

**前置条件：中期数据显示切页场景是主要掉帧源才执行**，否则跳过并在归档文档写明理由。

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt:151-152, 406-545`

**Step 1:** 用 Perfetto 定位切页热点（`adb shell perfetto -o /data/misc/perfetto-traces/x.pftrace -t 5s` 期间人工切页一次），确认是「根层 currentRoute 重组传导」还是「双页 hazeChild 并存 blur」。

**Step 2（按定位结果二选一）:**
- 若重组传导：把 `isActive` 的计算移入各 screen 内部（screen 自行 `currentBackStackEntryAsState()` 比较），根层不再向下传路由相关参数；
- 若 blur 热点：检查各 `hazeChild` 的 `HazeStyle` 是否在每次重组重建（移出组合调用点/`remember`），**参数值原样**。

**Step 3:** 切页复测对比 + 切页中帧截图确认视觉零变化。

**Step 4:** 提交 `perf(nav): ...`。

---

## Task 8: 最终回归与收尾

**Step 1:** release 包全功能 smoke：记账（语音/手输/模板）、编辑账单、月度明细、预算、资产、AI 页、同步开关、桌面小组件、深链。

**Step 2:** 关键屏截图与优化前对比（有背景/无背景、抽屉展开、编辑页、切换动画中帧），确认视觉零变化。

**Step 3:** 全量数据与结论写入 baseline 文档；跑 `./gradlew :app:test` 与 `./gradlew :app:lint`。

**Step 4:** 合并：

```bash
git checkout main
git merge --no-ff perf/app-fluency
git branch -d perf/app-fluency
```

---

## 明确不做（YAGNI）

- Macrobenchmark 模块 / UI 测试基建（gfxinfo + 人工够用，回归需要时另立计划）；
- Baseline Profile 正式引入（除非 Task 6 判定收益 ≥ 15%）；
- BillDragHost 拖动重排改动（刚重做过，除非测量指向）；
- 图表动画起始时机延后（有观感变化嫌疑，触碰红线）；
- 任何 Haze 模糊半径 / tint / 动画曲线参数变更。
