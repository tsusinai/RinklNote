# Vibe Coding 工作流沉淀（Claude Code × RinklNote）

> 课程：AI 产品开发 · 阶段二 · AI 工具（vibe coding）工作流
> 本文档沉淀本项目实际使用的 AI 协作工作流，可直接作为「熟悉 vibe coding 工具与 workflow」的交付物。

---

## 一、总览：一条从想法到真机可用的流水线

```
Brainstorming（澄清想法→写设计文档）
   → Writing Plans（拆任务→写实施计划）
   → Subagent-Driven Development（逐任务实现 + 双层 review）
   → Systematic Debugging（出 bug 先找根因，不瞎改）
   → TDD（测试先行）
   → 显式路径提交（git 卫生）
   → 真机验证（连设备跑起来看）
```

每一环都不是"可选的技巧"，而是这个项目每个功能实际走过的路。

---

## 二、各环节与用到的 Skills

| 环节 | Skill | 产出的真实例子 |
|---|---|---|
| 澄清想法 | `superpowers:brainstorming` | `docs/superpowers/specs/2026-08-12-ai-voice-design.md`、`2026-08-12-ux-logic-consistency-design.md` |
| 拆解计划 | `superpowers:writing-plans` | `docs/superpowers/plans/2026-08-12-ai-voice-feature.md`、`2026-08-01-quickadd-efficiency.md` |
| 逐任务实现 | `superpowers:subagent-driven-development` / `executing-plans` | AI 语音记账（38 App + 31 Server 测试全绿） |
| 找根因 | `superpowers:systematic-debugging` | 二级分类弹层"动画不生效"的根因定位（见下） |
| 测试先行 | `superpowers:test-driven-development` | `server/.../BillServiceTest.kt`（分页游标/聚合/seed 幂等） |
| 代码审查 | `superpowers:requesting-code-review` | 每个任务的 spec 合规 + 质量双层 review |
| 收尾 | `superpowers:verification-before-completion`、`finishing-a-development-branch` | 改完必须复跑测试 + 真机确认才算完 |

---

## 三、核心原则（项目实证）

### 3.1 先想清楚再让 AI 动手
一个需求进来，**先用 brainstorming 对话把"为什么做、验收标准是什么"敲定**，写成设计文档，才进入实现。RinklNote 每个功能都有对应 spec + plan，AI 不是"代写代码"，是在你心里有方案后帮你加速。

### 3.2 计划要细到"直接照做"
writing-plans 要求每个任务给出精确文件路径、完整代码、测试命令与预期输出，不含 "TBD"。这样实现者（无论 AI 子代理还是人）不需要猜测，review 也有明确依据。

### 3.3 出 bug 先找根因，不瞎改（systematic-debugging）
本项目真实案例——快捷记账抽屉"长按呼出二级分类弹层"动画不生效：

- 症状：`AnimatedVisibility` 包在 `if` 里，进入组合时 `visible` 已为 true → **不触发 enter 动画**。
- 根因：不是动画参数错，而是"组合时机"错了——`if` 守卫让节点"入场即可见"，false→true 的翻转根本没发生。
- 修复：把 `AnimatedVisibility` 改为始终组合，用 `expandedParentId == category.id` 状态翻转驱动动画。
- commit：`009203c`。

结论：先读代码、复现、定位"在哪一层断掉"，再动手。盲目改参数会越改越乱。

### 3.4 测试先行，改动要有回归锚
核心数据逻辑（服务端账单同步、月度聚合、分类 seed）都有 JVM 测试兜底。改一处，跑一遍，防止"改好 A 弄坏 B"。

### 3.5 显式路径提交，保持 git 干净
提交时**按路径显式暂存源码**，不用 `git add -A`，避免 `server/build/` 编译产物污染提交。提交信息用一句中文，突出"为什么"。

### 3.6 真机验证是最后一关
单元测试过了 ≠ 功能能用。每个 UI 改动最终都在 Redmi 真机（HyperOS 3.0）上手工走一遍主流程，截图留存。

---

## 四、常用命令速查

```bash
# 测试（GRADLE_USER_HOME 移到 D:\gradle 规避路径引号问题）
export GRADLE_USER_HOME=D:/gradle
./gradlew :app:testDebugUnitTest :server:test

# 构建/安装
./gradlew assembleDebug
./gradlew installDebug

# 真机截图（设备 89a5f9e1）
adb exec-out screencap -p > shot.png
```

---

## 五、用这套工作流的收益（本项目的数字）

- 全程 TDD + 双层 review：App 38 / Server 31 个测试全绿，无回归。
- systematic-debugging 把"动画不生效"这类玄学 bug 从数小时试错压到一次根因定位。
- 每个功能都有 spec + plan + commit + 测试四件套，复盘（本文档所在的阶段二交付）直接可引用。
