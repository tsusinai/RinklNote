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

---

# 第二轮（2026-09-15，用户 6 项新优化，分支不变）

## R2-A1（MoreDrawer.kt 独占 + 新 ui/screen/currency/ + SettingsManager.kt）
1. 更多抽屉对齐快速记账抽屉：宽度与 QuickAddDrawer 面板一致（200dp）、顶部个人信息区 padding
   对齐（statusBarsPadding + 面板 vertical 8dp 内边距节奏）；**拉出时背景不黑化**——去掉黑 scrim，
   改为透明触摸层收 dismissal（对照 QuickAddDrawer 的遮罩实现）。
2. 多币种预实现：MoreDrawer 新增「多币种」行（LocationOn 风格图标体系内选合适图标）→
   新页 MultiCurrencyScreen（路由 multi-currency 主会话接线）：本位币选择（默认 CNY，存
   SettingsManager 新键 base_currency，代码表用 ISO 4217 常用币种：CNY/USD/EUR/JPY/GBP/HKD/KRW），
   静态演示汇率表（标注「演示数据，记账换算为后续接入点」注释），不改 Room schema。

## R2-A2（BillMapScreen.kt 独占）
1. AccessBlocked 修复：OSM MAPNIK 封锁默认 UA——瓦片源换成国内可直连的公共源（高德
   webrd01..04.is.autonavi.com appmaptile style=7，逐服务器轮询），TileSource 自定义
   + UA 保持 packageName；保留演示标记。
2. 定位 UI：标记改经典大头针样式 + 上方椭圆标签气泡（分类名 + 金额），配色走主题令牌
   （支出 tertiary / 收入 IncomeGreen，底面 surface / 白雾）。

## R2-A3（PlanScreen.kt 独占）
计划主页已有「分类预算设置」入口：移除主页分类预算卡上的「未设 · 去设置」增加引导框
（已设预算的分类展示保留），避免双入口。

## R2-B1（QuickAddDrawer.kt + BillEditOverlay.kt + Motion.kt 独占）
动画优化：编辑账单账户选择的伸展动画（animateContentSize/expand 收敛 Motion）；快速记账
一级标签切换后二级标签的过渡（切换分类时二级区 AnimatedContent/淡入淡出 + Motion 令牌）。

## R2-B2（新 ui/screen/web/ 独占）
WebView 屏 WebScreen（路由 web-view?url={url}，Url 编解码；顶部 ArrowBack + 标题 +
加载进度条；WebViewClient/JS 开启），用于 QQ 机器人绑定引导页内打开；导航层接线由主会话做，
ProfileCards 的引导入口是否改跳由主会话集成时定。

## 集成 R2（主会话）：AppNavigation 接 multi-currency / web-view 路由与回调；编译 + 测试 + 提交。

---

# 最终轮（2026-09-15，用户 8 项，完成后提交合并 main）

## 跨代理契约（A1 实现，其余按此消费，主会话编译兜底）
SettingsManager 新增：`avatarUri: Flow<String?>`/`setAvatarUri`、`nickname: Flow<String?>`/`setNickname`、
`showCurrencySymbol: Flow<Boolean>`(默认 true)/`setShowCurrencySymbol`、`cardOverlay: Flow<Boolean>`(默认 false)/`setCardOverlay`、
NAV_ICON 主题槽键（RinklThemeSlot 加 NAV_ICON，RinklColors 加 `navIconColor: Color?` 默认 null）。
新建 ui/util/DisplayPreferences.kt（仿 BalancePrivacy 全局态）：currencySymbolVisible/cardOverlayEnabled 两个 StateFlow；
MainActivity collect SettingsManager 两流同步；Money.format 按 DisplayPreferences 决定是否带 ¥（关闭=输出 formatPlain 观感）。
applyCardGlass 读 cardOverlayEnabled，开启时 border 前插白色蒙版 background(White.copy(alpha=0.55f))。

## R3 批 1
- **A1 个性化系统**（RinklColors.kt/SettingsManager.kt/CustomThemeScreen.kt/HazeBackground.kt/util/Money.kt/新建 ui/util/DisplayPreferences.kt/MainActivity.kt）：
  6.1 NAV_ICON 第 8 槽（底栏图标色独立于字体色，默认 null 回落现状）；所有可独立设置图标色的消费点在报告里列出；
  货币符号开关生效于 Money.format；6.2 卡片白蒙版开关生效于 applyCardGlass；SettingsManager 全部新键与持久化；CustomThemeScreen 补 NAV_ICON 槽行。
- **A2 我的页分组与头像昵称**（ProfileScreen.kt/ProfileCards.kt）：
  3. 分组调整：「账户与安全」拆出「个性化」（主题/自定义主题/选择背景/移除背景 + 新增昵称行 + 头像行）；
  4. 头像：行点击 ActivityResult PickVisualMedia → 存 DataStore avatarUri（ProfileHeader 圆形显示，未选=首字符徽章）；昵称：行点击弹输入 AlertDialog → setNickname；ProfileHeader 显示优先级 自定义昵称 > 手机号。
  6.2 开关行「卡片白色蒙版」放「个性化」组尾（Switch 读/写 cardOverlay 流）。
- **A3 搜索账单**（新建 ui/screen/search/、MoreDrawer.kt）：
  5. MoreDrawer 加「搜索账单」行（onOpenSearch 回调，默认 {}）+ **顶部 padding 再优化**（7：statusBarsPadding 后顶部再加 12dp 呼吸、头像区与功能区间距拉开）。
  SearchBillsScreen（路由 bill-search 主会话接线）：搜索框（分类/备注/金额/子分类/标签按 Bill 实际字段，agent 自查）+
  筛选（种类 收入/支出/全部；日期 按天/按月/自定义起止范围）+ 结果账单行（点击 onEditBill 回调进编辑）。
  数据：repository.observeAllBills() 内存过滤（不动 Dao/schema）。VM 模式 State+Event。
- 集成 R3-1：编译测试提交。

## R3 批 2
- **A4 登录升级**（LoginScreen.kt 独占，可小改 AuthViewModel.kt）：
  2. UI 升级（品牌区/间距/按钮 pressScale 已有体系）、手机号验证（^1[3-9]\d{9}$，错误提示 12sp）、「忘记密码」入口：
     服务端若无对应端点则 UI 占位（AlertDialog 说明 + AuthEvent/接口预留注释「暂留接口」）。
- **A5 地图金额常驻**（BillMapScreen.kt 独占）：8. 标签气泡常驻显示（合成进 marker bitmap 或 addOverlay 后直接 open），保留点击交互不回退。
- **A6 Web 同步优化**（web/ 独占，可跑 npm build）：1. --on-primary 令牌化（消灭 #0b2b44 硬编码 16 处）、
  图表色板对齐 App PiePalette、border-radius 10px→12px 收敛、grid gap 统一 12px、.card padding 18→16、card-title 15→14px。
- 集成 R3-2：AppNavigation 接 bill-search 路由与 CustomBottomBar navIconColor；编译 + 测试 + 提交。

## 终局：真机安装 + web build 验证 + 合并 main + 推送。
