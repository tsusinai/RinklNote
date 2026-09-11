// 金额工具：全链路以「分」(minor unit) 的整数表示金额，避免浮点误差。
//
// 契约（三端一致）：
//   - 存储 / 传输 / 计算：一律「分」整数，字段名 amountMinor / balanceMinor / totalXxxMinor
//   - 展示：统一调用 formatMoney(minor)，输出形如 `¥1,234.50`（带千分位与货币符号）
//
// 后端兼容说明：响应里可能同时带旧的 amount（元，Double），Web 一律只读
// amountMinor / balanceMinor，忽略 amount / balance 兼容字段。

/** 把「分」整数格式化为「¥1,234.50」「-¥12.30」（固定 2 位小数 + 千分位）。
 *  使用纯整数拆分，全程不引入任何浮点乘法运算。 */
export function formatMoney(minor: number): string {
  // 动画中间帧可能是小数，先取整到最近的分
  const n = Math.round(minor)
  const sign = n < 0 ? '-' : ''
  const abs = Math.abs(n)
  const yuan = Math.floor(abs / 100)
  const cents = abs % 100
  const withSep = String(yuan).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return `${sign}¥${withSep}.${String(cents).padStart(2, '0')}`
}

/** 不带货币符号的金额展示：`1,234.50`。
 *  用于「¥」由模板单独渲染的场景（例如 ¥ 与数字分属不同样式/元素）。 */
export function formatMoneyPlain(minor: number): string {
  return formatMoney(minor).replace('¥', '')
}

/** 带正负号的金额展示：正数前缀 +，负数前缀 -。 */
export function formatSigned(minor: number): string {
  const n = Math.round(minor)
  const s = formatMoney(Math.abs(n))
  return n < 0 ? '-' + s : '+' + s
}

/** 把用户输入的「元」字符串解析为「分」整数，最多 2 位小数。
 *  策略：正则拆分整数/小数部分后做整数运算，杜绝 parseFloat(x) * 100 的浮点误差
 *  （如 "57.97" 必须得到 5797 而非 5796.999…）。
 *  非法输入（含负号、字母、空串、超过 2 位小数）一律返回 null，由调用方给出提示。 */
export function parseMoneyToMinor(input: string): number | null {
  const s = String(input ?? '').trim()
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null
  const [intPart, decPart = ''] = s.split('.')
  const yuan = parseInt(intPart, 10)
  const cents = parseInt((decPart + '00').slice(0, 2), 10)
  const minor = yuan * 100 + cents
  return Number.isSafeInteger(minor) ? minor : null
}
