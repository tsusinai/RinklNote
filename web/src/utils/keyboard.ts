/* 键盘流纯函数（Task 3.4）：全局快捷键与输入焦点、金额直输的规则判断。
 * 与 DOM 只弱耦合（isEditableTarget 接收 EventTarget），便于单测。 */

/** 目标是否为可编辑元素：输入框 / 文本域 / 下拉 / contenteditable。
 *  全局快捷键在可编辑元素聚焦时一律不拦截（组合键 Ctrl+K 除外，由调用方决定）。 */
export function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  // contenteditable 双通道：浏览器读 isContentEditable；jsdom 不回填该属性，回落查 attribute
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
    || target.isContentEditable === true
    || target.getAttribute('contenteditable') === 'true'
}

/**
 * 记账页金额直输：把「按键」合入当前金额字符串。
 * - 数字 0-9：追加；整数部分 ≤8 位、小数 ≤2 位（与 parseMoneyToMinor 契约对齐，超限返回 null）
 * - 小数点：无小数点时追加（空串补全为 "0."）
 * - Backspace：删除末位（空串仍为空串）
 * - 其余按键一律返回 null（调用方不处理）
 */
export function appendAmountKey(current: string, key: string): string | null {
  if (key >= '0' && key <= '9') {
    const next = current + key
    const intPart = next.split('.')[0]
    const decPart = next.includes('.') ? next.split('.')[1] : ''
    if (intPart.length > 8 || decPart.length > 2) return null
    return next
  }
  if (key === '.') {
    if (current.includes('.')) return null
    return (current === '' ? '0' : current) + '.'
  }
  if (key === 'Backspace') return current.slice(0, -1)
  return null
}
