export interface DefenseSlide {
  title: string
  section: string
  eyebrow: string
  heading: string
  description: string
  note: string
}

// 答辩叙事以 Android 端为主，其他入口只作为降低记录门槛的辅助能力。
export const slides: DefenseSlide[] = [
  { title: '项目介绍', section: '开场', eyebrow: '给年轻人的第一本长期账本', heading: '想记账的人很多，\n能坚持的人很少。', description: 'RinklNote 记一笔 · 面向大学生与初入职场人群的 Android 优先习惯型记账应用', note: '建议 45 秒。用大学生或刚工作的年轻人举例：知道记账有用，却经常忘记、嫌步骤多，月底只能凭感觉回想。RinklNote 的核心不是堆功能，而是让“记一笔”足够自然，慢慢培养长期习惯。' },
  { title: '市场痛点', section: '发现问题', eyebrow: '年轻人不是没有意愿，而是被记录链条打断', heading: '忘记记、不会记、\n记了也看不懂。', description: '问题不在于年轻人不想管理钱，而在于从想起到完成之间阻力太多。', note: '建议 55 秒。说明目标用户是大学生、初入职场的上班族等年轻人。三个痛点分别是：消费发生后忘记记录；面对分类、账户和金额感到困难；记完流水却没有反馈，无法形成下一次记录的动力。这里是产品定位假设，不声称调研统计。' },
  { title: 'Android 核心闭环', section: '产品能力', eyebrow: '从打开手机，到完成一笔记录', heading: '打开就能记，\n记完马上看见反馈。', description: '把“打开 → 输入 → 确认 → 反馈”压缩成一条不绕路的移动路径。', note: '建议 55 秒。按实际使用顺序讲：首页看到今天状态；点击记一笔；输入金额与分类；确认后立即回到当天账单；预算卡片给出“还剩多少”的即时反馈。每一步都服务于下一次打开。' },
  { title: 'Android 技术架构', section: '技术实现', eyebrow: '本地优先，保证记录不被网络打断', heading: '清晰的状态边界，\n让体验保持轻盈。', description: 'Compose + ViewModel + Repository + Room 构成移动端主链路，服务端只负责同步与鉴权。', note: '建议 60 秒。先讲 Android 数据流：Compose 订阅 StateFlow，ViewModel 管理页面状态，Repository 统一数据来源，Room v18 持久化账本；网络恢复后再通过 API 对齐。其他端不展开，只说明它们共享同一账本契约。' },
  { title: '离线与可靠性', section: '移动体验', eyebrow: '记录发生在生活现场，网络不应该成为前提', heading: '先保存，再同步，\n每一步都有回应。', description: '本地数据库、状态流和迁移链共同保护年轻人的每一次记录。', note: '建议 60 秒。重点讲三个实现选择：输入先写入 Room，页面马上反馈；StateFlow 让列表、余额和预算同步变化；Room v18 迁移链保证应用升级时数据不丢。同步失败可重试，用户不需要重新输入。' },
  { title: '习惯型交互设计', section: '交互设计', eyebrow: '把复杂能力藏在简单动作之后', heading: '不是提醒用户记账，\n而是让记账更容易发生。', description: '把一次记账设计成可重复的习惯回路：短路径、即时反馈、隔天还想回来。', note: '建议 55 秒。解释 Android 端的行为设计：默认入口保持稳定；金额输入后快速给出分类建议；按日归组降低回看压力；预算进度和连续记录提供小反馈。提醒应该是温和的辅助，不制造焦虑。' },
  { title: '设计创新：QQ Bot', section: '创新能力', eyebrow: '把记账带到年轻人已经在使用的聊天入口', heading: '不必打开 App，\n在 QQ 里也能记一笔。', description: 'QQ Bot 是 Android 主体验的延伸：当用户不方便打开应用时，用一句话把记录送回账本。', note: '建议 70 秒。强调 QQ Bot 不是替代 Android，而是解决“想起来却懒得打开”的最后一步。用户完成绑定后发送“午餐 20 元”即可进入同一账本；机器人负责解析、确认与回执，Android 负责完整回看、预算和长期管理。' },
  { title: '关键技术难点', section: '难点突破', eyebrow: '让低门槛不牺牲账本的可信度', heading: '输入可以自然，\n数据必须可靠。', description: '整数分、本地迁移、重复消息去重与版本保护，支撑 Android 和 QQ Bot 的一致体验。', note: '建议 70 秒。用 0.1 + 0.2 的浮点误差引出整数分；Room 迁移保证升级安全；QQ Bot 需要事件去重，避免重复投递造成重复账单；服务端用版本条件更新返回 409，避免静默覆盖。' },
  { title: '交互演示', section: '现场体验', eyebrow: '从一句生活表达，到一笔可确认的账', heading: '“午饭 28 元”，\n就这样记下来了。', description: '演示移动端自然语言入口的交互思路：识别、预览、确认，始终把决定权留给用户。', note: '建议 60 秒。本页是离线交互原型，用规则模拟 Android / QQ Bot 的自然语言入口，不请求模型也不写入真实账本。先输入“午饭 28 元”，观察分类和金额预览，再点击确认；强调真实产品仍会保留确认环节。' },
  { title: '测试与验证', section: '工程质量', eyebrow: '习惯产品更要经得起长期使用', heading: '先保证每一笔不丢，\n再谈让用户坚持。', description: '以下为 2026 年 9 月 30 日 Android 环境验证记录，不代表当前实时测试状态。', note: '建议 55 秒。Android 单元测试 301 项通过，Debug APK 构建成功。测试覆盖快速记账、账单列表、预算、资产、AI 入口和同步相关 ViewModel；本页只展示 Android 证据。未覆盖真机兼容性、性能压测和完整安全审计。' },
  { title: '总结与展望', section: '下一步', eyebrow: '从一次记录，走向一个习惯', heading: '让年轻人想起记账时，\n真的愿意记下来。', description: '从 Android 的第一笔记录开始，继续把“想起 → 记录 → 反馈 → 再回来”做得更自然。', note: '建议 50 秒。总结产品价值：解决忘记记和记账困难，不靠复杂报表，而靠更短路径和更及时反馈。下一步规划包括桌面小组件、可控提醒、连续记录反馈、QQ Bot 解析增强和更细的隐私设置，均不承诺交付日期。' },
  { title: '致谢与问答', section: '交流讨论', eyebrow: '让每一笔，成为更好生活的起点', heading: '谢谢聆听，\n欢迎提问。', description: '期待讨论 Android 交互、习惯培养与 QQ Bot 的产品边界。', note: '留出问答时间。可回看 Android 架构、离线策略或 QQ Bot 创新页。常见问题：为什么本地优先？如何避免重复记账？提醒怎样不打扰？QQ Bot 与 Android 如何保持同一账本？' },
]

export function pageFromQuery(value: unknown): number {
  const n = typeof value === 'string' ? Number(value) : 1
  return Number.isInteger(n) && n >= 1 && n <= slides.length ? n - 1 : 0
}
