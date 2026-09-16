/* ECharts 配置工具（Charts 页专属）
 * - 颜色：读 theme.css 的图表令牌（--chart-1…7 / --chart-axis / 收支色 / 描边色），
 *   getComputedStyle 实时反映当前主题，主题切换后由页面触发重新取值再 setOption。
 * - 动画：对齐规格 —— 初始绘制 500ms / 数据更新 300ms，缓动统一 cubicOut。
 * 全部为纯函数 + 常量，便于测试与复用。 */

/** 与 App 端 PiePalette 对齐的兜底色板（CSS 变量读不到时使用，如测试环境无 DOM） */
export const FALLBACK_PALETTE = ['#7EC1FC', '#F97D1D', '#04A433', '#9B59B6', '#F2B134', '#CA3032', '#B0B0B0']

function cssVar(name: string, fallback: string): string {
  if (typeof document === 'undefined' || !document.documentElement) return fallback
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/** 图表色板：--chart-1…7（末位灰固定给「其他」兜底位） */
export function readChartPalette(): string[] {
  return Array.from({ length: 7 }, (_, i) => cssVar(`--chart-${i + 1}`, FALLBACK_PALETTE[i]))
}

/** 轴文字 / 图例文字颜色：--chart-axis */
export function readChartAxisColor(): string {
  return cssVar('--chart-axis', '#6b7280')
}

/** 语义色：支出红 / 收入绿（主题切换后暗色有专属配色） */
export function readExpenseIncomeColors(): { expense: string; income: string } {
  return { expense: cssVar('--expense', '#CA3032'), income: cssVar('--income', '#04A433') }
}

/** 分割线 / 描边色（跟随边框令牌） */
export function readChartLineColor(): string {
  return cssVar('--border-light', 'rgba(127,127,127,.2)')
}

/** ECharts 受控动画参数（初始 draw 500ms / update 300ms，cubicOut） */
export const CHART_ANIMATION = {
  animation: true,
  animationDuration: 500,
  animationDurationUpdate: 300,
  animationEasing: 'cubicOut',
  animationEasingUpdate: 'cubicOut',
} as const
