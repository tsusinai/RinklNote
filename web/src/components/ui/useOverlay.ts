import { ref, watch, onBeforeUnmount, nextTick, type Ref } from 'vue'
import { acquireOverlaySlot, releaseOverlaySlot } from './overlayGate'

/* 浮层通用行为（Modal / Sheet 共用）：
 * - 串行闸门：open=true 先排队等名额，resolve 后才真正渲染进场（出完 240ms 再进下一个）
 * - Esc 关闭 + Tab 焦点圈禁（监听挂 document，浮层未展示时不生效）
 * - 浮层展示期间锁定 body 滚动，退场动画结束（after-leave）后归还名额并恢复滚动
 * - 卸载兜底归还名额，避免闸门泄漏 */

export function useOverlay(open: Ref<boolean>, close: () => void) {
  const shown = ref(false) // 实际渲染开关（拿到串行名额后才置 true）
  const panelRef = ref<HTMLElement | null>(null)
  let held = false // 是否已持有串行名额（shown=true 期间恒为 true）

  function releaseSlot(): void {
    if (!held) return
    held = false
    document.body.style.overflow = ''
    releaseOverlaySlot()
  }

  function onKeydown(e: KeyboardEvent): void {
    if (!shown.value) return
    if (e.key === 'Escape') { e.stopPropagation(); close(); return }
    if (e.key !== 'Tab') return
    // 焦点圈禁：Tab 循环限制在面板内
    const panel = panelRef.value
    if (!panel) return
    const items = Array.from(panel.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    )).filter((el) => !el.hasAttribute('disabled'))
    if (items.length === 0) { e.preventDefault(); return }
    const first = items[0]
    const last = items[items.length - 1]
    const cur = document.activeElement as HTMLElement | null
    if (e.shiftKey && (cur === first || !panel.contains(cur))) { e.preventDefault(); last.focus() }
    else if (!e.shiftKey && (cur === last || !panel.contains(cur))) { e.preventDefault(); first.focus() }
  }

  watch(open, async (v) => {
    if (v) {
      await acquireOverlaySlot() // 上一个浮层退场满 240ms 后才放行
      if (!open.value) { releaseOverlaySlot(); return } // 排队期间已被关闭：归还名额
      if (shown.value) return // 竞态保护：快速开关后另一次回调已进场
      held = true
      shown.value = true
      document.body.style.overflow = 'hidden'
      await nextTick()
      panelRef.value?.focus()
    } else if (shown.value) {
      shown.value = false // 触发退场动画；名额在 after-leave 归还
    }
  }, { immediate: true })

  watch(shown, (v) => {
    if (v) document.addEventListener('keydown', onKeydown)
    else document.removeEventListener('keydown', onKeydown)
  })

  onBeforeUnmount(() => {
    document.removeEventListener('keydown', onKeydown)
    releaseSlot() // 卸载兜底：Transition 的 after-leave 可能不再触发
  })

  return { shown, panelRef, onAfterLeave: releaseSlot }
}
