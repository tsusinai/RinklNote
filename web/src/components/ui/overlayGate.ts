/* 浮层串行闸门：对齐 App「浮层严格串行」——上一个浮层退场后需再空 240ms（--dur-drawer）
 * 才放行下一个进场，避免两层浮层交叠进退。
 * Modal / Sheet 通过 useOverlay() 共用这里的名额管理。 */

const GAP = 240

let openCount = 0 // 当前持有展示名额的浮层数
let gapUntil = 0 // 退场缓冲截止时间戳（performance.now() 毫秒）
let pumpTimer: number | null = null
const waiters: Array<() => void> = []

function pump(): void {
  pumpTimer = null
  if (openCount > 0 || waiters.length === 0) return
  const wait = gapUntil - performance.now()
  if (wait > 0) { pumpTimer = window.setTimeout(pump, wait + 1); return }
  openCount = 1
  const next = waiters.shift()
  next?.()
}

/** 申请一个浮层展示名额；若上一浮层未退场或缓冲未满则排队，resolve 即可进场。 */
export function acquireOverlaySlot(): Promise<void> {
  if (openCount === 0 && waiters.length === 0 && performance.now() >= gapUntil) {
    openCount = 1
    return Promise.resolve()
  }
  return new Promise<void>((resolve) => {
    waiters.push(() => { openCount = 1; resolve() })
    if (pumpTimer === null) pump()
  })
}

/** 归还浮层名额；若有人在排队，缓冲 240ms 后放行下一个。 */
export function releaseOverlaySlot(): void {
  openCount = Math.max(0, openCount - 1)
  gapUntil = performance.now() + GAP
  if (openCount === 0 && waiters.length > 0 && pumpTimer === null) {
    pumpTimer = window.setTimeout(pump, GAP + 1)
  }
}
