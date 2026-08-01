# QuickAdd 效率提升 — 模板 + NLP + 智能推荐

**Date**: 2026-08-01
**Status**: Design approved

## Problem

用户记账步骤多（打开→选分类→输金额→确认→再确认），重复消费每次要重选，心智负担重。

## Solution

三个互补的快捷录入通道，共享 QuickAdd 抽屉空间：

| 通道 | 场景 | 交互 |
|------|------|------|
| NLP 输入 | 非固定、灵活记账 | 输入文字 → 解析 → 自动填入 → 确认 |
| 模板 | 高频固定消费 | 点一下直接记账，跳过确认 |
| 智能推荐 | 按时段推测 | 气泡提示 → 点击记账 |

---

## A. 模板 (Templates)

### 数据模型

**服务端** `bill_templates` 表：
```sql
id (PK), user_id (FK, indexed), label, amount,
category_id (FK), category_name, sub_category_name,
account_id (FK), sort_order, created_at, updated_at
```

**Android Room** `BillTemplate` entity：同服端字段 + 通过 SyncManager 同步。

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/templates | 用户模板列表 |
| POST | /api/templates | 新建模板 |
| PUT | /api/templates/{id} | 编辑 |
| DELETE | /api/templates/{id} | 删除 |
| POST | /api/templates/reorder | 批量更新 sortOrder |

### 交互

- 点击 → 直接 `insertBill` + 关闭抽屉（无两段确认）
- 长按 → 弹出编辑/删除浮层
- 最右 "+" → 保存当前选中的分类/子分类/账户为新模板
- 横排滚动，最多 8 个
- SyncManager 拉取时同步到本地 Room

### Web 端

设置页新板块「记账模板」：列表 + 添加表单 + 编辑 + 拖拽排序

---

## B. 智能推荐 (Smart Suggestion)

### 算法

```
输入: userId, 当前时间
1. 查 bills 表最近 N 天 (default 7)
2. 按时段窗口分组 (default: 早7-10, 午11-14, 晚17-20)
3. 每组取 (categoryName, amount) 出现次数最多的 → 候选
4. 次数 >= minOccurrences (default 3) → 输出推荐
5. 无达标 → 不显示
```

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/insights/suggest | 返回推荐或 null |
| GET | /api/insights/suggest-config | 读取配置 |
| PUT | /api/insights/suggest-config | 更新配置 |

### 配置参数 (Web 端可调)

```json
{
  "enabled": true,
  "lookbackDays": 7,
  "minOccurrences": 3,
  "displayDuration": 5000,
  "timeWindows": [
    {"label": "早餐", "startHour": 7, "endHour": 10},
    {"label": "午餐", "startHour": 11, "endHour": 14},
    {"label": "晚餐", "startHour": 17, "endHour": 20}
  ]
}
```

配置存 `bot_config` 表（key=recommend_config, value=JSON）。

### 交互

- 打开抽屉时异步请求 `/api/insights/suggest`
- 有结果 → 模板区域下方气泡："💡 这个时间你在吃午餐？午餐 ¥25 [记]"
- 点击 → 一键记账（同模板）
- `displayDuration` 毫秒后自动收起
- 请求失败/无推荐 → 静默，不显示

### Web 端

设置页「智能推荐」板块：开关 + 参数 slider/input

---

## D. NLP 输入

### 解析引擎

优先级: 服务端 NLU (LLM+规则) > 本地正则 fallback

```
输入 "午餐25元" → POST /api/nlu/parse → 
  { categoryName: "三餐", amount: 25 } → 自动填入 UI
```

断网时本地正则: `(\d+\.?\d*)\s*[元块]?` — 只提取金额，不解析分类。

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/bills/parse | text → {categoryName, amount, remark} |

复用 `NLUService.parse()`，传入 userId=当前用户。

### 交互

- 输入栏在模板上方，placeholder: "午餐25元"
- 输入 → 点确认/回车 → 调用解析
- 成功 → 填入分类+金额，输入栏清空
- 失败 → 输入栏抖动 + 提示
- 填入后用户手动确认（走现有两段确认流程，或直接确认）

### 两段确认简化

NLP 填入后 → 直接一步确认（跳过 CountAfter 绿勾），因为用户已经通过文字确认了意图。模板同理。仅手动选分类+输入金额时保留两段确认。

---

## 数据流

```
┌─ 模板 ───────────────────────────────────────────┐
│ Web 编辑 → 服务器 → Android SyncManager 拉取同步   │
│ 点击模板 → 本地 insertBill → pushBill (后台)      │
└──────────────────────────────────────────────────┘

┌─ NLP ────────────────────────────────────────────┐
│ 输入 → POST /api/bills/parse → 填入 UI            │
│ 离线: 本地正则提取金额                            │
└──────────────────────────────────────────────────┘

┌─ 推荐 ───────────────────────────────────────────┐
│ Web 调参 → 服务器 ← 打开抽屉时 GET suggest       │
│ ↓ 返回则显示气泡                                 │
└──────────────────────────────────────────────────┘
```

## 实现顺序

1. 服务端 — 模板 CRUD + NLU parse 端点 + suggest API + config
2. Android — 模板 UI + NLP 输入栏 + 推荐气泡 + 确认简化
3. Web — 模板管理页 + 推荐配置页
