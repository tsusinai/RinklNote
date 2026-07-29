# LLM 智能解析系统设计

日期: 2026-07-29 | 状态: 已确认 | 模型: DeepSeek (OpenAI 兼容接口)

## 架构总览

```
┌──────────────────────────────────────────────────────┐
│  NLUService (接口)                                    │
│  ├─ RuleBasedParser  (用户关键词 → 系统关键词)         │
│  ├─ LLMParser        (DeepSeek 兜底)                  │
│  └─ LearningService  (批量学习 → 生成关键词)           │
├──────────────────────────────────────────────────────┤
│  InsightService  (消费洞察)                           │
│  ├─ 月度总结 GET /api/insights/monthly                │
│  ├─ 异常检测 GET /api/insights/anomaly                │
│  └─ 自然语言查询 POST /api/insights/query              │
└──────────────────────────────────────────────────────┘
```

## 1. 解析链 (实时调用路径)

```
输入文本 "午餐花了25元"
  └─ Step 1: 金额提取 (正则，已有)
  └─ Step 2: 用户自定义关键词匹配 (查 voice_keywords 表，priority DESC)
  └─ Step 3: 系统关键词匹配 (已有 VoiceParser 规则)
  └─ Step 4: DeepSeek LLM 兜底 (仅当 Step 2/3 均未匹配分类)
       └─ 返回 { categoryName, subCategoryName, remark }
       └─ 自动写入 voice_keywords (priority=5)
```

### DeepSeek API 调用

- 端点: `https://api.deepseek.com/v1/chat/completions`
- 模型: `deepseek-chat`
- Prompt 输入: 用户文本 + 可用分类列表
- 输出格式: JSON `{categoryName, subCategoryName?, remark?}`
- 用 response_format: { type: "json_object" } 确保结构化输出
- 超时: 10s，失败则降级为默认分类

### 自动学习（实时路径内的）

LLM 成功解析后自动写入 `voice_keywords`:
- `priority = 5` (低于手动管理的默认 10)
- 包含原始输入文本中的关键词片段
- 下次相同文本直接走 Step 2，避免重复调用 LLM

## 2. 学习环 (批量学习路径)

```
用户修正流水 (correction_log 新表)
  └─ 定时任务 (每小时)
       └─ 收集未处理的修正记录
       └─ 批量送 DeepSeek → 提炼关键词模式
       └─ 写入 voice_keywords (priority=8)
       └─ 标记已处理
```

### 修正触发来源

- QQ Webhook: 用户发送更正指令 ("不是三餐，改成外卖")
- Web 端: 账单列表中编辑已记账分类
- Android 端: 暂不接入 (后续远程同步再加)

### correction_log 表

| 列 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (自增) | 主键 |
| user_id | BIGINT | FK -> users.id |
| original_text | VARCHAR(500) | 原始输入文本 |
| original_category | VARCHAR(50) | LLM/规则给出的分类 |
| corrected_category | VARCHAR(50) | 用户修正的分类 |
| processed | BOOLEAN | 是否已被批量学习处理 |
| corrected_at | VARCHAR(30) | ISO 时间戳 |

### 定时任务

- 实现: Ktor 启动时 `launch { while(isActive) { delay(3600000); processCorrections() } }`
- 每次收集未处理记录，批量送 LLM，生成关键词写入 voice_keywords
- 如果无未处理记录则跳过

## 3. 洞察层

所有端点均需 JWT 认证。

### 3.1 月度总结 `GET /api/insights/monthly?month=2026-07`

- 输入: 当月账单聚合数据
- LLM 生成中文自然语言总结
- 输出: `{ summary, highlights[] }`
- 缓存: 用户+月份 key，账单变动后缓存失效

### 3.2 异常检测 `GET /api/insights/anomaly`

- 规则先行 → LLM 包装语义化表述
- 检测: 日支出 vs 30天均值的 150% 阈值
- 输出: `{ alerts: [{ level, message, type }] }`
- 触发: 每次记账后异步检查，有异常存 alerts 表

### 3.3 自然语言查询 `POST /api/insights/query`

- 输入: 用户自然语言问题
- LLM 返回查询参数 (不直接生成 SQL)
- 用参数查数据库 → LLM 格式化回答
- 输出: `{ answer }`

## 4. API 端点汇总

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/keywords` | JWT | 获取用户自定义关键词 |
| POST | `/api/keywords` | JWT | 添加关键词 |
| DELETE | `/api/keywords/:id` | JWT | 删除关键词 |
| POST | `/api/corrections` | JWT | 记录分类修正 |
| GET | `/api/insights/monthly` | JWT | 月度 AI 总结 |
| GET | `/api/insights/anomaly` | JWT | 异常检测 |
| POST | `/api/insights/query` | JWT | 自然语言查询 |

已有端点变更:
- `POST /api/qq/webhook` — 解析调用链改为 NLUService，支持修正指令识别
- `PUT /api/bills/:id` — 新增：Web 端编辑账单(触发修正记录)

## 5. 新增服务端文件

```
server/src/main/kotlin/com/example/rinklnote/server/
├── services/
│   ├── nlu/
│   │   ├── NLUService.kt          # 接口定义
│   │   ├── RuleBasedParser.kt     # 关键词匹配 (用户+系统)
│   │   ├── LLMParser.kt           # DeepSeek API 调用
│   │   └── LearningService.kt     # 批量学习定时任务
│   └── insight/
│       └── InsightService.kt      # 洞察层三个功能
├── routes/
│   ├── KeywordRoutes.kt           # 关键词管理 API
│   ├── CorrectionRoutes.kt        # 修正记录 API
│   └── InsightRoutes.kt           # 洞察 API
└── tables/
    ├── CorrectionLogTable.kt      # correction_log
    └── InsightCacheTable.kt       # 洞察缓存 (可选)
```

## 6. 配置项 (application.conf / 环境变量)

| 变量 | 默认值 | 说明 |
|------|------|------|
| DEEPSEEK_API_KEY | (必填) | DeepSeek API 密钥 |
| DEEPSEEK_BASE_URL | https://api.deepseek.com | API 地址 |
| DEEPSEEK_MODEL | deepseek-chat | 模型名 |
| LLM_TIMEOUT_MS | 10000 | 请求超时 |
| LEARNING_INTERVAL_MIN | 60 | 批量学习间隔 (分钟) |
| ANOMALY_THRESHOLD | 1.5 | 异常检测阈值 (倍数) |
