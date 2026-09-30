import { reactive, readonly } from 'vue'

/** kind：ok 成功（绿）/ err 失败（红）/ warn 部分成功等需留意的中性提醒（橙） */
export interface Toast { id: number; msg: string; kind: 'ok' | 'err' | 'warn' }
const state = reactive<{ toasts: Toast[] }>({ toasts: [] })
let seq = 0

export function useToast() {
  function push(msg: string, kind: 'ok' | 'err' | 'warn' = 'ok', dur = 2200) {
    const id = ++seq
    state.toasts.push({ id, msg, kind })
    setTimeout(() => close(id), dur)
  }
  function close(id: number) { const i = state.toasts.findIndex(t => t.id === id); if (i >= 0) state.toasts.splice(i, 1) }
  return { toasts: readonly(state.toasts), push, close }
}
