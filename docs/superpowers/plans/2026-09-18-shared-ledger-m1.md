# 共享账本 M1 详细计划（数据模型 / 同步协议 / 权限模型 / 里程碑）

> **状态**：M1 设计文档（2026-09-18 立项，源自《2026-09-18-all-domain-optimization.md》Task 4.5）。**本计划获批前不写任何代码。**
> **前置依赖**：Room v17（Task 2.6 `places` 表）先行落地，共享账本走 **Room v18**；服务端表结构由 Exposed 自动迁移承载。

## 1. 背景与目标

记账天然存在「家庭 / 情侣 / 合租」场景：多人各自记账、账单合并视图、成员互见。共享账本在不破坏现有「本地优先 + LWW + 整数分」三端约定的前提下，为账单引入可选的 **账本（ledger）维度**：

- 每个用户有且仅有一个 **个人账本**（默认，历史数据全部归属），可创建 / 加入若干 **共享账本**；
- 账单永远属于且只属于一个账本；记账时默认写入当前选中账本；
- 同步、金额（整数分 `Long`）、软删除（`deleted=1, dirty=1`）语义全部沿用，不做例外。

**非目标（M1 明确不做）**：账单级细粒度权限、成员角色分级（仅 owner/member 两级）、共享账本内预算/挑战共享、服务端实时推送（沿用既有增量拉取）。

## 2. 数据模型

### 2.1 服务端（PostgreSQL / H2，Exposed `tables/`）

```sql
-- 账本
CREATE TABLE ledgers (
  id            BIGSERIAL PRIMARY KEY,
  name          VARCHAR(64)  NOT NULL,            -- 展示名（如「家庭账本」）
  owner_user_id BIGINT       NOT NULL,            -- 创建者，users.id 外键
  invite_code   VARCHAR(16)  NOT NULL,            -- 邀请码（大写字母+数字 8 位，全局唯一）
  created_at    BIGINT       NOT NULL,            -- epoch millis（与三端时间戳口径一致）
  updated_at    BIGINT       NOT NULL,
  deleted       BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX ux_ledgers_invite_code ON ledgers(invite_code);

-- 成员（账本 ↔ 用户 多对多）
CREATE TABLE ledger_members (
  id         BIGSERIAL PRIMARY KEY,
  ledger_id  BIGINT    NOT NULL,                 -- ledgers.id 外键
  user_id    BIGINT    NOT NULL,                 -- users.id 外键
  role       VARCHAR(8) NOT NULL,               -- 'owner' | 'member'
  joined_at  BIGINT    NOT NULL,
  UNIQUE (ledger_id, user_id)
);
CREATE INDEX ix_ledger_members_user ON ledger_members(user_id);

-- 账单归属
ALTER TABLE bills ADD COLUMN ledger_id BIGINT NULL;   -- NULL = 存量个人账本（兼容窗口，见 §3 迁移）
CREATE INDEX ix_bills_ledger ON bills(ledger_id);
```

- `bills.ledger_id` **先可空**：服务端启动迁移时把所有存量账单 + 存量用户的 `ledger_id` 回填为各自新建的个人账本 id（幂等：`bot_config` KV 记 `ledger_backfill_done` 版本号防重跑）。回填完成后新代码即可按「非空」假设读写；**不在 M2 删列或改 NOT NULL**，避免与旧 App 兼容冲突（见 §3）。
- `ledger_members` 无软删除：成员移除 = 物理删除行（成员关系不参与账单同步，直接以接口为准）。

### 2.2 Android（Room v16 → v18）

- v17（Task 2.6 `places`）先行。共享账本 v18 新增两张表 + bills 加列，**顺序敏感的重建经验沿用 v13 教训**：本次只 `ALTER TABLE bills ADD COLUMN ledger_id INTEGER NULL` 与建新表，**不重建任何既有表**，规避外键顺序坑。
- 实体与 DAO：
  - `LedgerEntity(id, serverId 唯一索引, name, ownerUserId, inviteCode, role, createdAt, updatedAt, deleted)`——本地一张表即同时承载账本信息与「我在该账本的角色」（服务端下发时合并写回）；
  - `LedgerMemberEntity(id, serverId, ledgerServerId, userId, role, joinedAt)`——仅缓存成员列表供管理页展示，不参与同步循环；
  - `BillEntity.ledgerServerId: String?`（本地账单存服务端账本 id；同步层按 §3.2 映射）。本地 `ledgerId`（Room 自增）不引入，账单-账本关联只走服务端 id，避免本地/远端两套 id 漂移。
- `seedIfNeeded()` 不动：个人账本不在本地播种，首次同步由服务端下发（本地优先体现在「离线仍可记账」——未同步的账单带 `ledgerServerId = null`，同步上行时由服务端归入该用户个人账本）。

### 2.3 Web（M4）

Web 不落本地库，直接消费服务端 API（见 M4），无 schema 变更。

## 3. 同步协议扩展

### 3.1 总原则

LWW、软删除、`updated_at` 增量拉取、`base_updated_at` 条件 PUT（Task 0.1 乐观锁）**全部不变**；扩展点只有「账单读写必须锚定账本」。

### 3.2 去重与标识

- **`server_id` 去重按 ledger 维度**：同一用户的 `server_id` 在服务端本就全局唯一，但客户端同步缓存按账本分区——增量拉取接口 `GET /api/bills/sync?after=<ts>` 增加 **`ledgerId`（服务端账本 id）必填参数**，响应也按该账本过滤；客户端每个账本独立维护 `lastSync` 游标（Room `ledger_entity.last_sync` 或 DataStore KV）。
- 旧客户端（不认识 `ledgerId` 参数）：服务端检测到未带 `ledgerId` 的同步请求时，**回落为仅同步该用户个人账本**（存量行为完全兼容）。
- 上行：`POST/PUT /api/bills` 请求体新增可选 `ledgerId`；缺省归入个人账本；携带的 `ledgerId` 校验成员资格（§4），无资格返回 403。
- 账单迁移（把某笔账单移到另一账本）**M1 不支持**——`ledger_id` 写入后不可变，避免 LWW 在「移动」语义上产生歧义；误记账单走删除重记。

### 3.3 账本元数据的同步

- `ledgers` / 成员关系**只从服务端拉**（`GET /api/ledgers`、`GET /api/ledgers/{id}/members`），不做客户端上行同步；改名走服务端接口，拉取即得（账本数量级 ≤ 个位数，全量拉取即可，无需增量游标）。

## 4. 权限模型

| 能力 | owner | member | 非成员 |
|---|---|---|---|
| 查看账本、账单、成员 | ✅ | ✅ | ❌ 404（不暴露存在） |
| 记账 / 编辑 / 删除账单 | ✅ | ✅ | ❌ |
| 改名 / 解散账本、移除成员 | ✅ | ❌ | ❌ |
| 退出账本 | ✅（需先转让或解散） | ✅ | — |

- 全部校验在服务端路由层执行：`requireLedgerMember(ledgerId)`、`requireLedgerOwner(ledgerId)` 中间件（放 `server/.../routes/`，复用 JWT 中间件后置）。
- **非成员一律 404**（与 Web 管理端守卫同一哲学：不暴露存在性）。
- 成员上限：8 人（含 owner），邀请码加入时校验，防止滥用。
- 删除账单沿用软删；owner 解散账本 = 账本软删 + 成员关系物理清除 + 名下账单**保留但归属悬空**（M1 约定：解散前必须清空或导出账单，服务端在「账本仍有账单」时拒绝解散并提示，杜绝悬空态）。

## 5. 隐私与产品预期

- **成员间互相可见账单（含金额、分类、备注）是共享账本的产品预期**。加入确认页必须明示：「加入后，你在此账本内的全部记账（含备注）对全体成员可见」——该文案纳入 M3 App UI / M4 Web UI 验收项，缺失即打回。
- 隐私 NFR（送 LLM 仅聚合）不受影响：共享账本不改变洞察/教练的输入口径，AI 洞察仍按用户维度聚合；**跨成员的备注绝不离开成员边界**。

## 6. 里程碑与验收

### M2 服务端 API（先行，无 UI 也可用）

- 端点：`POST /api/ledgers`（创建，自动 owner）、`GET /api/ledgers`（我的账本列表）、`PUT /api/ledgers/{id}`（改名）、`DELETE /api/ledgers/{id}`（解散，校验空账本）、`POST /api/ledgers/join`（invite_code 加入）、`DELETE /api/ledgers/{id}/members/{userId}`（移除/退出）、`GET /api/ledgers/{id}/members`。
- bills 全套（CRUD / sync / search）接入 `ledgerId` 校验与过滤；启动迁移回填个人账本。
- 验收：H2 单测覆盖权限矩阵（§4 全格）、邀请码唯一性、成员上限、解散保护、旧客户端无 `ledgerId` 回落；`:server:test` 全绿。

### M3 App UI

- 账本切换器（记账页顶栏 / 更多抽屉入口）、创建与邀请码分享（二维码可后置）、成员管理页、加入确认页（**含成员互见明示文案**）。
- 同步：SyncManager 按 §3.2 扩展多账本游标；真机双设备同步冒烟（A 记账 B 可见、断网回落个人账本）。
- 验收：`:app:testDebugUnitTest` 全绿 + 双设备同步冒烟；迁移路径 v17→v18 覆盖安装不丢数据。

### M4 Web + Bot

- Web：顶栏账本切换器（Session 存储，默认个人账本）、账单/图表按账本过滤；多币种等二级页不受影响。
- Bot：查询类指令默认个人账本；新增 `@账本名` 前缀语法查询共享账本（四通道同构，走 `BotCommands` 常量）。
- 验收：`web` typecheck+Vitest 全绿；Bot 三通道冒烟 `@家庭账本 本月支出`。

### 每里程碑收尾

三端全量测试 + `git status --short --branch` 干净 + 真机/部署冒烟，按里程碑各自提交（沿用 `<type>(<scope>): 中文描述`，禁止 `git add -A`）。

## 7. 风险与开放问题

1. **旧客户端兼容**：`amount` 兼容字段教训重现——新增请求/响应字段对旧端必须可缺省，`ledgerId` 一律可空 + 服务端兜底归个人账本。
2. **同步游标多份化**：SyncManager 由单游标变多游标，注意 Mutex 单飞仍覆盖全部账本（一个循环同步所有账本，顺序拉取，避免并发写 Room）。
3. **解散保护**的空账本判定以「未删账单数 = 0」为准，服务端单一实现，客户端只提示。
4. 开放问题（获批前需拍板）：邀请码有效期与重置策略；member 是否可以编辑/删除他人账单（当前口径：**可以**，LWW 无例外——如需「仅自己可改」须在 M2 前钉死并加服务端校验）。
