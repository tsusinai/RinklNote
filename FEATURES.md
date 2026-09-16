# RinklNote 功能现状与升级规划

双端一体（Android App + Ktor 服务端 + Web SPA + QQ 机器人），全链路支持自然语言与 AI 洞察。
所有 UI 与注释统一使用中文。

## 1. 记账核心

### 当前实现
- 自定义数字键盘（NumericKeypad），无系统 IME、无 Material ripple
- 一步制确认：键盘确认 → 校验分类/账户/金额 → 直接落库；可选「位置」chip 主动打点（账单上账单地图）
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
- [x] 连续多笔录入（记账抽屉语音条每记一笔自动回到聆听，一句可拆多笔）
- [x] 中文数字本地转阿拉伯（"二十元"→20；支持零一两…九、十百千万组合）
- 底部悬浮迷你语音条（非全屏页面）

### 升级规划
- [ ] 中文数字解析（服务端 NLU 侧通用支持，本地 VoiceParser 已覆盖常见组合）
- [ ] 语音播报确认（TTS）

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

### 多通道机器人（飞书 / 企业微信 / 订阅号）

#### 当前实现
- [x] 飞书全量接入：webhook 事件订阅（challenge 回验证 + Encrypt Key AES-256-CBC 解密 + X-Lark-Signature 验签）、收发 `im/v1/messages`（tenant_access_token 2h 缓存 Mutex 单飞）、**日报主动推送无窗口限制**
- [x] 企业微信智能机器人：回调收消息（SHA1 验签 + AES-256-CBC/PKCS7 + XML，`wx/WxCryptUtil` 手写零依赖）+ 同步被动回复 + 「消息推送」webhook 主动推
- [x] 微信订阅号：echostr 验证 + 5 秒同步被动回复（管线内 LLM 兜底 4s 硬超时）、语音 `Recognition` 免费转写；**只收不推**（主动推送需认证服务号）
- [x] 三通道绑定：`users` 新增 `feishu_open_id` / `wechat_openid` / `wecom_userid` 身份列（可空 + 唯一索引）；6 位绑定码流程三通道同构（App 绑定页通道选择 + Web「多通道机器人」卡片），绑定码防爆破限流照 QQ
- [x] 分通道推送调度：日报 / 提醒按 **飞书 > 企业微信 > QQ** 取第一个已绑定通道；记账落库 source 标记 `FEISHU` / `WECOM` / `MP`（`BotCommands` 共享词表）
- [x] Web 控制台「多通道机器人」配置卡片：三通道状态 / 凭证 / 绑定统一管理（chip 切换），secret 类字段掩码输入可切明文，服务端掩码回显（`web/src/api/bots.ts` 按 channel 统一封装三通道管理面）
- [x] 旧 QQ 号绑定流程下线：`/api/auth/bind-qq`、`/api/auth/unbind-qq` 已删除，绑定状态以服务端三通道身份列为准；旧 `/api/qq/webhook` 共享密钥协议标注废弃但保留运行（防未知外部旧客户端断裂）

#### 升级规划
- [ ] 真机冒烟：飞书 / 企微后台回调配置需线上环境与账号（步骤见 `docs/bot渠道机制调研与选型.md` 末尾「部署冒烟清单」）
- [ ] 企微单聊主动触达边界核对（对照官方文档确认智能机器人单聊推送窗口）
- [ ] 语音转写扩展：企微语音接 ASR 兜底（飞书 v1 仅文本，订阅号已有免费 Recognition；QQ 平台自带 `asr_refer_text`）

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
- [x] 账户卡片（自定义增删改，新账号仅预置「无账户」），品牌色图标
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
- [x] App 端搜索账单（文本 / 种类 / 日期筛选，`bill-search` 路由）+ CSV 导入（`bill-import`）

### 升级规划
- [ ] App 端列表筛选 / 搜索 / 导出
- [ ] 列表分页（大数据量）
- [ ] 时间段切换（季 / 年）

---

## 8. 分类管理

### 当前实现
- 内置分类库（幂等 seed，App 与 Server 两侧各自补齐、清单逐字一致）：
  - 一级 25 个 —— 支出 18：三餐/日用/交通/学习/运动/娱乐/网购/医疗/居家/人情/宠物/美妆个护/服饰/母婴/汽车/数码/保险/旅行；收入 7：工资/兼职/理财/其他/报销/二手转卖/红包礼金
  - 二级按一级补齐（如 三餐→早餐/午餐/晚餐/零食/外卖/饮品，医疗→门诊/药品/体检/口腔/眼镜），完整清单见 `BillRepositoryImpl.seedSubCategories()` / `BillService.seedSubCategories()`
- 老安装升级自动补新分类：一级按 (name, bill_type) 幂等插入、二级按 (parent, name) 幂等插入；顺序即 id 顺序，只允许追加不许重排（bills 存分类名快照、budgets 引用二级 id、两端靠同序对齐自增 id）
- 快速记账抽屉：长按一级呼出二级标签，有二级的行尾带「···」提示标记；编辑浮层与 Web 为显式两段选择
- 暂不支持用户自定义分类

### 升级规划
- [ ] 用户自定义分类 / 子分类（新增 / 编辑 / 删除 / 排序）
- [ ] 分类参与同步（当前仅账单/预算同步，分类靠两端 seed 同序对齐）
- [ ] 服务端种子数据统一管理（App 与 Server 共用）

---

## 9. 数据管理

### 当前实现
- [x] Room v16（App，8 表含 challenges）+ 服务端多表（users / categories / bills / budgets / challenges / bot_config / push_log …）
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
- [x] AI 页快捷询问 chips（支出总结 / 支出异常 / 支出分析一键发送）+ 顶栏返回键
- [x] 月度总结 / 异常检查 / 自然问账（按月解析 `resolveYearMonth`，历史月按窗口聚合喂 LLM）/ 智能推荐
- [x] 习惯提醒（`habitReminder` → `polishHabitCopy`，App / QQ 一套文案）
- [x] 隐私 NFR：只喂 LLM「分类 + 金额 + 日期」聚合，不送明细备注

### 升级规划
- [ ] 模糊语义理解（"和昨天一样"、"再来一单"）
- [ ] 中文数字识别

---

## 14. 省钱挑战与成就（2026-09-16）

### 当前实现
- [x] 三类挑战：无消费日（月，目标天数）/ 连续记账（滚动 7~100 天档位）/ 每周预算（周支出上限，默认 = 月总预算×7÷当月天数向上取整）
- [x] 进度全部由账单实时派生（`ChallengeEngine` 纯函数），`challenges` 表只存承诺；断签 / 周期结束懒回写 ACHIEVED / MISSED
- [x] 结余预测器（日均外推 + 预计结余 / 超支预警）+ 少买计算器（月内分类排行 × 10%~100% 滑杆）
- [x] 15 枚成就徽章墙（记账 / 省钱 / 预算 / 挑战四类，锁定态显示进度 x/y；防刷 = 删账单实时回退）
- [x] 3 个解锁预设主题（晨曦 / 薄荷 / 琥珀，解锁态纯派生自成就，`CustomThemeScreen` 锁定行一键应用）
- [x] 入口：记账页「更多」抽屉「Rk省钱计划」行 → 独立挑战页（`challenges` 路由；2026-09-16 自计划页摘要卡迁移）
- [x] 三端同步：Room v15 `challenges` 表 + GET/PUT `/api/challenges` + SyncManager 挑战段（LWW + 软删复活）

---

## 15. 当天账单页与分享（2026-09-16）

### 当前实现
- [x] `day-detail/{dayStart}` 独立页：单日账单列表 + 当日收支汇总，点行进编辑
- [x] 分享按钮：Canvas 绘制 1080px 账单分享图（FileProvider `content://`，超长截断提示），可分享至 QQ / 微信等
- [x] 入口：记账页「一天一张卡」日期头 / 账单地图

---

## 16. 账单地图真实化（2026-09-16）

### 当前实现
- [x] bills 增加 `latitude/longitude`（Room v16 + 服务端列 + API 透传，三端同步）
- [x] 主动打点来源：快速记账「位置」chip、编辑页位置区（`LocationGrabber` 一次性定位）；语音 / QQ / AI 不打点
- [x] 地图仅显示真实打点账单，按 ~11m 网格聚合标记（「分类 ¥金额 ×N」），点标记 → 编辑账单；支持 `focusBillId` 聚焦指定账单
- [x] 「回到我的位置」按钮 + 首次进入自动定位
- 已知取舍：账单存 WGS-84，高德瓦片为 GCJ-02，未纠偏（大陆视觉偏移数百米，纠偏 TODO 在 `BillMapScreen`）

---

## 17. Web 管理员控制台（2026-09-16，P0）

### 当前实现
- [x] 权限模型：`ADMIN_IDENTITIES` 环境变量（手机号/userId）+ JWT admin claim 双保险；`/api/auth/me` 返回 `isAdmin`
- [x] 漏洞收口：`POST /api/qq-bot/config`、`PUT /api/insights/suggest-config` 改为管理员专属（后者 GET 保持全员只读）
- [x] 管理端只读 API：`GET /api/admin/{overview,users,push-logs,bot/status}`——聚合计数 + 手机号掩码，绝不返回备注/明细（隐私红线）
- [x] WS 热重连：机器人配置保存后自动 `restartGateway()`，无需重启进程
- [x] Web `/admin` 区：AdminLayout（深色侧栏 + 「运维」徽标）+ 四页（运维大盘/用户管理/机器人运维/推送历史），非管理员守卫 404 化（不暴露存在）
- [x] 机器人离线红条「静态」告警（管理端零常驻动效——规格红线）

### 升级规划（P1 蓝图，未实施）
- [ ] 封禁/解封（disabled 列 + token_version 吊销）、重置密码、审计日志、手动推送、suggest-config 管理页

---

## 架构演进方向

```
现在 (2026-09)
─────────────────────────────────────────
单 Activity + Navigation Compose
Room v16 本地 DB + H2/Ktor 服务端
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
