# 计划：飞书/微信 bot 接入 + QQ 通道升级（机制调研 + 双端实施）

> 来源：用户桌面 `plan3.md`（2026-09-16 批准）。分支 `feat/channel-bots`（基于 main @ daebe1a，独立 worktree `D:/Codes/RinklNote-channelbots`）。Phase 划分即实施顺序；跨文件契约以 QQ 现有实现为模板。

## 机制探索核心结论

**QQ 通道（基准）**：WS 网关 + HTTP webhook（Ed25519）双收口 → `QQMessageProcessor`（SHA-256 去重/自动开户/指令短路）→ `PhoneIntentRouter`（纯计算）→ NLU → `BillService` 落库 → REST 回复。通道无关部分齐全（`PushScheduler` lambda 注入、`bot_config` KV、`webhook_events` 去重）；硬编码在身份列与 QQ 命名处理器。

**飞书（最顺，先做）**：自建应用（个人免费建团队），事件订阅走 webhook 回调（结构对齐现有 QQ webhook；长连接需 SDK，不用）。收 `im.message.receive_v1`，url_verification 回 challenge，配 Encrypt Key 时 AES-256-CBC 解密 + X-Lark-Signature 验签；发 `POST /open-apis/im/v1/messages`（tenant_access_token 2h 缓存）；**推送无窗口限制，日报无障碍**；语音无平台转写（v1 只接文本）。

**微信（两条路线都写透）**：个人订阅号 = echostr 验证 + XML + 5 秒被动回复 + 语音 Recognition 免费转写，**但无任何主动推送能力**（客服/模板/订阅通知均需认证服务号）；企业微信智能机器人 = 回调收消息 + 「消息推送」webhook 主动推，**能收能发能推**但交互在企业微信 App 内。协议号违反条款不做。

**选型**：飞书全量；微信主线企业微信智能机器人，订阅号可选支线（只收不推）。

## 执行顺序

1. **Phase A**：`docs/bot渠道机制调研与选型.md`——每通道：前置条件/验签加解密/收发 API/推送能力/限制坑/与 QQ 结构对照 + 选型矩阵
2. **Phase B**：通道底座改造 + 飞书接入
3. **Phase C**：微信实施（W0 底座 → W1 企业微信主线 → W2 订阅号可选）
4. **Phase D**：QQ 遗留通道收敛（App/Web 绑定迁移官方流程）
5. **Phase E**：Web 控制台配置卡片
6. **Phase F**：测试 + 冒烟 + 文档收尾

### Phase B 通道底座 + 飞书

**B1 共享底座（QQ 行为零变化）**
- `UsersTable`：加 `feishu_open_id` / `wechat_openid` / `wecom_userid` 三列一次加齐（nullable + uniqueIndex，createMissingTablesAndColumns 自动加列）
- `BotCommands` 共享常量对象：从 `QQMessageProcessor` 提取推送开关/登录码正则，QQ 改引用同一常量
- `PhoneIntentRouter.route()` 加带默认值 `source` 参数（默认 "QQ" 行为不变；飞书 "FEISHU"、微信 "WECOM"/"MP"）
- `UserService`：每通道 `findBy*/createBy*/bindBy*` 照 QQ 三件套 + `findAllPushUsers`（任一推送通道已绑定）
- `PushScheduler`：`send` lambda 加通道维度 `(channel, targetId, content, msgId)`；目标通道按 飞书 > 企业微信 > QQ 取第一个已绑定（保持 user+type+day 单次推送语义）；`PushSchedulerTest` 更新
- `Application.kt` 组装分通道 send 分发

**B2 飞书**
- `services/FeishuBotService.kt`（新）：bot_config keys `feishu_app_id/feishu_app_secret/feishu_encrypt_key(可空)/feishu_verification_token`（loadFromDb/saveToDb 照 QQ）；token 缓存 Mutex 单飞；`sendText(openId, content, replyToMsgId?)`（注意 content 是「JSON 字符串套 JSON」）；绑定码照 QQ 内存 map 5 分钟
- `services/FeishuMessageProcessor.kt`（新）：p2p/群@归一化、mention 占位符剥离、去重复用 `isFirstEvent`、指令短路走 `BotCommands`、进 `PhoneIntentRouter`
- `routes/FeishuBotWebhookRoutes.kt`（新）：`POST /api/feishu/bot/webhook` 照 `QQBotWebhookRoutes` 模板（challenge → 验签/解密 → 立即 200 → 异步处理）
- `routes/FeishuBotManageRoutes.kt`（新）：`/api/feishu-bot/{status,config,bind,unbind,bind-status}` 照 `QQBotManageRoutes`
- 测试：`FeishuBotServiceTest`、`FeishuMessageProcessorTest`

### Phase C 微信

- **W0**：`services/wx/WxCryptUtil.kt`（新）——微信系 SHA1 验签 + AES-256-CBC/PKCS7 加解密 + 微型 XML 解析组装（手写零依赖）
- **W1 企业微信智能机器人（主线）**：`routes/WecomBotWebhookRoutes.kt`（GET echostr 验证 + POST 加密 XML + 同步被动回复）；bot_config `wecom_token/wecom_encoding_aes_key/wecom_push_webhook_url`；单聊主动触达边界对照官方文档核对
- **W2 订阅号（可选）**：`routes/MpWebhookRoutes.kt`——5 秒同步被动回复、voice Recognition 进管线；LLM 兜底 4s 硬超时策略；不参与推送
- 测试：`WxCryptUtilTest`、`WecomMessageProcessorTest`、`MpMessageProcessorTest`

### Phase D QQ 遗留收敛（App/Web 绑定迁移官方流程）

- **App**：`BindQQScreen`（输 QQ 号）改造为「机器人绑定码」输入（通道选择 QQ/飞书/微信 + 6 位码 → `/api/qq-bot/bind` 等）；`ProfileScreen/ProfileCards/ProfileDialogs/AuthViewModel` 的绑定/解绑切到 `/api/qq-bot/{bind-status,unbind}`；`ApiService.bindQQ/unbindQQ`、`DTOs.BindQQRequest`、`TokenManager` 旧 qqNumber 键下线（绑定状态以服务端为准）
- **Web**：`Me.vue`「解绑QQ号」与 `auth.ts` bindQq/unbindQq 迁到官方流程（引导至 `QqBotSection` 已有管理区）
- **服务端**：`AuthRoutes` 删 `/bind-qq`、`/unbind-qq`（两端 caller 迁移后即无调用方）；`users.qq_number` 列保留只读；旧 `/api/qq/webhook`（共享密钥协议）**保留运行**，代码注释 + README 标注废弃（无法确认线上无外部旧客户端，宁留勿删）
- `application.conf` 死配置 `qqbot` 块删除；README 补官方 webhook 路径

### Phase E Web 控制台

- `QqBotSection.vue` 模式扩展「飞书 / 微信」配置卡片；`web/src/api/qqBot.ts` 泛化为 bot 配置 API

### Phase F 验证与收尾

- `./gradlew :server:test` 全绿（QQ 现有 `QQBotServiceTest/QQMessageProcessorTest/PushSchedulerTest` 为回归线）；App `assembleDebug` + 现有单测全绿
- 冒烟：`:server:run` + 内网穿透（或部署 jmbot）→ 飞书后台配回调 → 验证 url_verification、「午餐20元」落库 source=FEISHU、日报推送；微信按选定路线（留用户后台配置，代码侧备好）
- README「API 端点/数据表/环境变量」、AGENTS.md 服务端段落、FEATURES.md 收尾

## 硬性约定

金额整数分（走 `BillService.createBill`）、全中文注释、新逻辑补单测、不引飞书/微信 SDK（手写 HTTP+crypto 零新依赖）、不并发跑同一工作树的 Gradle。

## 风险与不做

- 飞书加密常量（SHA256(EncryptKey) 作 key、密文前 16 字节作 IV 等）与企微回调细节实施时对照官方文档核对
- 不抽 `BotChannel` 统一抽象（QQ 处理器保持现状，仅常量/参数级触碰）；不做长连接模式；不做个人微信协议号
- 旧 webhook 路由保留运行防外部依赖断裂；App 本地旧 qqNumber 缓存只清理不迁移
