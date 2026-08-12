# App + Web 使用逻辑一致性修复 — 设计

日期：2026-08-12
状态：待评审

## 背景

对 App（Kotlin/Compose）与 Web（单文件 SPA）做了一次使用逻辑审查，发现 7 项不符合用户直觉 / 平台不对称的问题。本设计统一修复高影响与中影响项（原清单 1-7）。

已确认的关键决策：
- 记账页数据窗口统一为「顶部可切换月份」，窗口随月份走
- 确认流程全部一步（去掉绿勾门禁）
- MonthDetailOverlay 保留，但只展示当前选中月的分类汇总（去掉内部翻月）
- App 导出 CSV 本批一起做（走系统分享面板）

## 问题清单与修复映射

| # | 问题 | 修复 |
|---|------|------|
| 1 | 记账页列表/图表/总额窗口口径互相矛盾 | A：统一按选中月份 |
| 2 | 备注无法手动输入（点备注弹录音） | B：RemarkInputSheet |
| 3 | 两段式确认不一致 | C：统一一步保存 |
| 4 | 历史月份查看路径太绕 | A：顶部日期可点切月 |
| 5 | Web 缺编辑/删除账单 | E：行内编辑/删除 |
| 6 | Web 资产不能编辑余额 | F：服务端端点 + Web UI |
| 7 | App 账单无搜索/筛选/导出 | D：筛选栏 + 分享导出 |

---

## A. 记账页月份切换 + 口径统一（App）

### 目标
列表、图表、收支总额三个数据视图全部以「选中月份」为唯一窗口，顶部日期可点击切换月份。

### 状态与数据流
`BookkeepingState` 增加：
```
val selectedMonthOffset: Int = 0   // 0=本月, -1=上月, ...
```

`BookkeepingViewModel` 数据源全部改为按 `selectedMonthOffset` 计算：
- `collectBills()`：窗口改为 `getMonthStart(offset) → getNextMonthStart(offset)`（原为 today-10d → 下月1号）。主列表 = 当月账单，`MonthDetailOverlay` 直接复用 `state.bills` 做分类汇总。
- 删除冗余的 `monthBills` 字段与 `collectMonthBills`（与主列表同窗口，无存在价值）。
- `refreshTotals()`：按 `selectedMonthOffset` 计算收支总额（原固定本月）。

新增 `selectMonth(offset)` 事件：更新 offset，重建 `billCollectorJob` 与总额查询。

### 顶部栏
- `TopBar` 日期文本显示「yyyy年M月」（`toHeaderString` 改为月份格式）。
- 日期区域可点击 → 切换显示 `MonthNavigator`：`‹ 2026年8月 ›` + 「回本月」。
- 「下月」按钮在 `selectedMonthOffset >= 0` 时禁用（不能看未来月）。
- 视觉沿用现有卡片样式（RoundedCornerShape 15dp / surface 背景），与 MonthDetailOverlay 的 ‹ › 风格一致。

### 图表
`computeChartData` 改为按选中月份逐日支出：
- 当前月（offset=0）：1号 → 今天
- 历史月：1号 → 月末
- X 轴标签 "M.d"
- 保留「明细」按钮 → `MonthDetailOverlay`（见下）。

### MonthDetailOverlay（保留，改为当前月汇总）
- 去掉内部 `monthOffset` 状态与 ‹ › 翻月、去掉 `onPrevMonth/onNextMonth`、去掉关闭时重置逻辑。
- 输入改为：直接接收 `state.bills`（当前选中月的账单），展示分类分组 + 小计 + 收支总额。
- `showMonthDetail` 仍由图表 Detail 按钮触发。

### 已知限制（本次不做，记录）
- 浏览历史月时，QuickAdd 新记账仍记录今天日期、计入本月（QuickAdd 不加日期选择器）。
- 后续若做「补记」，QuickAdd 需加日期字段，选中月为默认值。

---

## B. 备注可手动输入（App）

### 问题
键盘面板备注行「点击输入备注...」点击后启动语音录音（`AppNavigation.kt:312` `onRemarkClick = onVoiceInput()`），无文本输入；`BillEditOverlay` 中 `onRemarkClick = {}` 为空操作。

### 修复
新建 `RemarkInputSheet` composable：
- 底部弹出卡片，含单个 `TextField`（系统 IME，接受中文）+「确定/取消」。
- 确定 → `QuickAddEvent.RemarkChanged(text)` → 关闭。

接入点：
- `AppNavigation` 键盘 overlay 的 `onRemarkClick` → 打开 `RemarkInputSheet`（不再触发语音）。
- `BillEditOverlay` 的 `onRemarkClick` → 同样打开 `RemarkInputSheet`。

语音入口保留：抽屉底部 AI 按钮（`QuickAddDrawer`）不变，仍触发 `onVoiceInput`。

说明：这是全 App 唯一使用系统 IME 的场景（数字键盘仍保持自定义 NumericKeypad），作为有意例外。

---

## C. 确认流程统一一步（App）

### 问题
手输金额走两段式（键盘确认 → CountAfter 绿勾 → 再点绿勾保存）；模板/推荐/NLP 一步保存。行为不一致。

### 修复
统一为一步：
- `QuickAddEvent.Confirm`（键盘「确定」）→ 直接 `finalConfirm()` 保存。
- 删除 `QuickAddState.confirmed`、`QuickAddEffect.ConfirmRequested`、`AppNavigation.showConfirmed`、`QuickAddDrawer` 的 `CountAfter` 绿勾分支。
- 保存成功后 Toast「已记账」+ 抽屉关闭 + `BookkeepingEvent.Refresh`（现有 `FinalConfirmCompleted` effect 保留）。
- 模板 / 推荐 / NLP 已直接 `finalConfirm()`，不变。

防误触权衡：一步保存失去绿勾门禁，由 Toast + 列表即时刷新 + 编辑入口（点账单可改）兜底。用户已确认此取舍。

---

## D. App 账单搜索/筛选/导出（App）

### 搜索 + 分类筛选
`BookkeepingScreen` 列表上方（TopBar 与 Chart 之间）加筛选栏：
- 搜索框：系统 IME，`Local` state，匹配备注/分类名，作用于当月 `state.bills`。
- 分类下拉：当月账单去重分类列表，含「全部分类」。
- 筛选为客户端本地过滤，不新增 Room 查询。

### 导出 CSV
筛选栏加「导出」按钮：
- 用当前筛选结果生成 CSV（`\uFEFF` BOM 防乱码，字段值做 CSV 转义）。
- 写入 `cacheDir` 临时文件 → `FileProvider` → `ACTION_SEND`（text/csv）分享面板。
- `FileProvider` 需要 `res/xml/file_paths.xml` + Manifest provider 声明。

---

## E. Web 账单编辑/删除

### 现状
表格只能点分类名弹隐藏 select 改分类（不显眼）；不能改金额/备注/删除。

### 修复
- 每行加「编辑」「删除」操作（表格操作列，移动端放行尾）。
- 编辑：弹窗复用记账表单字段（金额/收支切换/分类/子分类/账户/备注），`PUT /api/bills/{id}`（已存在，body 为 CreateBillRequest）。成功后 `loadData()`。
- 删除：`confirm()` → `DELETE /api/bills/{id}`（已存在）→ `loadData()`。
- 移除分类名的内嵌 select（隐蔽入口），改为统一走编辑弹窗。

---

## F. Web 资产编辑余额

### 服务端（新增）
- `BillRoutes`（或新 `AccountRoutes`）新增 JWT 保护的 `PUT /api/accounts/{id}`：
  - body：`{ balance: Double }`
  - 校验 `balance >= 0` 且有限；非法 → 400「余额不能为负」。
  - 更新 `AccountsTable.balance`，返回更新后的 `AccountDTO`。
  - 账户表为全局单套（无 userId），沿用 `getAccounts()` 语义。

### Web
- 账户卡加「编辑」按钮 → `prompt()` 或小弹窗输入新余额 → `PUT /api/accounts/{id}` → 重新 `loadData()`。
- 需在 `S` 中补充账户编辑状态。

### 已知限制
App 端 `BalanceEditDialog` 改余额仍是纯本地、不同步服务器。两端余额会分叉。本次只补 Web 能力；App 账户同步留作后续。

---

## 受影响文件

### App
- `ui/viewmodel/BookkeepingViewModel.kt` — selectedMonthOffset、collectBills/refreshTotals 改按月、删除 collectMonthBills、selectMonth
- `ui/viewmodel/BookkeepingState` — 删 monthBills、monthOffset，增 selectedMonthOffset
- `ui/screen/bookkeeping/BookkeepingScreen.kt` — TopBar 月份导航、筛选栏、导出入口
- `ui/screen/bookkeeping/MonthDetailOverlay.kt` — 去掉内部翻月，展示当前月汇总
- `ui/viewmodel/QuickAddViewModel.kt` — Confirm 直接 finalConfirm，删 confirmed 相关
- `ui/screen/quickadd/QuickAddDrawer.kt` — 删 CountAfter 分支
- `navigation/AppNavigation.kt` — 删 showConfirmed，RemarkInputSheet 接入，Toast
- 新增 `ui/component/RemarkInputSheet.kt`
- `ui/screen/bookkeeping/BillEditOverlay.kt` — 备注输入接入
- 新增 `res/xml/file_paths.xml`；`AndroidManifest.xml` FileProvider
- 单测：`BookkeepingViewModelTest`、`QuickAddViewModelTest` 更新/新增

### Web
- `web/index.html` — 账单行编辑/删除、编辑弹窗、账户卡编辑、移除内嵌 select

### Server
- `routes/BillRoutes.kt`（或新 `routes/AccountRoutes.kt`）— `PUT /api/accounts/{id}`
- `plugins/Routing`（Application.kt 接线）— 挂载新路由
- 单测：`AccountRoutesTest` 或并入现有

---

## 测试

- **App 单测**（`:app:test`）：
  - `BookkeepingViewModel`：selectMonth 后 bills/totals/chart 跟随月份；下月禁用逻辑（状态层）
  - `QuickAddViewModel`：Confirm 事件直接触发保存（finalConfirm 路径），无绿勾状态
- **Server 单测**（`:server:test`，用 `TestDatabase.connect()`）：`PUT /api/accounts/{id}` 正常更新、非法余额 400、未授权 401
- **Web 手测**：编辑/删除账单、编辑余额、移除内嵌 select 后旧路径不残留
- **App 装机手测**：切月（含历史月空月/有月）、备注输入、一步记账、搜索/筛选/导出

## 部署
- Server：installDist → scp → systemd 重启 → curl 冒烟新端点
- Web：sudo cp 到 /var/www/rinklnote/index.html，核对 md5
- App：installDebug 装机
