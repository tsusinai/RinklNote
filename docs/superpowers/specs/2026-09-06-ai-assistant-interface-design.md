# AI 助手接口设计（小爱同学等手机 AI）

日期：2026-09-06
范围：给手机语音/文字 AI（小爱同学等）提供「免打扰记账 + 问账」接口，含本地深链。
用户发起：能否提供接口给手机 AI 使用（如小爱同学）。

## 目标

让小爱同学之类的手机 AI 能：
- 一句话免打扰记账（复用后端 NLU：说「午餐20元」→ 自动分类记到三餐）。
- 一句话问账（今日支出 / 本月支出·预算剩余 / 账户余额 / 月度总结与风险提醒）。
- 需要确认/补充信息的场景，通过本地深链打开 App 快速记账抽屉预填、App 内确认。

## 已裁定决策（grilling 结果）

| 维度 | 选择 |
|------|------|
| 接口形态 | HTTP 接口 + 本地深链（两者都要） |
| 记账 | 语音记账（NLU 自动分类） |
| 查询 | 今日支出、本月/预算、账户余额、月度总结与风险提醒 |
| 鉴权 | 每用户个人访问令牌（App 设置生成、可作废、粘贴到小爱技能） |
| 深链 | `rinklnote://add?amount=&category=&remark=&type=` → 预填快速记账抽屉，App 内确认 |

## 架构与数据流

```
小爱语音/文字 → 你配置的小爱技能 POST /api/ai/ask {text}
   Authorization: Bearer <个人令牌>
   → 服务器鉴权 → userId
   → PhoneIntentRouter.route(text, userId)  [复用 Bill/Budget/Insight/NLU]
   → {reply: "已记录：三餐 ¥20"} 或 {reply: "今天已花 ¥38（3笔）"} …
   → 账单落库 billSource="AI" → App 端 SyncManager 周期同步
   → 记账页 / 主屏小组件自动出现
```

关键点：**这套几乎全复用**。真正的增量只有三块——(1) 个人令牌鉴权；(2) `/api/ai/*` 入口；(3) App 端设置页令牌管理 + 深链。NLU 记账、查询、月度总结、异常、同步都是现有实现。

## 服务器端

### 1. 个人令牌鉴权（新增）

新表 `ai_api_tokens`：

```
id, user_id, token_hash, name, created_at, revoked_at
```

- **只存 SHA-256 哈希**，明文仅在生成时返回一次。
- 路由（走现有 JWT 登录态）：
  - `POST /api/ai/tokens`（body `{name}`）→ 生成，返回明文 token + id。
  - `GET /api/ai/tokens` → 列出（id/name/createdAt/revoked，不含明文）。
  - `POST /api/ai/tokens/{id}/revoke` → 作废单个。
  - `POST /api/ai/tokens/revoke-all` → 作废全部。
- 校验：`/api/ai/*`（除 `/api/ai/tokens*` 用 JWT）统一读 `Authorization: Bearer <token>` → `SHA-256(token)` 查表 → 未命中或 `revoked_at` 非空 → 401。

### 2. AI 入口（新增 `AIAssistantRoutes.kt`）

- `POST /api/ai/ask` — 主入口。body `{"text": "午餐20元", "month": "2026-09"?}` → `{reply}`。文本即自然语言。
  路径分配：先补「余额」意图到 `PhoneIntentRouter`，再调用 `route(text, userId)`。
- 结构化副接口（给已在技能里把话参数化的场景）：
  - `POST /api/ai/record`：body `{amount, category?, type?, remark?, date?}` → 直接 `createBill`（type 缺省 EXPENSE）。
  - `GET /api/ai/today`：今日支出/收入。
  - `GET /api/ai/month`：本月支出/收入/预算剩余。
  - `GET /api/ai/balance`：账户余额列表与合计。
  - `GET /api/ai/summary?month=`：`InsightService.monthlyReview` + `anomalyCheck` 合并文本。

### 3. 复用（不改）

- `QQIntentRouter` → 改造成通用 `PhoneIntentRouter`：抽掉「QQ」source，新增 `balance` 意图；记账 source 传 `"AI"`。QQ 机器人继续走原路由，二者共享同一 intent 逻辑。
- `BillService.createBill / allBills / monthlyStats`、`BudgetService`、`InsightService.monthlyReview / anomalyCheck / naturalQuery`、`NLUService.parse`。

## App 端

### 1. 设置页新增「AI 助手接口」

- 用现有登录 JWT 调 `/api/ai/tokens`：生成令牌、给每个令牌命名（如「小爱」）、一键作废/全部作废。
- 显示接口地址与用法（`Authorization: Bearer` 示例、小爱技能如何填）。
- 令牌列表 + 复制按钮。

### 2. 深链 `rinklnote://add`

- MainActivity manifest 加 scheme `rinklnote` 的 intent-filter（`android.intent.action.VIEW`）。
- 解析 `amount/category/remark/type` → 复用现有 `EXTRA_OPEN_QUICK_ADD` + `EXTRA_CATEGORY_ID` 通路 → 打开快速记账抽屉并预填；金额展示在输入区，App 内确认保存。
- 走 `onCreate` + `onNewIntent` 双路径。

## 数据流转与错误处理

- AI 记账写库走现有流程并 `nudgeAccount`，`billSource="AI"` 与 QQ/WEB 同源一致。
- 401（令牌缺失/失效/已作废）→ 明确响应；`amount<=0` → `{reply:"没听清金额，试试'午餐20元'"}`。
- LLM 异常 → 服务端已有规则回退文案。
- 深链缺参 → 默认打开快速记账抽屉（预选默认分类 三餐），不阻塞。

## 验证

1. `./gradlew server:test`（LLM/Intent 相关 JVM 单测，含 balance、record、ask 分发）。
2. `server:run` + curl：`/api/ai/tokens` 生成 → `/api/ai/ask` 用 Bearer 打「午餐20元」「今天花了多少」「余额」「8月总结」。
3. App `assembleDebug` + lint；设置页生成令牌→小爱实测。
4. 深链：`adb shell am start -a android.intent.action.VIEW -d 'rinklnote://add?amount=20&category=三餐'` → 验证预填。

## 边界与风险

- 小爱技能须能带 `Authorization: Bearer`；若受限于小爱技能转交方式，方案退化为「技能做 NLU 再调 `/record`（结构化）」。
- 令牌只在生成时展示一次明文，需提示用户立即复制。
- 深链依赖启动器/小爱对自定义 scheme 的放行；不可用时改用 `https://rinklnote.example/add?...` App Link（本 spec 先做自定义 scheme）。
- 现有 QQ 机器人的 `source`、help 文案与小爱语境差异：`PhoneIntentRouter` 抽分后，AI 端 help 文案独立成段。
