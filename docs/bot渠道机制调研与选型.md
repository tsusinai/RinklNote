# Bot 渠道机制调研与选型

> 目的：为「记一笔」接入飞书 / 微信系 bot 通道做机制层调研，明确每个通道的前置条件、验签加解密、收发消息 API、主动推送能力与限制坑，并与现有 QQ 通道的结构逐点对照，给出选型结论与实施顺序。
> 依据：QQ 通道现有真实实现（2026-09 读码）+ 各开放平台官方文档核对（2026-09-15）。
> 关联计划：`docs/superpowers/plans/2026-09-16-channel-bots.md`。

---

## 0. 调研范围与方法

- **对照基准**：仓库内已稳定运行的 QQ 官方机器人通道（WebSocket 网关 + HTTP webhook 双收口）。所有新通道复用它的「通道无关」部分，只补「通道相关」部分。
- **调研维度**（每通道一节，统一口径）：前置条件 → 验签与加解密 → 收消息 API → 发消息 API → 主动推送能力 → 限制坑。
- **明确不做**：个人微信协议号（违反微信使用条款，封号风险，法律与稳定性双重不可接受）；飞书/企微的 WebSocket 长连接模式（需官方 SDK，违背「零新依赖」硬约定）。

---

## 1. QQ 通道（基准）——现有实现解剖

QQ 通道已在生产运行，其结构是新通道的模板。以下全部为仓库真实类名/方法名。

### 1.1 收消息：双收口

| 收口 | 位置 | 说明 |
|---|---|---|
| HTTP webhook | `routes/QQBotWebhookRoutes.kt` → `POST /api/qq/bot/webhook` | `op=13` URL 验证（对 `event_ts + plain_token` 做 Ed25519 签名原样回包）；`op=0` 事件分发（`X-Signature-Ed25519` + `X-Signature-Timestamp` 验签 → 立即回 `{"op":12}` ACK → `scope.launch` 异步处理） |
| WebSocket 网关 | `services/QQBotWebSocketClient.kt` | 官方默认收事件模式，无需公网 URL。op 10 Hello → op 2 Identify → op 1 心跳 → op 0 Dispatch（`C2C_MESSAGE_CREATE` / `GROUP_AT_MESSAGE_CREATE`） |

两条收口的事件 payload 形状一致，统一交给 **`services/QQMessageProcessor.kt` 的 `process(eventType, d, ...)`** 处理——这是「通道无关逻辑只写一遍」的关键。

### 1.2 处理管线（`QQMessageProcessor.process` 内部顺序）

1. **内容归一化**：`content` 兼容 string / 段落 JSON 数组两种形态；剥离群 `@` 占位 `<@!id>`；语音走 `attachments[].asr_refer_text`（平台自带转写）兜底。
2. **去重**：`isFirstEvent(eventId)` —— 事件 id 是 140+ 字符而 `webhook_events.event_id` 列只有 VARCHAR(64)，所以先做 **SHA-256 十六进制摘要**再插入，靠**主键冲突**判重（并发投递只有一个 insert 赢），插入成功顺手清理 3 天前的旧记录。
3. **自动开户**：`UserService.findByQqOpenid(openid)` → 无则 `createByQqOpenid(openid)`（openid 即账号，`phone`/`password_hash` 为空，支持「QQ 即账号」零门槛记账）。
4. **指令短路**（必须先于自然语言路由，否则「开启每日推送」会被当成记账/闲聊吞掉）：
   - 推送开关：`PUSH_ON` / `PUSH_OFF` / `PUSH_STATUS` 三个正则（internal，测试 `QQMessageProcessorTest` 直接引用）→ `UserService.setDailyReport(...)`；
   - 登录码：`LOGIN_CODE` 正则 → `QQBotService.generateBindCode(openid)`（内存 map，6 位数字，5 分钟过期）。
5. **自然语言记账**：`PhoneIntentRouter(billService, budgetService, insightService, nluService).route(content, user.id)` —— 纯计算单元，不发消息；路由优先级 帮助→删除→余额→总结/异常→查询→记账→补金额→LLM 兜底；记账经 `nluService.parse` → `BillService.createBill(..., source="QQ")` 落库（`bills.bill_source`）。
6. **回复**：群聊 `qqBotService.sendGroupMessage(groupOpenid, reply, msgId)` / 单聊 `sendC2CMessage(openid, reply, msgId)`。

### 1.3 发消息与鉴权（`services/QQBotService.kt`）

- `getAccessToken()`：向 `bots.qq.com/app/getAppAccessToken` 换 token（注意不是 api 网关域名），Mutex 单飞 + 过期前 300s 刷新。
- `sendC2CMessage` / `sendGroupMessage`：POST `/v2/users/{openid}/messages` 与 `/v2/groups/{groupOpenid}/messages`。
- **msg_id 双语义**（`explicitNulls=false`）：传收到的事件 id = 被动回复；传空串则序列化时省略 `msg_id` 字段 = 主动推送（随机 UUID 会被 QQ 以 40034024 拒收）。
- 配置存 `bot_config` KV 表（`loadFromDb` / `saveToDb`），Web 管理端经 `routes/QQBotManageRoutes.kt`（`/api/qq-bot/{status,config,bind,unbind,bind-status}`）维护。

### 1.4 主动推送（`services/PushScheduler.kt`）

- 进程内协程 `tick()` 循环（30s），**send lambda 注入**：`send: suspend (openid, content, msgId) -> Boolean`——调度与通道发送解耦，这是多通道化的现成接缝。
- 四类推送（MONTHLY_SUMMARY / ANOMALY / HABIT / DAILY_REPORT）共用 `pushIfNeeded` 模板：`push_log(user_id, type, day_key)` 判重 → provider 取内容（null 不发）→ send 成功才落去重。
- 目标用户枚举：`UserService.findAllBoundQq()`（qqOpenid 非空）；`ai_disabled=true` 跳过全部主动推送。

### 1.5 QQ 通道的硬编码点（多通道化的改造对象）

- **身份只有 `qq_openid` 一列**（`UsersTable`），开户/绑定/解绑方法都是 QQ 命名（`findByQqOpenid` / `createByQqOpenid` / `bindByQqOpenid` / `unbindQq` / `findAllBoundQq`）。
- **推送目标写死 QQ**：`findAllBoundQq` + send lambda 直接 `qqBotService.sendC2CMessage`。
- **指令正则内联在 `QQMessageProcessor`**（`LOGIN_CODE` / `PUSH_ON` / `PUSH_OFF` / `PUSH_STATUS`），新通道无法复用。
- 旧版 `/api/qq/webhook`（`routes/QQWebhookRoutes.kt`，共享密钥协议）保留运行，Phase D 标注废弃。

---

## 2. 飞书（Lark）自建应用

### 2.1 前置条件

- 个人免费注册即可创建**团队**，团队内免费创建**企业自建应用**（无需企业认证、无需付费）；开启**机器人**能力；用户/群在应用可用范围内即可收发。
- 需要的凭证：`App ID` / `App Secret`（换 token）、可选 `Encrypt Key`（事件加密）、`Verification Token`（旧版校验字段，配了 Encrypt Key 后以验签为准）。
- 事件订阅选「**将事件发送至开发者服务器**」，填公网 URL（现有 QQ webhook 已走内网穿透/部署路径，可复用同一套部署方式）。长连接模式需要官方 SDK，**不用**。

### 2.2 验签与加解密

- **URL 验证**：配置事件地址时飞书 POST `type=url_verification` 的 JSON（含 `challenge`），服务端**原样返回 challenge** 即可（配了 Encrypt Key 时整个 body 是密文，需先解密再取 challenge）。
- **验签**（配 Encrypt Key 时必做）：`X-Lark-Signature = SHA256(timestamp + nonce + encrypt_key + body)`，请求头 `X-Lark-Request-Timestamp` / `X-Lark-Request-Nonce` 提供前两项；不配 Encrypt Key 时可用 Verification Token 做轻校验。
- **解密**：AES-256-CBC，key = SHA256(EncryptKey)，密文 Base64 解码后**前 16 字节作 IV**，明文 JSON 的事件结构在 `event` 字段内。⚠️ 实施时以官方文档逐字核对常量（B2 阶段风险点）。

### 2.3 收消息 API

- 订阅事件 **`im.message.receive_v1`**：单聊（p2p）与群聊（群内需 @机器人）都在这一事件里；`event.message.chat_type` 区分会话类型，`message_id` 全局唯一（天然去重键），文本内容在 `message.content`（JSON 字符串，如 `{"text":"午餐20元"}`），群消息含 `@_user_1` mention 占位符需剥离。
- 需要的权限：`im:message`（接收/读取单聊、群聊消息）等，在应用后台按需开通。

### 2.4 发消息 API

- `POST /open-apis/im/v1/messages?receive_id_type=open_id`：`receive_id` = 用户 open_id，`msg_type=text`，`content` 是**「JSON 字符串套 JSON」**——`content` 字段本身是字符串化的 JSON（如 `"{\"text\":\"已记录：三餐 ¥20.00\"}"`），这是最容易写错的一点。
- 鉴权：`Authorization: Bearer {tenant_access_token}`；`POST /open-apis/auth/v3/tenant_access_token/internal`（app_id + app_secret）换取，**有效期 2 小时**，需缓存 + 提前刷新（照抄 `QQBotService.getAccessToken` 的 Mutex 单飞模式）。
- 主动回复可传 `receive_id`，也可用 `POST .../messages/{message_id}/reply` 以原消息 id 回复。

### 2.5 主动推送能力

- **无 QQ 式「被动回复窗口」限制**：任意时刻向已打开机器人会话/可用范围内的用户发消息均可（钉钉式 20 条/分钟等常规频控存在，对日报级别的量完全无感）。日报/月结/异常推送**无障碍**。

### 2.6 限制坑

- **语音**：v1 事件只接文本，平台**无语音转写**下发给开发者；语音消息拿到的是音频文件 key，需自备 ASR（可复用现有 Whisper 兜底管线，B2 阶段先只接文本）。
- content 是 JSON 套 JSON、mention 占位符、`open_id` 与 `user_id` 与 `union_id` 三种 id 语义不同（自建应用用 `open_id` 即可）。
- 群聊必须 @机器人才触发事件；应用发布（创建版本并通过管理员审核）后事件才生效，开发期易漏。

---

## 3. 企业微信智能机器人

### 3.1 前置条件

- 需要一个**企业微信**（个人可免费注册企业，无需认证即可开发调试）；管理后台创建「**智能机器人**」并切换 **API 模式**（普通/消息推送模式无法对接自有系统）。
- 凭证：回调 URL 的 `Token` + `EncodingAESKey`（收消息）；主动推送用独立的 **「消息推送」webhook URL**。

### 3.2 验签与加解密

- **URL 验证**：GET 请求携带 `msg_signature / timestamp / nonce / echostr`，`msg_signature = SHA1(sort(token, timestamp, nonce, echostr))`，比对一致后**解密 echostr 原样返回**明文。
- **消息加解密**：微信系标准 AES-256-CBC/PKCS7，key = Base64(EncodingAESKey)，明文 = 随机 16B + 4B msg_len + msg + receiveid 后缀；XML 载体。验签同上 SHA1。
- ⚠️ B 阶段（W0）需手写 `WxCryptUtil`（零依赖），这套常量与飞书不同（SHA1 验签、PKCS7、msg_len 头），实施时逐字对照官方文档。

### 3.3 收消息 API

- 智能机器人 API 模式下，用户 **@机器人（群聊）或单聊**发消息时，企业微信向回调 URL 推送消息（`aibot_msg_callback` 体系）；支持文本/语音/图片等，回调可同步被动回复，也可生成流式消息（流式不接）。
- 身份：消息携带发送者 `userid`（企业内唯一），即开户键。

### 3.4 发消息 API

- **被动回复**：在回调响应里直接返回加密 XML（5 秒内）。
- **主动推送**：向「消息推送」配置页获得的 **webhook URL** POST JSON（`{"msgtype":"text","text":{"content":"..."}}`），无需 token；另有应用消息接口 `message/send`（需 corpid + 应用 secret 换 access_token，走应用可见范围）。日报推送用 webhook URL 最简。

### 3.5 主动推送能力

- **能收能发能推**——三通道里唯一在微信体系内三者齐备的路线。webhook 主动推无 48 小时窗口类限制（群机器人 webhook 有 20 条/分钟频控，足够）。

### 3.6 限制坑

- 交互发生在**企业微信 App 内**：用户必须装企业微信并加入你的企业，C 端日常记账体验不如 QQ/飞书个人场景自然。
- 单聊主动触达边界（机器人能否未经用户发起会话主动单发）官方文档口径需在 W1 实施时核对；保险策略是日报推送走「消息推送」webhook 到群，或仅对单聊有过交互的用户推。
- XML 进出、双重加解密，实现复杂度三通道最高（但 W0 工具类写完后可被订阅号复用）。

---

## 4. 个人微信订阅号

### 4.1 前置条件

- 个人主体可免费注册**订阅号**（未认证）；后台「基本配置」填服务器 URL + Token（+ EncodingAESKey 明文模式可不用）。

### 4.2 验签与加解密

- **接入验证**：GET `signature / timestamp / nonce / echostr`，`signature = SHA1(字典序排序(token, timestamp, nonce) 拼接)`，一致则**原样返回 echostr**。
- 明文模式下后续消息不加密（XML 明文 + 每条带签名可校验）；安全模式才用 EncodingAESKey 加解密（与企微同套 WxCryptUtil）。**选明文模式**降低复杂度。

### 4.3 收消息 API

- 微信服务器 POST XML：文本（`MsgType=text`，`Content`）、**语音（`MsgType=voice`，开启「接收语音识别结果」权限后自带 `Recognition` 转写文本，免费）**——这是唯一免费拿平台语音转写的通道。

### 4.4 发消息 API

- **只有被动回复**：对收到的消息在 HTTP 响应体内直接返回 XML（5 秒内）。
- 客服接口（48h 窗口主动发）、模板消息、订阅通知**全部需要认证服务号**；个人订阅号无法认证 → **无任何主动推送能力**。

### 4.5 主动推送能力

- **没有**。日报/月结/异常推送全部不可用（客服消息接口未认证订阅号不可调用）。

### 4.6 限制坑

- **5 秒硬窗口**：微信服务器 5 秒收不到响应断连重试（共 3 次），超时用户看到「该公众号暂时无法提供服务」。对策：无响应把握时回空串（微信不处理）；LLM 兜底 4s 硬超时策略（W2 阶段）。
- 被动回复不能主动补发——丢了就丢了；没有重试语义。
- 与既有用户体系的连接：`FromUserName` 即 openid，可自动开户（同 QQ 模式），但**登录码等主动指令可回、推送类指令只能回「订阅号不支持推送」**。

---

## 5. 与 QQ 结构对照表

> 「QQ 现状」列全部为仓库真实类名/方法名；新通道按此表逐行落位。

| 结构点 | QQ 现状 | 飞书落位（B2） | 企业微信落位（W1） | 订阅号落位（W2） |
|---|---|---|---|---|
| webhook 收口 | `routes/QQBotWebhookRoutes.kt`（`POST /api/qq/bot/webhook`，op13 验证 / op0 事件，立即 ACK 异步处理） | `routes/FeishuBotWebhookRoutes.kt`：`POST /api/feishu/bot/webhook`，`url_verification` 回 challenge → 验签/解密 → 立即 200 → 异步处理 | `routes/WecomBotWebhookRoutes.kt`：GET echostr 验证 + POST 加密 XML（**同步被动回复**，不能异步 ACK） | `routes/MpWebhookRoutes.kt`：GET echostr 验证 + POST 明文 XML（**同步被动回复**，5s 硬窗口） |
| WS 网关 | `services/QQBotWebSocketClient.kt`（长连接，与 webhook 共享 processor） | 不做（需 SDK） | 不做（有长连接模式但需 SDK） | 无此模式 |
| 事件去重 | `QQMessageProcessor.isFirstEvent()`：SHA-256 摘要 → `webhook_events` 主键冲突判重，3 天清理 | 复用同一 `isFirstEvent`（`message_id` 作 eventKey） | 复用（MsgId/事件拼接键） | 复用（MsgId） |
| 内容归一化 | `QQMessageProcessor.process`：string/数组兼容、`<@!id>` 剥离、`asr_refer_text` 语音兜底 | 新 processor：mention 占位符 `@_user_1` 剥离、`content` JSON 字符串解析 | 新 processor：XML → 字段提取、`Recognition` 语音兜底 | 新 processor：XML → 字段提取、`Recognition` 语音兜底 |
| 开户 | `UserService.findByQqOpenid` / `createByQqOpenid`（openid 即账号） | `findByFeishuOpenId` / `createByFeishuOpenId`（open_id 即账号） | `findByWecomUserid` / `createByWecomUserid` | `findByWechatOpenid` / `createByWechatOpenid` |
| 指令短路 | `QQMessageProcessor` 内联 `LOGIN_CODE`/`PUSH_*` 正则 → `setDailyReport` / `generateBindCode` | 同一 `BotCommands` 常量，同套短路顺序（推送开关 → 登录码 → 记账） | 同左（推送开关回「不支持主动推送」由通道能力决定） | 同左 |
| 意图路由 | `PhoneIntentRouter.route(content, userId)`，source 默认 `"QQ"` → `BillService.createBill(source)` | `route(..., "FEISHU")` | `route(..., "WECOM")` | `route(..., "MP")` |
| 发消息 | `QQBotService.sendC2CMessage` / `sendGroupMessage`（msg_id 空串=主动推） | `FeishuBotService.sendText(openId, content, replyToMsgId?)`（tenant_access_token 2h 缓存，content JSON 套 JSON） | 企微：回调内被动回复 + 消息推送 webhook URL 主动推 | 被动回复 XML（仅此一种） |
| 绑定码 | `QQBotService.generateBindCode`/`consumeBindCode`（内存 map 5 分钟）+ `QQBotManageRoutes`（`/api/qq-bot/{bind,bind-status,unbind}`）+ `UserService.bindByQqOpenid` | `FeishuBotService` 同构 bindCodes + `FeishuBotManageRoutes`（`/api/feishu-bot/...`）+ `bindFeishuByOpenId` | `WecomBotService` 同构 + `/api/wecom-bot/...` + `bindWecomByUserid` | 订阅号只收不推，绑定走被动回复发码（可选） |
| 配置管理 | `bot_config` KV（`QQBotService.loadFromDb/saveToDb`）+ Web 管理页 | `feishu_app_id/feishu_app_secret/feishu_encrypt_key/feishu_verification_token` 四 key 同表 | `wecom_token/wecom_encoding_aes_key/wecom_push_webhook_url` | `mp_token`（明文模式仅此一 key） |
| 推送调度 | `PushScheduler`：send lambda `(openid, content, msgId)`；`findAllBoundQq` 枚举；`push_log(user,type,day)` 去重 | send lambda 升维为 `(channel, targetId, content, msgId)`；目标按 **飞书 > 企微 > QQ** 取第一个已绑定通道（B1 已落） | 同左 | 不参与推送 |

---

## 6. 选型矩阵

| 维度 | QQ 官方机器人（现有） | 飞书自建应用 | 企业微信智能机器人 | 个人订阅号 |
|---|---|---|---|---|
| 注册门槛 | 个人可建（机器人需平台审核） | **最低**（个人免费建团队） | 低（个人可建企业） | 低（个人可注册，无法认证） |
| 收消息 | ✅ WS + webhook 双收口 | ✅ webhook（challenge + AES） | ✅ 回调（SHA1 + AES-256-CBC/PKCS7 + XML） | ✅ 回调（SHA1 + 明文 XML） |
| 发/回复消息 | ✅ | ✅ REST，JSON 套 JSON | ✅ 被动回复 XML + webhook 推 | ✅ 仅被动回复 XML |
| **主动推送（日报）** | ✅ | ✅ **无窗口限制** | ✅（webhook URL） | ❌ **完全没有** |
| 语音转写 | ✅ 平台 `asr_refer_text` | ❌ 需自备 ASR | 平台侧待核（W1 核对） | ✅ **免费 `Recognition`** |
| 实现复杂度 | 已有 | **低**（JSON 全链路） | **高**（XML + 双重加解密） | 中（XML，但可复用 W0 工具） |
| 合规风险 | 无 | 无 | 无 | 无（协议号除外，已排除） |
| 用户体验场景 | QQ 单聊/群 | 飞书个人/团队 | 企业微信 App 内 | 微信内最普及 |

**结论**：
1. **飞书全量接入**（收 + 发 + 主动推送全能力，实现成本最低）——主线第一个新通道。
2. **微信主线走企业微信智能机器人**（微信体系内唯一「能收能发能推」）。
3. **订阅号作为可选支线**（只收不推，胜在免费语音转写与微信内触达广度；日报类推送静默降级为被动回复提示）。
4. 个人微信协议号不做。

---

## 7. 实施顺序（与总计划 Phase 对齐）

| 阶段 | 内容 | 状态 |
|---|---|---|
| **A** | 本调研文档 | ✅ 本文 |
| **B1** | 通道底座（QQ 行为零变化）：`UsersTable` 三通道身份列 / `BotCommands` 共享常量 / `PhoneIntentRouter` source 参数 / `UserService` 每通道三件套 + `findAllPushUsers` / `PushScheduler` 分通道 send + 目标通道选择（飞书>企微>QQ）/ `Application.kt` 分通道 send 分发 | ✅ 同批完成 |
| **B2** | 飞书：`FeishuBotService` / `FeishuMessageProcessor` / `FeishuBotWebhookRoutes` / `FeishuBotManageRoutes` + 测试 | ✅ 已完成 |
| **C** | 微信：W0 `WxCryptUtil` → W1 企业微信主线 → W2 订阅号可选 | ✅ 已完成 |
| **D** | QQ 遗留收敛（App/Web 绑定迁官方流程，旧 webhook 保留运行标注废弃） | ✅ 已完成 |
| **E** | Web 控制台多通道配置卡片（`web/src/api/bots.ts` + `BotChannelsSection.vue`） | ✅ 已完成 |
| **F** | 测试全绿 + 文档收尾（冒烟需线上环境，见第 8 节部署冒烟清单） | ✅ 代码侧完成，真机冒烟待线上执行 |

**风险备忘**（转交后续阶段）：飞书加密常量（SHA256(EncryptKey) 作 key、密文前 16 字节作 IV）与企微回调细节（SHA1 验签、PKCS7、msg_len 头）实施时必须逐字对照官方文档，不得凭记忆写；订阅号 LLM 兜底需 4s 硬超时（5s 窗口预留 1s 组包）。

---

## 参考

- 飞书：[接收事件/Encrypt Key 加密](https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscription-configure-/encrypt-key-encryption-configuration-case?lang=zh-CN) · [challenge 校验](https://open.feishu.cn/document/server-side-sdk/nodejs-sdk/handling-events?lang=zh-CN) · [发送消息 im/v1/message/create](https://open.feishu.cn/document/server-docs/im-v1/message/create) · [消息 content 结构](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/im-v1/message/create_json) · [获取 tenant_access_token](https://open.feishu.cn/document/server-docs/api-call-guide/calling-process/get-access-token)
- 企业微信：[智能机器人概述](https://developer.work.weixin.qq.com/document/path/101039) · [接收消息](https://developer.work.weixin.qq.com/document/path/100719) · [消息推送（webhook）配置](https://developer.work.weixin.qq.com/document/path/91770) · [应用推送消息/加解密](https://developer.work.weixin.qq.com/document/path/90248)
- 微信公众号：[接入指南（echostr/SHA1）](https://developers.weixin.qq.com/doc/subscription/guide/product/message/Passive_user_reply_message.html) · [发送客服消息（认证限制）](https://developers.weixin.qq.com/doc/subscription/api/customer/message/api_sendcustommessage.html) · [接收语音消息（Recognition）](https://developers.weixin.qq.com/doc/subscription/guide/product/message/Receiving_standard_messages.html)
- 仓库内基准实现：`server/src/main/kotlin/com/example/rinklnote/server/` 下 `services/QQMessageProcessor.kt`、`services/QQBotService.kt`、`services/QQBotWebSocketClient.kt`、`services/PushScheduler.kt`、`services/UserService.kt`、`services/PhoneIntentRouter.kt`、`routes/QQBotWebhookRoutes.kt`、`routes/QQBotManageRoutes.kt`

---

## 8. 部署冒烟清单（Phase F 收尾时补，待线上执行）

> 真实飞书 / 企微后台的回调配置需要线上环境与对应平台账号，**代码侧已就绪但未真机验证**。以下步骤在服务器（`118.31.184.221`）部署后按序执行。

### 前置

- [ ] 按现有部署流程打包部署最新服务端（`./gradlew :server:installDist` 或 jar），重启后确认日志无启动报错、8080 端口监听正常
- [ ] Web 控制台「设置 → 多通道机器人」填入对应通道凭证并保存（配置存 `bot_config` 表，非环境变量）

### 飞书（主线，优先冒烟）

- [ ] 飞书开放平台 → 自建应用 → 事件与回调 → 请求地址配置填 `http://118.31.184.221/api/feishu/bot/webhook`
  - ⚠️ 安全修复（217530c）后的硬顺序：**必须先在 Web 管理卡保存飞书凭证（verification_token 必填，Encrypt Key 可选），再去飞书后台配回调**——双未配状态下服务端对回调一律拒绝（503「机器人未完成安全配置」），飞书后台会保存失败；只配 token 未配 Encrypt Key 时，请求也必须带平台下发的 token 才放行
  - 平台会先发 `url_verification`：服务端回 challenge 即通过（若配了 Encrypt Key，先在 Web 卡片填入再配回调）
- [ ] 订阅事件 `im.message.receive_v1`
- [ ] 飞书 App 内向机器人发「午餐20元」→ App/Web 账单列表出现该笔，`source = FEISHU`
- [ ] 发「登录」→ 收到 6 位绑定码 → Web「多通道机器人 · 飞书」页签提交 → 显示已绑定
- [ ] 发「查询今天花了多少」「开启每日推送」→ 回复正常且推送开关落库
- [ ] 等日报推送时刻（或临时触发）→ 飞书收到日报（推送通道优先级 飞书 > 企微 > QQ）

### 企业微信智能机器人

- [ ] 企微管理端 → 机器人回调 URL 填 `http://118.31.184.221/api/wecom/bot/webhook`（GET echostr 验证需先通过）
- [ ] Token / EncodingAESKey 与 Web 卡片所填一致
- [ ] 企微 App 内发「午餐20元」→ 落库 `source = WECOM`；发「登录」走绑定码流程
- [ ] 在 Web 卡片填「消息推送」webhook URL → 日报能推到企微群/会话

### 微信订阅号（可选支线）

- [ ] 公众平台 → 基本配置 → 服务器配置 URL `http://118.31.184.221/api/mp/bot/webhook`，Token 与 Web 卡片一致（订阅号暂无 Web 管理卡片时直接写 `bot_config` 表 `mp_token` / `mp_encoding_aes_key`）
- [ ] 微信内发「午餐20元」→ 5 秒内收到被动回复且落库 `source = MP`；发语音 → Recognition 转写后记账
- [ ] 确认订阅号**不参与**日报主动推送（无推送能力，属预期）

### 回归线

- [ ] QQ 通道收发记账 / 日报推送不回归；旧 `/api/qq/webhook` 仍可访问（废弃保留）
- [ ] 三通道 `/api/{qq,feishu,wecom}-bot/status` 掩码回显正常，Web 卡片三页签切换正常
