# 阶段二 · 核心改动实现复盘 —— AI 语音记账（用 AI 协作完成）

> 课程：AI 产品开发 · 阶段二 · 核心改动
> 改动的改进方案背景见 `docs/阶段二-改进方案-AI记账助手.md`。
> 本文件完整复盘一次"**用 AI 协作完成一个真实功能改动**"的流程：需求 → 计划 → 实现 → 测试 → 真机验证，附 commit、代码路径、测试结果。

---

## 1. 需求描述

**一句话**：在 AI 助手页支持语音记账——用户说一句"午餐 28 元"，语音转文字 → NLP 解析金额/分类 → 聊天内确认 → 直接落账；说"这个月花了多少"则走问账回答。

**验收标准**（写进设计文档）：

1. AI 页为聊天式界面（气泡 + 输入栏），支持文本与语音两条输入路径。
2. 语音/文本命中记账意图 → 解析出金额+分类 → **聊天内一条确认消息**直接落账，不打断主流程。
3. 命中问账意图 → 返回月度/异常/自定义查询的回答。
4. 记账/问账由同一入口**自动分流**，用户不需要选模式。
5. 聊天历史持久化（重启不丢）。

---

## 2. 计划（brainstorming → 设计文档 → 实施计划）

| 产物 | 文件 | 说明 |
|---|---|---|
| 设计文档 | `docs/superpowers/specs/2026-08-12-ai-voice-design.md` | commit `9c01b75`：架构、数据流、去重位设计 |
| 实施计划 | `docs/superpowers/plans/2026-08-12-ai-voice-feature.md` | commit `7ab32ca`：拆成可独立验收的任务 |

计划里把改动切成数据层（insights DTO + ApiService）→ ViewModel（AiViewModel 聊天式重写）→ UI（AiScreen 气泡）→ 语音（VoiceInputBar 复用）→ 持久化（ChatMessage）五段，每段一个独立测试周期。

---

## 3. 实现（按 commit 序列）

| 阶段 | commit | 改动 | 关键文件 |
|---|---|---|---|
| 数据层 | `e1f0b36` | 新增 insights 查询 DTO + ApiService 方法（问账/月总结/异常的数据来源） | `app/.../data/network/ApiService.kt` |
| 服务端 | `6a9c433`/`5ca3953` | `/api/insights/{monthly,anomaly,query,suggest}` 路由 + NLU 服务 | `server/.../routes/InsightRoutes.kt`、`server/.../services/nlu/`、`services/insight/InsightService.kt` |
| ViewModel | `3a3d22e` → `46d848a` | AiViewModel 从"综合助手"重写为**聊天式**：记账路由（NLU 判定意图）+ 问账 + **去重位注入** | `app/.../ui/viewmodel/AiViewModel.kt` |
| UI | `a9791d8` | AI 界面改为聊天式（气泡 + 输入栏），记账确认走对话内消息 | `app/.../ui/screen/ai/AiScreen.kt` |
| 持久化 | `e4a0b14` | ChatMessage 表 + Room MIGRATION_8_9 + Repository 方法 | `app/.../data/db/entity/ChatMessage.kt` |
| 语音 | `405129a` | AI 页复用底部语音条，结果走聊天路由；输入框胶囊圆角 | `app/.../ui/component/VoiceInputBar.kt` |
| 去重/防泄漏 | `25eee2d` | AI 总结/异常失败不占用去重位；离开 AI 页丢弃在途记账标记 | `app/.../ui/viewmodel/AiViewModel.kt` |
| 编排 | `2c84fda`/`38a1013` | AI 页接入 pager 第 5 页；我的/AI 页隐藏底部导航 | `app/.../navigation/AppNavigation.kt` |

**本地兜底解析**：断网/未登录时，`VoiceParser`（`app/.../util/VoiceInputUtil.kt`，正则抽金额 + 关键词匹配分类）保证"午餐 28 元"离线也能落账。

---

## 4. 测试

**命令**：

```bash
export GRADLE_USER_HOME=D:/gradle
./gradlew :app:testDebugUnitTest :server:test
```

**结果**（本次实跑）：

| 模块 | 用例数 | 失败 |
|---|---|---|
| App 单元测试 | 38 | 0 |
| Server 测试（含账单分页游标、月度聚合、NLU、seed 幂等回归） | 31 | 0 |

服务端回归测试覆盖 `server/.../services/BillServiceTest.kt`（同步分页复合游标、软删除墓碑、月度 SQL 聚合、二级分类幂等补全）。

---

## 5. 真机验证（Redmi · HyperOS 3.0）

1. 首页 → AI 图标进入 AI 页，底部语音条输入"午餐 28 元"。
2. 观察：语音 → 文字 → 聊天内出现记账确认消息"已记账：28 元（三餐）"；回记账页账单列表多出该笔。
3. 再输入"这个月花了多少" → 返回月度支出/收入统计。
4. 输入一句话记账 → 记账页同步刷新，无重复账单（去重位生效）。
5. 杀掉 App 重进 → 聊天历史仍在（Room 持久化）。

> 真机截图见 `docs/阶段二-原型/05-AI页.png`。

---

## 6. AI 协作复盘（课程考核点）

| 协作动作 | 实际发生 |
|---|---|
| **如何描述需求** | 不直接给"写个语音记账"，而是先讲清楚痛点（记账摩擦高）+ 验收标准（5 条），让 AI 在方案层面对齐 |
| **如何 review** | 每个任务完成后检查 spec 合规与代码质量；`25eee2d` 就是在 review 阶段发现"总结失败也占用去重位"的边界 bug 后补的回归 |
| **卡住时如何介入** | 语音条复用 vs 新建的取舍、去重位放 App 端还是服务端，这两个点 AI 主动提出 2-3 个方案并给出 trade-off，由我拍板后再实施 |
| **真实教训沉淀** | 同步重复账单（推未落库行 → 全量同步重复 POST）在本次 AI 协作中再次被规避：`QuickAddViewModel.finalConfirm` 先落库拿真实 id 再推送，见改进方案 §4.3 |
