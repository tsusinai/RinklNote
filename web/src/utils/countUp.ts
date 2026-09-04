const last: Record<string, number> = {}

export function countUp(
  el: { textContent: string },
  target: number,
  key: string | null,
  fmt: (n: number) => string,
  dur = 400,
): void {
  if (key && last[key] === target) { el.textContent = fmt(target); return }
  if (key) last[key] = target
  const start = performance.now()
  const frame = (now: number) => {
    const t = Math.min(1, (now - start) / dur)
    const eased = 1 - Math.pow(1 - t, 3)
    el.textContent = fmt(target * eased)
    if (t < 1) requestAnimationFrame(frame)
  }
  requestAnimationFrame(frame)
}
