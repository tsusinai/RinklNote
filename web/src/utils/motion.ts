/* 动效偏好与全局动效开关（JS 侧统一检查点）
 * CSS 侧的 reduced-motion 降级由 theme.css 全局规则兜底，这里只服务 JS 动画（countUp 等）。 */

/** 系统是否开启「减少动态效果」。每次 live 读取 matchMedia（开销极低），无需监听刷新缓存。 */
export function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** 页面隐藏时给 <html> 挂 anim-paused，暂停全站 CSS 动画（省电）；回到前台恢复。
 *  在应用入口调用一次即可，详见 theme.css 的 .anim-paused 规则。 */
export function setupAuroraPowerSave(): void {
  if (typeof document === 'undefined') return
  document.addEventListener('visibilitychange', () => {
    document.documentElement.classList.toggle('anim-paused', document.hidden)
  })
}
