# RinklNote 功能现状与升级规划

双端一体（Android App + Ktor 服务端 + Web SPA + QQ 机器人），全链路支持自然语言与 AI 洞察。
所有 UI 与注释统一使用中文。

## 1. 记账核心

### 当前实现
- 自定义数字键盘（NumericKeypad），无系统 IME、无 Material ripple
- 两段式确认流程：键盘确认 → CountAfter 金额展示 + 绿勾动画 → 最终入库
- 支出/收入切换，一级分类 + 二级子分类（长按弹出）
- 备注输入（系统 IME 的 RemarkInputSheet）
- 右侧抽屉式快捷记账（QuickAddDrawer）：模板 / NLP / 智能推荐 / 常用模板一键录入
- 记账页按日分组「一天一张卡」（日期头 + 星期 + 当日净额），行内滑动删除（滑出删除按钮 → 二次确认）
- 编辑页（BillEditOverlay）：分类 / 账户双卡 + 底部键盘，切换收支类型重置分类
- 月切换（MonthNavigator：‹ › + 回本月）

### 升级规划
- [x] 账单编辑与删除（App + Web + 服务端 API）
- [x] 常用模板一键录入
- [x] NLP 自然语言记账（"午餐25元"）
- [x] 智能推荐（按时段 + 历史）
- [ ] 周期性账单（每月固定支出自动记录）
- [ ] 分类拖拽排序

---

## 2. 语音记账

### 当前实现
- Android `SpeechRecognizer` (zh-CN) 实时出字，本地 `VoiceParser` 边听边解析预览「¥金额 · 分类」
- 服务端 Whisper 兜底（`ASR_API_KEY` 可选；未配置则回退设备识别）
- 解析结果自动填入金额/分类/备注，并自动选中分类
- 底部悬浮迷你语音条（非全屏页面）

### 升级规划
- [ ] 中文数字解析（"二十"→20、"五块"→5）
- [ ] 语音播报确认（TTS）
- [ ] 长按连续多笔录入

---

## 3. QQ 机器人记账

### 当前实现
- [x] QQ 官方 Bot API v2：HTTP webhook（Ed25519 验签）+ WebSocket 网关长连接（op 心跳/断线重连）
- [x] 纯自然语言意图路由（`QQIntentRouter`：帮助 → 删除 → 查询 → 记账 → 待补金额 → LLM 兜底）
- [x] 记账 / 问账 / 删除 / 月结 / 异常 / 建议；openid 身份作用域（`member_openid` 群 / `user_openid` 单聊）
- [x] 绑定码机制 + QQ 即账号（`createByQqOpenid` 自动开户，去掉 App 前置绑定）
- [x] App / Web 跨端同步 QQ 账单
- [x] 回复文案口语化（高频措辞卡 + LLM 温化，测试保留断言子串）

### 升级规划
- [ ] 群聊 @机器人 多人记账（家庭 / 合租）
- [ ] QQ 端主动推送（月结 / 超预算 / 习惯提醒）
- [ ] 微信 / 钉钉平台扩展

---

## 4. 图表分析

### 当前实现
- [x] 支出走势图（`ChartBox`，折线/柱状切换，10 天窗口，单 Canvas 绘制）
- [x] 动画 `Animatable`（tween 1000ms）；标签文本预测量缓存，动画期间不逐帧重复测量
- [x] 底部月度汇总栏（总支出红 + 总收入绿）
- [x] Detail 按钮 → 月度明细弹层（`MonthDetailOverlay`）
- [x] 月度明细：支出/收入汇总卡 + 分类分组清单（`CategoryHeader` + 明细行）
- [x] 每日支出热力图（4×8 矩阵，主蓝 `#7EC1FC` 透明度随当日支出加深，点击筛当天）
- [x] 支出分类饼图（占比 + 图例；>6 类并「其他」；点击筛分类）
- [x] 明细筛选联动（单选中 + 清除 chip，图表与汇总卡保持整月）

### 升级规划
- [ ] 月度对比（本月 vs 上月）
- [ ] 年度折线（12 个月趋势）
- [ ] 日 / 周 / 月 / 年 时间粒度切换
- [ ] 导出图表图片

---

## 5. 资产管理

### 当前实现
- [x] 账户卡片（微信 / 支付宝 / 默认），品牌色图标
- [x] 余额隐私占位（`BalancePrivacy`，资产页 + 抽屉共用眼睛开关）
- [x] 增 / 改名 / 改余额 / 删除（软删除同步）
- [x] 总资产统计卡

### 升级规划
- [ ] 记账时自动关联账户余额变动
- [ ] 转账（微信 → 支付宝）
- [ ] 更多账户类型（信用卡、储蓄卡等）

---

## 6. 计划 / 预算

### 当前实现
- [x] 月度预算卡（进度条 / 已花 / 剩余 / 超预算红字）
- [x] 点卡片弹预算键盘（本地落库 + 尽力推送）
- [x] 预算跨端同步（`budgets` 表，按用户 + 月唯一）
- [x] 本月剩余天数

### 升级规划
- [ ] 分类粒度预算
- [ ] 超预算 App 通知
- [ ] 储蓄目标追踪
- [ ] 账单预测（基于历史日均）

---

## 7. 账单列表与查询

### 当前实现
- [x] 按日分组「一天一张卡」卡片列表（日期头 + 星期 + 当日净额）
- [x] 月切换；Room Flow 响应式刷新
- [x] 月度明细（月度总额 + 分类分组）
- [x] Web 端搜索（备注/分类/日期范围）+ CSV 导出

### 升级规划
- [ ] App 端列表筛选 / 搜索 / 导出
- [ ] 列表分页（大数据量）
- [ ] 时间段切换（季 / 年）

---

## 8. 分类管理

### 当前实现
- 首次启动幂等 seed：7 支出 + 4 收入分类，含二级子分类（App 与 Server 两侧各自补齐）
- 暂不支持用户自定义分类

### 升级规划
- [ ] 用户自定义分类 / 子分类（新增 / 编辑 / 删除 / 排序）
- [ ] 分类图标库扩展
- [ ] 服务端种子数据统一管理（App 与 Server 共用）

---

## 9. 数据管理

### 当前实现
- [x] Room v10（App）+ 服务端多表（users / categories / bills / budgets / bot_config / push_log …）
- [x] Web CSV 导出（BOM 头 + 公式注入防护）
- [ ] 仍用 `fallbackToDestructiveMigration` — 升级丢数据（债务）

### 升级规划
- [ ] 真实 Database Migration（替代 destructive）
- [ ] JSON 备份 / 恢复
- [ ] WebDAV / 云盘自动备份
- [ ] 微信 / 支付宝账单 CSV 导入

---

## 10. 数据同步

### 当前实现
- [x] 双向、本地优先，Last-Writer-Wins 处理冲突
- [x] `updated_at` + 软删除；`server_id` 去重；`base_updated_at` 乐观锁
- [x] 复合游标分页同步（`updatedAt,id`）
- [x] 账单 / 账户 / 预算 / 模板跨端同步
- [x] 周期自动同步（`SyncManager` Mutex 单飞）

### 升级规划
- [ ] 冲突手动选择
- [ ] 后台 WorkManager 定时同步

---

## 11. 主题与 UI

### 当前实现
- [x] Material 3 明 / 暗主题；色彩体系：支出红 `#CA3032`、收入绿 `#04A433`、主蓝 `#7EC1FC`
- [x] 背景三分层（页面底 / 卡片 / 次级容器）
- [x] 底部导航三 tab 上移避开系统手势/导航条 + 四角等曲
- [x] 动效 token（`Motion.kt`）+ 图表标签预测量
- [x] Web 端 Inter 字体 + 暗色适配 + 圆角 token

### 升级规划
- [ ] 用户自选主题色
- [ ] Widget（桌面快速记账 / 查看余额）
- [ ] 通知栏快捷记账

---

## 12. 用户系统

### 当前实现
- [x] 手机号 + 密码注册 / 登录（jBCrypt）
- [x] JWT（HMAC256）+ DataStore / TokenCipher（Keystore 加密）+ 内存缓存
- [x] QQ 绑定 / QQ 即账号（openid 自动开户）
- [x] AI 主动推送开关（`/api/auth/ai`）
- [x] 限流（登录 / 绑定 / 注册；QQ 登录失败也计入）

### 升级规划
- [ ] 短信 / 邮箱验证码
- [ ] 第三方登录（微信 / QQ 快捷登录）
- [ ] 多设备登录与踢出
- [ ] 用户注销与数据删除（GDPR）

---

## 13. LLM / NLU

### 当前实现
- [x] 规则 + LLM 双引擎（`RuleBasedParser` + DeepSeek）；优先级：用户关键词 > 系统默认 > LLM
- [x] `voice_keywords` 自学习（`LearningService` 记录修正反馈）
- [x] 解析端点 `/api/bills/parse` + 转写 `/api/bills/transcribe`
- [x] 月度总结 / 异常检查 / 自然问账（按月解析 `resolveYearMonth`，历史月按窗口聚合喂 LLM）/ 智能推荐
- [x] 习惯提醒（`habitReminder` → `polishHabitCopy`，App / QQ 一套文案）
- [x] 隐私 NFR：只喂 LLM「分类 + 金额 + 日期」聚合，不送明细备注

### 升级规划
- [ ] 模糊语义理解（"和昨天一样"、"再来一单"）
- [ ] 中文数字识别

---

## 架构演进方向

```
现在 (2026-09)
─────────────────────────────────────────
单 Activity + HorizontalPager
Room v10 本地 DB + H2/Ktor 服务端
双向同步 (Last-Writer-Wins + 复合游标)
Retrofit + JWT + DataStore + TokenCipher
StateFlow 缓存 + Flow 响应式
Android + Ktor 后端 + Web SPA + QQ Bot
NLU (规则+LLM双引擎) + 消费洞察 + 习惯提醒
手动 DI (ViewModel Factory)
```

### 关键债务 / 待办
- [ ] `fallbackToDestructiveMigration` → 真实 Database Migration
- [ ] 金额使用 `Double` → 整数分 / BigDecimal（浮点漂移 + 符号判定误差）
- [ ] 服务端 `BillRoutes` 条件 PUT 乐观锁未并入 UPDATE 的 WHERE（并发丢更新）
- [ ] 隐私 NFR：`LearningService.processCorrections` 疑似把原始 utterance 发给 LLM，应核查并聚合化
- [ ] QQ 用户改密码对 `password_hash == null` 会异常
- [ ] 设备时区显示：部分日期展示仍用 `LocalDate.now()`（系统时区），未统一到业务时区
- [ ] ViewModel Factory 样板重复 → DI 框架（可选）
