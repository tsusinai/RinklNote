export interface DefenseSlide {
  title: string
  section: string
  eyebrow: string
  heading: string
  description: string
  note: string
}

// 答辩叙事以 Android 端为主，其他入口（如 QQ Bot）作为降低记录门槛、延伸触达的创新辅助能力。
export const slides: DefenseSlide[] = [
  {
    title: '项目介绍',
    section: '开场',
    eyebrow: '给年轻人的第一本长期账本 · Android 优先',
    heading: '想记账的人很多，\n能坚持的人很少。',
    description: 'RinklNote 记一笔 · 基于 Jetpack Compose 的本地优先习惯型记账应用',
    note: '建议 45 秒。开场点题：记账最大的阻碍不是算力或报表，而是习惯断裂。大学生和职场新人知道记账有用，却常被繁琐步骤劝退。RinklNote 从年轻人真实生活场景出发，确立“Android 本地优先、最短路径、即时正向反馈”的核心理念，把复杂的账目管理融入极简自然的每一次记录。',
  },
  {
    title: '市场痛点',
    section: '发现问题',
    eyebrow: '年轻人不是不想理财，而是记录过程充满摩擦',
    heading: '容易忘、录入繁、\n记完缺乏正向反馈。',
    description: '直击大学生与职场新人三大真实痛点，重构移动端记账交互链路。',
    note: '建议 55 秒。深入分析三大真实困境：① 消费现场遗忘——聚餐或下课后记录念头转瞬即逝；② 表单认知负担重——多级分类、账户选择和繁杂字段在第一步就劝退用户；③ 正反馈缺失——只留下冷冰冰的数字列表，看不清预算动态。RinklNote 的全部设计都围绕化解这三重阻力展开。',
  },
  {
    title: 'Android 核心闭环',
    section: '产品能力',
    eyebrow: '从打开手机到落库确认，秒级顺畅完成',
    heading: '打开就能记，\n记完立即看见反馈。',
    description: '稳定首页、定制数字键盘一步确认、按日分组流水与动态预算卡，构筑极简闭环。',
    note: '建议 55 秒。串联核心主链路：打开 App 直达稳定首页；悬浮按钮一触即发快速记账；数字键盘自研布局，输入金额与主分类一步确认（已彻底移除繁冗二次弹窗）；当天卡片即刻呈现，预算水位实时动态反馈，形成“记录—反馈—激励”的自洽闭环。',
  },
  {
    title: 'Android 技术架构',
    section: '技术实现',
    eyebrow: '本地优先（Local-First）工程哲学，体验极致轻盈',
    heading: '清晰的响应式边界，\n让交互如丝般顺滑。',
    description: 'Kotlin 2.0 + Compose UI + ViewModel + Repository + Room v18 构建坚固底座。',
    note: '建议 60 秒。拆解四层架构分工：Compose 声明式 UI 负责纯粹的状态渲染与流畅动效；ViewModel 统一管理 @Immutable UIState 与单向 Event；Repository 封装单例内存缓存与数据源策略；Room v18 承担本地持久化源头。网络恢复时增量双向同步，服务端聚焦认证与中继，端侧永不因弱网卡顿。',
  },
  {
    title: '离线与可靠性',
    section: '移动体验',
    eyebrow: '生活发生在断网角落，记账绝不受制于网络',
    heading: '本地先入库，云端异步对齐，\n每一笔都有迹可循。',
    description: 'Room 事务隔离、StateFlow 实时分发与 Room v18 迁移链，杜绝丢账与数据损坏。',
    note: '建议 60 秒。深入可靠性保障：断网时输入直接落库 Room，界面毫秒级就绪；StateFlow 驱动全局账单、分类与预算卡片响应式联动；严谨维护 MIGRATION_1_2 至 17_18 完整迁移链路，且彻底移除破坏性清库兜底；同步采用 server_id 去重与失败重试队列，用户绝无重复记账之忧。',
  },
  {
    title: '习惯型交互设计',
    section: '交互设计',
    eyebrow: '以行为科学设计动力回路，不制造记账焦虑',
    heading: '不是催促用户记账，\n而是让记账自然发生。',
    description: '极短输入路径、智能预填建议、按日轻松复盘与温和激励体系。',
    note: '建议 55 秒。阐述习惯养成机制：利用消费时间与频次提供智能预填建议；右滑手势再来一笔；按日折叠卡片化解面对长流水的心理压力；月度热力图与省钱挑战大字报提供直观成就感；温和的晨暮关怀与预算亮灯取代高压催促，保护用户长期记账热情。',
  },
  {
    title: '设计创新：QQ Bot',
    section: '创新能力',
    eyebrow: '跨入年轻人的高频社交场，打通记账最后一公里',
    heading: '无需切出聊天，\n在 QQ 群聊里一句话入账。',
    description: '小盘 QQ 管家作为 Android 主账本的场景化延伸，支持多模态输入与双向秒级同步。',
    note: '建议 70 秒。重申多端协同定位：QQ Bot 绝非简陋复刻，而是作为“最轻量记录采集器”。用户在群聊或私信中发送“奶茶 15”或语音、账单小票，机器人瞬间解析结构化入账并回执；后续的深入预算分析、月度走势和资产总览回归 Android 主端，双端同一账本、无缝互补。',
  },
  {
    title: '关键技术难点',
    section: '难点突破',
    eyebrow: '极简交互背后的深层工程攻坚',
    heading: '输入可以随心口语化，\n底层数据必须严谨可信。',
    description: '金额全链路整数分（Long）、时区统一、双向 LWW 裁决与条件乐观锁防并发。',
    note: '建议 70 秒。剖析三大硬核技术攻坚：① 消除浮点漂移：三端彻底统一使用整数分（Long）存储与传输，配合严格的 Money 工具格式化契约；② 并发冲突裁决：多设备离线修改采用 Last-Writer-Wins 时间戳判定，服务端以条件版本 UPDATE 避免覆写；③ 隐私红线恪守：送往 LLM 的只有分类、金额、日期的聚合脱敏摘要，逐笔明细与敏感备注绝不出端。',
  },
  {
    title: '交互演示',
    section: '现场体验',
    eyebrow: '自然语言到严谨账单的实时结构化解析',
    heading: '“午饭 28 元”，\n一眨眼化为清晰流水。',
    description: '离线交互演示：意图提取、分类映射与即时预览，始终把最终决策权赋予用户。',
    note: '建议 60 秒。现场互动演示说明：本页展示口语化自然语言的解析流水线。输入如“午饭 28 元”或“地铁 4 元”，实时提取金额分值并智能归类至对应支出标签，展示可核对的预览卡片；点击确认即模拟完成闭环。突出强调“算法辅助预填，用户终审确认”的防误记理念。',
  },
  {
    title: '测试与验证',
    section: '工程质量',
    eyebrow: '严谨的工程实践，是产品长期可信赖的基石',
    heading: '经受千百次用例打磨，\n确保每一笔都精确无误。',
    description: 'Android 333 项核心测试全绿通过，全仓三端 1,018 个自动化用例筑牢质量护栏。',
    note: '建议 55 秒。列举坚实的测试证据：Android 端 333 项 JVM 单元测试覆盖全部核心 ViewModel、Room DAO 及 Money 换算工具；服务端 417 项测试验证 JWT 认证、NLU 意图及并发接口；Web 端 268 项 Vitest 测试把关；代码规范落实严格，APK 自动化打包流水线完备。',
  },
  {
    title: '总结与展望',
    section: '下一步',
    eyebrow: '从一次轻松的尝试，走向一生的理财习惯',
    heading: '让记账不再是一项任务，\n而是生活里的确定感。',
    description: '持续优化 Android 极速触达能力，深化多通道智能交互与家庭账本协同。',
    note: '建议 50 秒。展望下一阶段演进：① 移动端前沿探索：Glance 桌面快捷微组件、通知栏即时记账与周期性固定收支自动规划；② 多通道管家增强：QQ / 飞书 / 企微 Bot 的离线纠偏自学习与更精细的隐私分级；③ 多人协同扩展：家庭账本与群聊 AA 自动清算。',
  },
  {
    title: '致谢与问答',
    section: '交流讨论',
    eyebrow: '用心记录生活，读懂每一份成长',
    heading: '感谢各位评委老师倾听，\n欢迎批评指正与提问。',
    description: '期待与各位老师共同探讨移动端本地优先架构、习惯养成设计与多通道机器人的融合实践。',
    note: '准备 Q&A 交流。可主动引导评委回看“本地优先架构”、“Double 迁整数分”、“多端 LWW 冲突裁决”或“QQ Bot 协同边界”等核心攻关页面，自信展示技术深度与产品思考。',
  },
]

export function pageFromQuery(value: unknown): number {
  const n = typeof value === 'string' ? Number(value) : 1
  return Number.isInteger(n) && n >= 1 && n <= slides.length ? n - 1 : 0
}
