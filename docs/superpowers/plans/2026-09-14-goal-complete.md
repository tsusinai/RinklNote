# 彻底完善 RinklNote——goal 实施计划（2026-09-14）

> 来源：用户目标文档（桌面《这是RinklNote的目标.md》）。
> 模式：Superpowers 子代理驱动开发。并发子代理按**文件集严格分区**，禁止各自跑 Gradle / 提交；
> 主会话负责集成（AppNavigation 接线）、编译、测试、评审与提交。
> UI 一律遵守 `docs/UI设计规范` 既有事实标准：卡片 15dp + rinkShadow + applyCardGlass、
> 字阶 30/22/20/18/16/14/12、Motion 令牌、页面水平 14dp / 列表 10dp / 卡内 16×12、
> 返回键 ArrowBack、金额整数分、注释文案全中文。

## 波次 1（并发 4 代理，文件零交集）

### T-A 主题系统（对应目标 4 + 7）
文件独占：`ui/theme/RinklColors.kt`、`data/local/SettingsManager.kt`、
`ui/screen/profile/CustomThemeScreen.kt`、`ui/component/HeatmapBox.kt`、`ui/component/MonthChartPager.kt`
1. 边框槽默认改为**透明（无边框）**：`defaultRinklColors` / `rinklColorsOf` 的 borderColor 默认值改 `Color.Transparent`；
   分割线回落默认灰逻辑保持不变（rinklColorsOf 已处理 alpha==0）。
2. 新增 2 个主题槽：`HEATMAP`（热力图色，自动按强度分配透明度/深浅）、`CHART`（折线/柱状色）；**饼图不动**（PiePalette 保持）。
   - RinklThemeSlot 枚举 + SettingsManager 存储键 + rinklColorsOf 解析（默认：热力沿用 EmptyBlue→Blue40、图表沿用 tertiary/primary 现状色）。
   - CustomThemeScreen 增加两行槽位 UI（ColorPickerSheet 复用；CHART 槽说明「影响折线/柱状」，HEATMAP 说明「自动分配深浅」）。
   - HeatmapBox 热力色改用热力槽色 lerp；MonthChartPager 折线/柱状主色改用图表槽色。

### T-B 计划模块（对应目标 1）
文件独占：`ui/screen/plan/PlanScreen.kt`、`ui/screen/plan/BudgetEditScreen.kt`、`ui/viewmodel/BudgetViewModel.kt`、新文件 `ui/screen/plan/CategoryBudgetScreen.kt`
1. 新增**分类预算设置独立 nav 页**（`CategoryBudgetScreen`，导出供路由接线）：
   列出全部支出分类（BudgetState.expenseCategories 已备），显示已设/未设，点击分类进该分类的预算编辑（复用 BudgetEditScreen 或跳其路由的入口回调）。
2. 计划页增加进入该页的入口；分类行图标**适应铺满**（icon 尺寸与行容器匹配，不留过余白）。
3. 顶部注释标注「路由 `budget-categories` 由主会话接线」。

### T-C 我的页重排（对应目标 5）
文件独占：`ui/screen/profile/ProfileScreen.kt`、`ui/screen/profile/ProfileCards.kt`
按使用逻辑重排编排（登录态/数据同步 > 日报通知 > 账户与安全 > 主题外观 > 通用/关于），
卡片沿用 SettingsGroupCard/SettingsRow；不改行为只改编排与分组标题。

### T-D 导入账单（对应目标 8）
文件独占：新目录 `ui/screen/import/`（Screen + VM）、`data/repository/BillRepository.kt`、`data/repository/BillRepositoryImpl.kt`
1. CSV 导入：格式对齐 `util/BillCsvExporter` 导出格式（列名/日期/金额分）。
2. 流程：选文件（ActivityResult OpenDocument）→ 解析预览（条数/可识别分类账户/异常行）→ 确认落库（本地优先，dirty=1 走既有同步推送）。
3. 分类匹配：按分类名精确匹配 + 报告无法识别行；金额一律 Long 分（Money.parseMinor）。
4. 导出 `ImportBillsScreen`（路由 `bill-import` 由主会话接线），入口回调参数由主会话注入。

## 集成 1（主会话）
AppNavigation：接 `budget-categories`、`bill-import` 路由与回调；编译 + 全量测试 + 提交。

## 波次 2（并发 2 代理）

### T-E 更多抽屉 + 账单地图（对应目标 5左上角 + 9）
文件独占：`ui/screen/bookkeeping/BookkeepingScreen.kt`、新文件 `ui/component/MoreDrawer.kt`、新目录 `ui/screen/map/`、`gradle/libs.versions.toml`（如需依赖）、`app/build.gradle.kts`
1. 左侧弹出抽屉：顶部个人信息（登录态/昵称/头像占位）+ 下方功能模块列表（账单地图/导入账单/设置入口等），Motion 令牌动画，右上角关闭。
2. 账单地图**预实现**：入口点击 → 确认对话框（说明用途 + 同时请求定位权限）→ 地图屏。
   无位置数据与地图 SDK key 的现实约束下：优先引入 `osmdroid`（走 libs.versions.toml，无 key 要求）；
   地图上以**占位标记 + 说明**预实现，代码留好「bills 位置字段接入点」注释；拒绝权限时给引导态。
3. BookkeepingScreen 顶栏「更多」按钮改为打开左抽屉。

### T-F 动画补全 + 输入法 + 滑动手感（对应目标 2 + 10 + 11）
文件独占：`ui/theme/Motion.kt`、`ui/screen/login/LoginScreen.kt`、`ui/screen/profile/BindQQScreen.kt`、`ui/screen/ai/AiScreen.kt`、`ui/component/NumericKeypad.kt`、`ui/screen/quickadd/QuickAddDrawer.kt`、`ui/screen/assets/AccountEditorScreen.kt`
1. 输入法遮盖修复：涉及输入框的屏（登录/绑定QQ/AI 输入栏/键盘备注）加 `imePadding()`/`navigationBarsPadding` 组合并验证不被遮挡。
2. 动画补全：开关/按钮按压态（scale 0.97 + Motion）、tab 指示条已有、抽屉已有；补明显缺口（对话框默认无动画不动），新增令牌先落 Motion。
3. 滑动手感：复查 NavHost 转场参数（主会话已改二级页不复用 tab 转场）；在本批文件范围内不越界。

## 集成 2（主会话）
AppNavigation：接更多抽屉入口（BookkeepingScreen 回调）、`bill-map`、`bill-import` 最终路由；
顶栏图标跳转逻辑按文档重构（更多→左抽屉、AI→ai、金融→assets、登记→快捷记账）；编译 + 全量测试 + 提交。

## 终检
全量 `:app:testDebugUnitTest` + 真机安装冒烟 + 最终评审 + 汇总报告。

## 约束（所有子代理）
- 只允许改动本任务列出的文件；**禁止运行 Gradle**（主会话统一编译）；**禁止 git 提交**。
- 不改 Room schema（不新增迁移）；新依赖必须先加 `gradle/libs.versions.toml` 再引用。
- 所有新 UI 走既有令牌体系；文案注释全中文；金额 Long 分。
