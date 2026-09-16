import { prefersReducedMotion } from './motion'

/* 数字滚动重写（对齐规格）：
 * 1. from 值滑动 —— 数据变化时从上一次展示值滑向新值，不归零重涨；
 * 2. rAF 取消 —— 同一目标的进行中动画在新一帧到来前被取消，避免多帧竞争与泄漏；
 * 3. reduced-motion 定格 —— 系统开启「减少动态效果」时直接定格终值。
 * 签名与旧版保持兼容：countUp(el, target, key, fmt, dur=400)。 */

const lastTarget: Record<string, number> = {} // key → 上一次目标值（重复调用直接跳过动画）
const lastShown: Record<string, number> = {} // key → 上一次展示值（作为下一次动画起点）
const pending = new Map<string | object, number>() // 动画句柄（key 或 el），用于 rAF 取消

function stopRaf(token: string | object): void {
  const id = pending.get(token)
  if (id !== undefined) {
    cancelAnimationFrame(id)
    pending.delete(token)
  }
}

export function countUp(
  el: { textContent: string },
  target: number,
  key: string | null,
  fmt: (n: number) => string,
  dur = 400,
): void {
  const token: string | object = key ?? el
  stopRaf(token)
  const finish = () => {
    if (key) { lastTarget[key] = target; lastShown[key] = target }
    el.textContent = fmt(target)
  }
  if (prefersReducedMotion() || dur <= 0) { finish(); return }
  if (key && lastTarget[key] === target) { el.textContent = fmt(target); return }
  // 起点：优先用上一次展示值滑动；首次出现（或无 key）从 0 涨
  const from = key ? (lastShown[key] ?? 0) : 0
  if (key) lastTarget[key] = target
  const start = performance.now()
  const frame = (now: number) => {
    const t = Math.min(1, (now - start) / dur)
    const eased = 1 - Math.pow(1 - t, 3)
    const value = from + (target - from) * eased
    if (key) lastShown[key] = value
    el.textContent = fmt(value)
    if (t < 1) pending.set(token, requestAnimationFrame(frame))
    else pending.delete(token)
  }
  pending.set(token, requestAnimationFrame(frame))
}
