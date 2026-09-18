/* Ctrl+K 命令面板的动作配置与纯函数（Task 3.4）
 * 动作表是配置数组：新增命令只需在这里追加条目，面板组件零改动。
 * 执行器（router.push / 主题切换 / 导出）在 CommandPalette.vue 注入，
 * 本文件保持纯数据 + 纯函数，便于单测。 */

import type { IconName } from '../components/ui/icons'

/** 命令动作类型：route = 路由跳转；theme = 亮暗切换；export-csv = 导出账单 */
export type CommandActionType = 'route' | 'theme' | 'export-csv'

export interface CommandAction {
  id: string
  label: string
  /** 辅助匹配词（拼音/英文），与 label 一起参与过滤 */
  keywords: string
  icon: IconName
  type: CommandActionType
  /** type === 'route' 时的目标路由 name（ConsoleLayout 的 console 子路由名） */
  route?: string
}

/** 面板动作表：常用导航 + 高频动作（顺序即默认展示顺序） */
export const COMMAND_ACTIONS: readonly CommandAction[] = [
  { id: 'bookkeeping', label: '记一笔', keywords: 'bookkeeping add jiyibi', icon: 'book', type: 'route', route: 'bookkeeping' },
  { id: 'bills', label: '搜索账单', keywords: 'search bills sousuo', icon: 'search', type: 'route', route: 'bills' },
  { id: 'charts', label: '图表分析', keywords: 'charts tubiao', icon: 'chart', type: 'route', route: 'charts' },
  { id: 'assets', label: '资产', keywords: 'assets zichan', icon: 'wallet', type: 'route', route: 'assets' },
  { id: 'me', label: '我的', keywords: 'me profile wode', icon: 'user', type: 'route', route: 'me' },
  { id: 'settings', label: '设置', keywords: 'settings shezhi', icon: 'settings', type: 'route', route: 'settings' },
  { id: 'export-csv', label: '导出账单 CSV', keywords: 'export csv daochu', icon: 'share', type: 'export-csv' },
  { id: 'theme', label: '切换亮 / 暗主题', keywords: 'theme dark light yeliang anse', icon: 'moon', type: 'theme' },
]

/** 面板过滤：空串返回全部；对 label + keywords 做大小写不敏感的包含匹配 */
export function filterActions(query: string, actions: readonly CommandAction[] = COMMAND_ACTIONS): CommandAction[] {
  const q = query.trim().toLowerCase()
  if (!q) return [...actions]
  return actions.filter((a) => a.label.toLowerCase().includes(q) || a.keywords.toLowerCase().includes(q))
}

/** 选中项游标：带符号步进并环形回绕；空列表返回 -1 */
export function nextIndex(current: number, delta: number, length: number): number {
  if (length <= 0) return -1
  const n = ((current + delta) % length + length) % length
  return n
}
