import type { Directive } from 'vue'

/* v-reveal 进场揭示指令（升级版）
 * - 无值：<div v-reveal> 进视口后加 .revealed
 * - 带值：v-reveal="delayMs" 进场 stagger 延迟（毫秒），配合同屏多个元素错落进场
 * - unmounted 清理：断开 IntersectionObserver、清掉延迟定时器、移除内联 delay */

interface RevealState {
  io?: IntersectionObserver
  timer?: number
}

// 每元素状态存 WeakMap，元素销毁后自动可回收
const states = new WeakMap<HTMLElement, RevealState>()

// reveal 过渡时长 .6s（见 theme.css），延迟结束后再多留一点余量清 delay
const REVEAL_TRANSITION_MS = 600

export const reveal: Directive<HTMLElement, number | undefined> = {
  mounted(el, binding) {
    el.classList.add('reveal')
    const state: RevealState = {}
    states.set(el, state)

    // stagger 延迟：内联 transition-delay；揭示完成后清掉，避免残留影响元素后续过渡
    const delay = typeof binding.value === 'number' && binding.value > 0 ? binding.value : 0
    if (delay > 0) el.style.transitionDelay = `${delay}ms`

    if (typeof IntersectionObserver === 'undefined') {
      // 兜底环境（如测试）：无 IntersectionObserver，直接可见
      el.classList.add('revealed')
      el.style.transitionDelay = ''
      return
    }

    state.io = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue
        el.classList.add('revealed')
        // 延迟过渡跑完后再移除内联 delay
        state.timer = window.setTimeout(() => { el.style.transitionDelay = '' }, delay + REVEAL_TRANSITION_MS)
        state.io?.disconnect()
        state.io = undefined
      }
    }, { threshold: 0.12 })
    state.io.observe(el)
  },

  unmounted(el) {
    const state = states.get(el)
    if (!state) return
    state.io?.disconnect()
    if (state.timer !== undefined) window.clearTimeout(state.timer)
    el.style.transitionDelay = ''
    states.delete(el)
  },
}
