# RinklNote 项目体验优化实施计划

> **For Claude:** Use `${SUPERPOWERS_SKILLS_ROOT}/skills/collaboration/executing-plans/SKILL.md` to implement this plan task-by-task.

**Goal:** 围绕快速记账、同步反馈、AI 可信度和服务异常恢复优化 RinklNote，让用户始终知道数据保存在哪里、是否已经同步，以及下一步可以做什么。

**Architecture:** 保留本地优先、Last-Writer-Wins 和普通记账一步确认语义，在其上增加可观察的同步状态、可操作的结果反馈和针对不确定 AI 结果的确认层。App、Web、服务端分别负责本地交互、状态展示和错误诊断，不通过 UI 改动绕过现有同步协议或权限边界。

**Tech Stack:** Kotlin 2.0.21 + Jetpack Compose + Room 2.6.1 + DataStore；Ktor 2.3.13 + Exposed；Vue 3.5 + Vite + Pinia + Vitest；Gradle 8.13。

---

## 范围与约束

- 普通数字键盘记账继续保持“一次确认直接落库并关闭抽屉”。
- AI 建议、模糊金额和低置信度识别允许进入“检查后记入”流程，不能静默扩大自动记账范围。
- 本地落库成功与云端同步成功必须使用不同文案和状态。
- 同步失败保留本地数据，支持重试；不改变软删除、乐观锁和 Last-Writer-Wins 规则。
- 保留 RinklNote 现有主蓝色，通过层级、状态、文案和操作反馈提升体验。
- 所有新增 UI 文案和代码注释使用中文。
- 当前工作区的 .test-shots/ 是未跟踪目录，执行计划时不得通过 git add -A 混入提交。

## 优先级与验收指标

| 优先级 | 体验目标 | 第一阶段验收指标 |
| --- | --- | --- |
| P0 | 用户知道账单是否真正同步 | 任意一次保存都能看到本地保存、同步中、已同步或待重试之一 |
| P0 | 服务不可用时仍可恢复 | 502、网络断开、超时均有可理解的错误状态和重试入口 |
| P1 | 减少误记账和返工 | 建议记账、模糊金额能看到依据；修改后可回到原流程 |
| P1 | 缩短重复记账路径 | 高频场景不增加普通记账点击数，建议确认后可一键完成 |
| P2 | 提高预算决策价值 | 用户能从计划首页看到“今天还能花多少”及其计算依据 |
| P2 | 多端行为一致 | App 与 Web 对“同步完成”“待同步”“上次成功时间”使用同一套语义 |

---

## Task 0：建立体验基线与测试清单

**Files:**

- Create: docs/experience/2026-10-01-体验验收清单.md
- Read: app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt
- Read: app/src/main/java/com/example/rinklnote/sync/SyncManager.kt
- Read: web/src/stores/data.ts
- Read: web/src/views/console/settings/SyncCard.vue

**Step 1：记录当前行为**

分别记录普通数字键盘记账、建议记账点击“使用”、网络断开后记账、手动同步失败、Web 刷新失败、删除账单后恢复这六个场景的实际文案、页面变化和数据结果。

**Step 2：建立验收表**

每个场景至少包含：前置条件、操作步骤、用户看到的状态、数据库预期、失败时的恢复入口。

**Step 3：检查基线**

    git status --short --branch
    git diff --check

Expected：只有已有的 .test-shots/ 未跟踪目录，不修改或暂存它。

---

## Task 1：App 同步状态可观察化

**Files:**

- Modify: app/src/main/java/com/example/rinklnote/sync/SyncManager.kt
- Modify: app/src/main/java/com/example/rinklnote/data/local/TokenManager.kt
- Modify: app/src/main/java/com/example/rinklnote/ui/screen/profile/ProfileScreen.kt
- Modify: app/src/main/java/com/example/rinklnote/ui/screen/profile/ProfileCards.kt
- Test: app/src/test/java/com/example/rinklnote/sync/SyncManagerTest.kt

**Step 1：定义状态模型**

在 SyncManager 暴露可收集的状态流，至少覆盖：空闲、同步中、已同步（时间、上传数、下载数）、待重试（本地待上传数、错误原因）、需要登录。

状态只描述同步结果，不改变已有 API 请求顺序和冲突处理。

**Step 2：保留可恢复信息**

在 TokenManager 增加最后一次失败时间和用户可读失败原因的持久化字段；退出登录时一并清除。错误信息不能写入 token 或账单内容。

**Step 3：更新个人资料页**

将“上次同步”改为状态卡片：

- 同步中显示进行中状态；
- 已同步显示时间和本次数量；
- 待重试显示“立即重试”；
- 没有登录显示登录入口。

**Step 4：补单测**

新增成功同步、上传失败后账单仍保持 dirty、重试恢复成功、401 进入需要登录状态四类用例。

    $env:JAVA_HOME = 'D:/Codes/AndroidStudio/jbr'
    $env:GRADLE_USER_HOME = 'D:/Codes/RinklNote/.gradle-home'
    ./gradlew :app:testDebugUnitTest --tests "com.example.rinklnote.sync.SyncManagerTest"

Expected：BUILD SUCCESSFUL，新增用例全部通过。

---

## Task 2：保存、删除和重试反馈

**Files:**

- Create: app/src/main/java/com/example/rinklnote/ui/component/RinklSnackbarHost.kt
- Modify: app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt
- Modify: app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt
- Modify: app/src/main/java/com/example/rinklnote/data/repository/BillRepository.kt
- Modify: app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt
- Test: app/src/test/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModelTest.kt
- Create: app/src/test/java/com/example/rinklnote/data/repository/BillRepositoryTest.kt

**Step 1：定义统一反馈事件**

将当前分散的 Toast 文案归并为结构化事件：已保存到本机、已同步到云端、同步失败但账单仍保存在本机、账单已删除可撤销、账单已保存可编辑。

**Step 2：实现带操作的 Snackbar**

沿用现有主题令牌和圆角风格，提供“编辑”“继续记账”“撤销”“重试”等操作。失败反馈保持到用户操作或主动关闭。

**Step 3：实现删除撤销**

通过现有软删除字段实现短时间撤销：

1. 删除时保存被删除账单的本地快照；
2. 点击撤销时恢复 deleted=0、dirty=1；
3. 撤销失败时保留删除状态并显示可重试错误；
4. 不直接物理删除已同步数据。

**Step 4：回归测试**

    $env:JAVA_HOME = 'D:/Codes/AndroidStudio/jbr'
    $env:GRADLE_USER_HOME = 'D:/Codes/RinklNote/.gradle-home'
    ./gradlew :app:testDebugUnitTest --tests "com.example.rinklnote.ui.viewmodel.QuickAddViewModelTest"

Expected：原有一步确认测试继续通过，新增反馈和撤销测试通过。

---

## Task 3：建议记账与 AI 结果的可信度

**Files:**

- Modify: app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt
- Modify: app/src/main/java/com/example/rinklnote/ui/screen/quickadd/QuickAddDrawer.kt
- Modify: server/src/main/kotlin/com/example/rinklnote/server/services/nlu/FuzzyAmount.kt
- Modify: server/src/main/kotlin/com/example/rinklnote/server/services/nlu/DefaultNLUService.kt
- Modify: server/src/main/kotlin/com/example/rinklnote/server/services/PhoneIntentRouter.kt
- Test: app/src/test/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModelTest.kt
- Test: server/src/test/kotlin/com/example/rinklnote/server/services/DefaultNLUServiceTest.kt
- Test: server/src/test/kotlin/com/example/rinklnote/server/services/PhoneIntentRouterTest.kt

**Step 1：扩展建议结果**

建议结果增加置信度等级、推荐分类和账户的来源、是否需要用户确认、可选的简短解释。

**Step 2：区分自动记入和检查后记入**

- 普通手动记账保持现状；
- 高置信度建议可显示“一键记入”；
- 低置信度建议只填充表单，不直接调用 finalConfirm；
- 用户点击“检查后记入”后仍可修改金额、分类和账户。

**Step 3：处理模糊金额**

对于“三四十”“几十块”等区间，明确展示原始区间、系统采用的中值和修改金额入口；Bot 或 AI 回执中保留“按中值记账”的说明，不把模糊金额误认为精确金额。

**Step 4：改善建议可发现性**

保留现有长按能力，同时增加一次性提示和可见入口，不依赖用户猜测手势。建议卡片支持固定、关闭和重新显示。

**Step 5：运行测试**

    $env:JAVA_HOME = 'D:/Codes/AndroidStudio/jbr'
    $env:GRADLE_USER_HOME = 'D:/Codes/RinklNote/.gradle-home'
    ./gradlew :server:test --tests "*DefaultNLUServiceTest"
    ./gradlew :server:test --tests "*PhoneIntentRouterTest"
    ./gradlew :app:testDebugUnitTest --tests "com.example.rinklnote.ui.viewmodel.QuickAddViewModelTest"

Expected：模糊金额、歧义分类、建议确认和普通一步确认用例全部通过。

---

## Task 4：Web 同步语义和错误恢复

**Files:**

- Modify: web/src/stores/data.ts
- Modify: web/src/views/console/settings/SyncCard.vue
- Modify: web/src/api/http.ts
- Modify: web/src/components/GlobalToast.vue
- Test: web/src/stores/__tests__/data.test.ts
- Test: web/src/api/__tests__/http.test.ts
- Create: web/src/views/__tests__/sync.test.ts

**Step 1：拆分加载结果**

data.loadData 需要区分部分资源加载成功、账单加载成功、账号或分类加载失败、网络不可用、401 需要重新登录、409 并发冲突。不能把失败资源静默替换为空数组后再显示“同步完成”。

**Step 2：统一同步卡片**

显示当前状态、上次成功时间、失败原因、受影响资源、“重试”按钮，以及继续使用本地已有数据的提示。Web 的“同步”明确表示刷新云端数据，不伪装成 App 的待上传队列。

**Step 3：补充 502 和超时文案**

在 http.ts 中将 502、503、504、网络异常映射为稳定的中文错误类型，保留原始 HTTP 状态供日志和测试使用。页面只展示用户能采取行动的内容。

**Step 4：测试与构建**

    Set-Location web
    npm run test
    npm run build

Expected：Vitest 全部通过，vue-tsc 和 Vite 构建成功。

---

## Task 5：计划页的日常决策反馈

**Files:**

- Modify: app/src/main/java/com/example/rinklnote/ui/screen/plan/PlanScreen.kt
- Modify: app/src/main/java/com/example/rinklnote/ui/viewmodel/BudgetViewModel.kt
- Modify: web/src/views/console/Charts.vue
- Modify: web/src/utils/budgetRisk.ts
- Test: app/src/test/java/com/example/rinklnote/ui/viewmodel/BudgetViewModelTest.kt
- Test: web/src/utils/__tests__/budgetRisk.test.ts

**Step 1：复用现有计算口径**

BudgetEditScreen 已经展示日均可花，本任务将相同派生结果提升到计划首页和 Web 相关视图，避免出现两套计算。

剩余日额度使用：

    剩余预算 ÷ 剩余天数

预算为零、周期结束或没有设置预算时显示对应状态，不出现除零或误导性的正数。

**Step 2：增加解释区域**

同时显示本月预算、已用、剩余、剩余天数和今日建议额度。超支时提供查看支出明细和调整预算入口。

**Step 3：保持现有入口约定**

未设置分类预算的卡片继续进入专门预算设置页，不在首页复制另一套编辑逻辑。

**Step 4：验证**

    $env:JAVA_HOME = 'D:/Codes/AndroidStudio/jbr'
    $env:GRADLE_USER_HOME = 'D:/Codes/RinklNote/.gradle-home'
    ./gradlew :app:testDebugUnitTest --tests "com.example.rinklnote.ui.viewmodel.BudgetViewModelTest"
    Set-Location web
    npm run test

Expected：预算为零、跨月、超支和无预算四类用例通过。

---

## Task 6：端到端验收与部署前检查

**Files:**

- Create: docs/体验验收记录-2026-10.md
- Modify: README.md（仅在实际行为改变后同步）
- Read: docs/前后端审查报告-2026-09-29.md
- Read: docs/deploy-checklist-2026-09-18.md

**Step 1：App 验收**

至少验证离线记账、恢复网络后同步、同步失败重试、建议记账确认、模糊金额修改、删除撤销、预算为零。

**Step 2：Web 验收**

至少验证首次加载、502 / 503 / 超时、401 回登录并保留回跳地址、409 并发冲突、同步卡片状态、预算日额度。

**Step 3：构建检查**

    $env:JAVA_HOME = 'D:/Codes/AndroidStudio/jbr'
    $env:GRADLE_USER_HOME = 'D:/Codes/RinklNote/.gradle-home'
    ./gradlew :app:testDebugUnitTest
    ./gradlew :server:test
    Set-Location web
    npm run test
    npm run build

Expected：三端检查通过；若服务端存在已知限流测试失败，必须单独记录失败原因，不能标记为全绿。

**Step 4：代码与文档检查**

    git diff --check
    git status --short --branch

提交前只暂存本次计划涉及的路径，禁止使用 git add -A。

---

## 暂不纳入本轮

- 重写同步协议或替换 Last-Writer-Wins；
- 引入新的依赖注入框架；
- 重做主色、品牌图标和整体导航；
- 自动读取或修改用户未主动授权的定位数据；
- 将邮件、Bot、AI 能力全部改成强制在线；
- 在没有真实 502 日志和上游证据前修改服务器上其他项目的 Nginx 配置。

## 推荐实施顺序

1. Task 1：同步状态；
2. Task 2：保存、删除和重试反馈；
3. Task 4：Web 同步与 502 恢复；
4. Task 3：AI 建议可信度；
5. Task 5：预算日额度；
6. Task 6：端到端验收。

前四项完成后，用户会直接感受到“记账不丢、同步可知、出错能恢复”；后两项属于决策质量和体验深化。


