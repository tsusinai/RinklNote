# RinklNote 答辩版 PPT 重排实施计划

> **For Claude:** Use `${SUPERPOWERS_SKILLS_ROOT}/skills/collaboration/executing-plans/SKILL.md` to implement this plan task-by-task.

**Goal:** 将已验收的 11 页通用汇报 PPT 重排为 12 页「总-分-难-证」四幕答辩结构（10 分钟节奏），新增三大难点深挖页、真机截图占位页与现场演示引导页。

**Architecture:** 全部改动集中在 `D:\Codes\RinklNote\.zcode\pptx-build\build.js`（pptxgenjs 脚本，每页一个代码块）。构建后跑 `postprocess.py`（字体/pPr 兜底），用 PowerPoint COM 导出 PNG 验收。成品复制到仓库根 `RinklNote-项目汇报-2026-09.pptx`（覆盖）。不涉及任何源码与分支改动，无需 worktree。

**Tech Stack:** pptxgenjs（本地装于 `.zcode/pptx-build/node_modules`）、sharp（图片圆角，已装）、Python 3.14（后处理）、PowerShell PowerPoint COM（渲染）、judge subagent（视觉验收）。

**设计令牌（勿改）：** 画布 W=13.33/H=7.5/M=0.65；NAVY=0A2E4E、INK=0F3050、BODY=3D5A75、MUTED=8299AC、PRIMARY=2E86C9、BRAND=7EC1FC、TINT=EAF4FC、LINE=D7E6F2、ACCENT=CA3032、ACCENT_TINT=FDEEEE；字体统一 `Microsoft YaHei`（常量 F）；标题 29pt/正文 13-15pt/最小 12pt。

**验证循环（每个任务通用）：**

```bash
cd /d/Codes/RinklNote/.zcode/pptx-build
node build.js && python postprocess.py
# 渲染（COM 全量导出，快）：
powershell -NoProfile -Command "$app = New-Object -ComObject PowerPoint.Application; $pres = $app.Presentations.Open('D:\Codes\RinklNote\.zcode\pptx-build\rinklnote-report.pptx', -1, 0, 0); $pres.Export('D:\Codes\RinklNote\.zcode\pptx-build\render', 'PNG', 1600, 900); $pres.Close(); $app.Quit()"
```

期望：构建输出 `OK rinklnote-report.pptx`；render 目录 12 张 PNG；Read 新改页 PNG 自查（无溢出/无重叠/行首无标点）。最后统一跑 judge。

---

### Task 1: 全局脚手架调整（页数与页脚）

**Files:** Modify `.zcode/pptx-build/build.js`

1. `footer()` 中 `String(idx).padStart(2,"0")} / 11` 改为 `/ 12`。
2. S1 封面底部汇报日期行下加一行演讲属性说明？——不加，保持封面不动。
3. 验证：构建通过（此时仍是 11 页，后续任务补齐第 12 页）。

### Task 2: 难点① 整数分三端迁移页（新写，插入为 P5）

**Files:** Modify `build.js`（在 S4 核心链路代码块之后插入）

**内容规格（问题→方案→验证 三段式）：**
- header: `header(s, "难点 ①", "金额不漂移：Double 到整数分的三端迁移")`
- 问题带（y≈1.6, h0.9, ACCENT_TINT 底）：左粗体红色「问题的样子」+ 等宽感示例 `0.1 + 0.2 = 0.30000000000000004`（19pt INK bold）+ 小字「Double 金额的浮点漂移与符号判定误差，账目对不上就是事故」
- 方案区（y≈2.75, 三列卡片 TINT, 各 w3.9 h1.9）：
  1. 「三端统一 Long 分」App amountMinor · Server amount_minor BIGINT · Web amountMinor
  2. 「Room v13 重建四表」REAL→INTEGER；**顺序坑**：bills 外键引用 accounts，必须先搬 bills 再重建 accounts
  3. 「API 兼容双字段」旧 `amount` 字段保留，且必须 `@EncodeDefault(ALWAYS)`，否则恒等于默认值时被序列化省略、旧端解析崩
- 验证行（y≈5.0, h1.4, 白底 LINE 边框条）：粗体「验证」+ 三个证据：`MIGRATION_1_2 → 12_13 全链路保留` / `三端 Money 工具单测（parseMinor/format）` / `91+ 服务端用例含 MoneyTest`
- footer(s, 5)
- 验证：渲染 PNG 自查三列卡片不溢出（每卡正文 ≤42 字）。

### Task 3: 难点② 多端同步冲突页（重写现 S7 数据页 → P6）

**Files:** Modify `build.js`（替换现 S7 代码块整体；「13 大数字」素材移除，v13 已在难点①讲）

**内容规格：**
- header: `header(s, "难点 ②", "三端同时改一笔账：冲突怎么裁决")`
- 冲突场景图（y≈1.65, h2.1）：左侧两个设备小盒（App 离线改 ¥28→¥35 / Web 同步改 ¥28→¥20），中间箭头汇入右侧服务器裁决盒（NAVY 底白字「Last-Writer-Wins：updated_at 最新者胜」），下注「离线窗口越长，冲突概率越高」
- 机制五件套（y≈4.0, 横向 5 个小卡或两行 rows，每条 ≤14 字）：
  1. 软删墓碑 `deleted=1,dirty=1`
  2. `server_id` 去重
  3. `base_updated_at` 条件 PUT 乐观锁
  4. 复合游标分页 `(updatedAt,id)`
  5. `SyncManager` Mutex 单飞防抖
- 验证行（y≈5.6）：粗体「验证」+ `Web http409.test.ts 条件更新 409 冲突测试` + `同步字段三端 schema 逐一对齐` + 诚实披露一句（12.5pt MUTED）：「已知债：服务端乐观锁仍是先读后写，并发极端场景可丢更新（见路线页）」
- footer(s, 6)
- 验证：渲染自查场景图箭头对齐、五件套不溢出。

### Task 4: 难点③ NLU 双引擎与隐私页（重写现 S5 AI 页 → P7）

**Files:** Modify `build.js`（改造现 S5：保留阶梯图与红线带骨架，按三段式重排文案）

**内容规格：**
- header: `header(s, "难点 ③", "「午饭二十八块」怎么变一笔账")`
- 问题行（y≈1.6, 单行 14pt）：准确率、成本、隐私的三角矛盾——全走 LLM 又贵又慢还送明细
- 方案区（y≈2.1）：保留三级优先级阶梯（用户关键词 > 系统规则 > LLM 兜底）+ 右侧自学习闭环注解「用户纠正 → voice_keywords 沉淀 → 下次直接命中」
- 验证区（y≈4.6 左右）：洞察四件套压缩为一行四 chip（月度总结 / 异常提醒 / 自然问账 / 习惯提醒）
- 隐私红线带（y≈5.7, ACCENT_TINT, 保留原文案）：「隐私红线　送给 LLM 的只有『分类 + 金额 + 日期』聚合数据 —— 备注与未聚合明细永不出端。」右上角可加小字「（NFR 硬约束）」
- footer(s, 7)
- 验证：渲染自查阶梯文字对比度（BRAND 底用 NAVY 字，TINT 底用 INK 字）。

### Task 5: 架构页埋伏笔 + 功能全景/质量页轻改（P3/P4/P8）

**Files:** Modify `build.js`

1. **P4 架构页**（现 S6）底部备注两行改为：
   - 行 1：「断网也不影响记账：Room 本地先落库，恢复后增量双向同步 —— 三端并发冲突如何裁决？→ 难点②」
   - 行 2（新增 13pt）：「一句话为什么能变一笔账？→ 难点③」
2. **P3 功能全景**（现 S3）：卡片 h 2.44→2.26、body 行距收紧；页脚上方加一行 12.5pt MUTED「模块详情与升级规划见 FEATURES.md」。
3. **P8 质量页**（现 S8）：title 改「工作量与质量：48 天 · 232 次提交 · 240+ 用例全绿」；右列首行 desc 前置一句「App 133 + 服务端 108 + Web 63 = 304 个用例」。
- 验证：构建 + 渲染自查三页。

### Task 6: 产品实感截图占位页（新写，P9）

**Files:** Modify `build.js`；Create `.zcode/pptx-build/assets/screenshots/`（空目录 + `README.md` 说明命名约定）

1. `assets/screenshots/README.md`：约定四张图文件名 `home.png`（记账主页）、`quickadd.png`（快速记账抽屉）、`chart.png`（月度图表）、`qq.png`（QQ 机器人对话）；竖屏 9:19.5 左右，PNG。
2. 页面代码：header `header(s, "产品实感", "四张截图，看它真的能用")`；四个手机壳占位框（w1.95 h4.2 ROUNDED_RECTANGLE，LINE 虚线边框 dashType:"dash"，TINT2 底），内部居中放「截图占位」12pt MUTED + 各自内容标注在下：
   - 框下方标注条（13.5pt bold INK + 12pt BODY 一行）：「记账主页 · 一天一张卡」「快速记账 · 一步确认」「月度图表 · 热力图+饼图」「QQ 对话 · 一句话记账」
   - 代码用 `fs.existsSync("assets/screenshots/home.png")` 判断：存在则 `s.addImage({ path, sizing:{type:"cover",...} })` 替换占位（四张同逻辑，方便后补）
3. 底部一行 12.5pt MUTED：「截图来源：Android 真机（namespace com.example.rinklnote）· QQ 群聊实拍」
4. footer(s, 9)
- 验证：无截图文件时占位框渲染正常；放一张测试图验证 cover 裁切不变形。

### Task 7: 现场演示引导页（新写，P10）

**Files:** Modify `build.js`

1. header: `header(s, "现场演示", "三个演示点，各 30 秒")`
2. 三个演示行（y≈1.8 起，每行 h1.1，编号圈 + 粗体动作 + 预期效果）：
   1. 语音记一笔——对手机说「午饭花了二十八块」→ 预期：语音条实时出「¥28 · 三餐/午餐」，确认后主页当天卡片出现
   2. 看图说话——进月度详情 → 预期：热力图/饼图当天联动筛选
   3. QQ 记账——群里发「奶茶 15」→ 预期：机器人回复记账成功，App 里同步可见
3. 兜底条（y≈5.4, TINT 底）：粗体「演示兜底」+「若现场网络/设备不可用：P9 截图页即为备份，讲解顺序不变。」
4. footer(s, 10)
- 验证：渲染自查三行不溢出。

### Task 8: 进展与路线合并页（重写现 S9+S10 → P11）

**Files:** Modify `build.js`（两页合一，删除原 S10 代码块，重写 S9）

**内容规格：**
- header: `header(s, "进展与规划", "九月冲刺的成绩单，和接下来要还的账")`
- 上半：时间线压缩（保留 5 节点，日期 14pt、节点 y≈2.0、desc 单行化每条 ≤18 字，线 y≈2.15，整体高度压缩到 1.8）
- 下半左右两列（y≈4.35，中间竖线分隔）：
  - 左「已知债务 · 4 项已定位」精选 2 条：破坏性迁移兜底仍在（未覆盖路径会清库）／服务端乐观锁先读后写（并发可丢更新）
  - 右「下一步路线 · FEATURES.md」精选 2 条：周期性账单（固定支出自动记）／群聊多人记账（家庭共享账本）
- 底部一行 12.5pt MUTED：「完整债务与待办清单见 README / FEATURES / AGENTS 三处文档」
- footer(s, 11)
- 验证：渲染自查上下两段不拥挤、时间线 desc 无孤字。

### Task 9: 结尾页微调（P12）

**Files:** Modify `build.js`

1. 原 S11 结尾页：footer 无需改；chips 中「240+ 用例全绿」保留。
2. 验证：整体 12 页页序正确（S1 封面 / S2 概览 / S3 功能 / S4 架构 / S5 难点① / S6 难点② / S7 难点③ / S8 质量 / S9 截图 / S10 演示 / S11 进展路线 / S12 结尾），每页页脚编号无误。

### Task 10: 全量渲染 + judge 验收 + 交付

1. 全量构建 + 渲染 12 张 PNG。
2. 派 judge subagent 全量验收（重点：P5–P7 难点页、P9 占位页、P11 合并页；沿用首轮验收的检查清单与行首禁则标准）。
3. 修复 judge 发现的问题并复检（循环直到 pass）。
4. `cp rinklnote-report.pptx /d/Codes/RinklNote/RinklNote-项目汇报-2026-09.pptx` 覆盖交付。
5. 汇报改动摘要。

---

**素材待办（用户侧，非阻塞）：** 四张真机截图按 `assets/screenshots/README.md` 命名放入后，重跑 Task 6 的存在性判断即自动替换占位图（`node build.js && python postprocess.py` + COM 渲染 + 复制成品）。
