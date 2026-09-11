# 实施计划：长按拖动排序 + 拖入 FAB 删除（替换左滑删除）

> 分支 `drag-reorder-delete`（自 d13bd87）。方案 A：排序完整持久化（App + Server 透传）。

## 交互规格（已与用户确认）

1. **长按账单单项** → 震动 → 进入拖动；单击行 = 编辑；**删除只经拖拽**。
2. 拖动仅限**本日组内**；被拖项从流中抽出成 ghost 浮层，原位由后面项**补齐**（卡片高度同步动画）；
   悬停到某间隙时该处让位（下方行下移动画），行到位有轻微震动。
3. 拖动期间，**加账单 FAB 变为删除图标**（Crossfade + 缩放）；ghost 中心碰到删除区 → FAB 放大变红 + 震动一次；
   **松手才删除**（再震动一次，直接删、不弹确认框——拖入+松手两段手势即防误触）。
4. 松手在列表上 → 落位并提交重排（sort_order 持久化）。
5. 旧「左滑删除」（SwipeableBillItem）与长按 DropdownMenu 一并移除（原左滑删除点击无效 bug 随之消灭）。

## sort_order 语义（关键决策）

- `bills.sort_order` **可空** `Long?`，`NULL = 未显式排序`。
- 展示序：`ORDER BY date DESC, COALESCE(sort_order, created_at) DESC, created_at DESC`
  —— 兜底 created_at DESC 与现有「新的在前」完全一致；新账单无需显式赋值。
- 重排提交：该日新序自上而下赋 `sort_order = (n - i) * 1000`（值远小于 created_at 量级，
  之后新增的账单仍按 created_at 兜底落在最前）。
- 同步：`CreateBillRequest` / `BillDTO` 加 `sortOrder: Long? = null` 透传；pull 合并
  `sortOrder = dto.sortOrder`。行级 LWW，两端同日同时重排会互相覆盖为最后写入——已知取舍。

## 步骤

### T1 数据层（App）
- `Bill`：加 `@ColumnInfo("sort_order") val sortOrder: Long? = null`。
- `AppDatabase`：version 11→12；`MIGRATION_11_12 = ALTER TABLE bills ADD COLUMN sort_order INTEGER DEFAULT NULL`。
- `BillDao`：observeAll / observeByMonth / getBillsByDay 改 ORDER BY（含 COALESCE）。
- `BillRepository(+Impl)`：`suspend fun reorderBills(bills: List<Bill>)`（事务内逐行 update；
  dirty/updatedAt 由 VM 统一盖章）。

### T2 VM 事件（TDD）
- `BookkeepingEvent.ReorderBills(newOrder: List<Bill>)`。
- 纯函数 `reorderRanks(n): LongArray`（降序、互异、正）放 domain/util，先测后写。
- VM：映射 copy(sortOrder=rank, dirty=true, updatedAt=now) → repo.reorderBills → 逐个 pushBill。
- 测试：BookkeepingViewModelTest 增加 2 用例（rank 顺序/互异；Fake repo 捕获落库参数）；
  各 Fake BillRepository（Bookkeeping/Budget/Ai/QuickAdd）补 `reorderBills` override。

### T3 UI 层
- 新文件 `ui/screen/bookkeeping/BillDragHost.kt`：拖动状态机（dragging 状态、行 bounds 注册表、
  FAB bounds、insertionIndex 计算、overDelete 判定、震动边沿触发）。
- `BillCard.kt`：`BillItem` 提为 internal `BillRow`（供 ghost 复用）；卡片渲染改为
  「displayOrder（去掉被拖项 + insertionIndex 处插占位 Spacer）+ animateContentSize」；
  行挂 `detectDragGesturesAfterLongPress`（onDragStart 震动 + 注册）；
  移除 SwipeableBillItem/DropdownMenu；clickable = 编辑。
- `BookkeepingScreen.kt`：根 Box 顶部画 ghost（BillRow + scale1.05 + shadow + 跟手位移）；
  FAB：isDragging → Crossfade 成删除图标、armed → 放大变红；松手 overDelete → DeleteBill（无弹窗），
  否则 → ReorderBills。删除 deleteTarget/menuBill/revealedBillId/AlertDialog。
- 删除 `ui/component/SwipeableBillItem.kt`。

### T4 Server 透传（不编译，标注待验证）
- `BillsTable` 加 `sort_order`（nullable long）；server DTO 加字段；BillService create/update 透传。

### T5 验证
- `compileDebugKotlin` + `compileDebugUnitTestKotlin` + JVM 单测（testDebugUnitTest --tests BookkeepingViewModelTest）。
- installDebug 冷启动；用户走查拖动手感（adb 点击注入被禁）。
- 提交（中文 message，git commit -F）。

## 风险
- animateContentSize 与 LazyColumn item 复用交互：占位行用稳定 key 结构，避免 item 复用闪烁。
- CRLF 文件（BookkeepingScreen 等）编辑：优先 Edit 工具，失败退 PowerShell 原始替换。
