import { ref } from 'vue'
import type { Router } from 'vue-router'

/* 页面转场方向：按路由 meta.tab 序号判定（对齐 App「按 tab 序号判方向」规则）。
 * - 前进（tab 变大）：新页从右侧 24px 滑入，旧页向左滑出（page-forward）
 * - 后退（tab 变小）：方向相反（page-back）
 * - 同 tab / 控制台进出 / Landing↔Login 等无 tab 场景：纯 fade（page-fade）
 * 消费方：App.vue 顶层 fade + ConsoleLayout 内层方向转场；CSS 见 app.css。
 * 只动 transform/opacity，时长取 --dur-page(280ms)。 */
export const pageTransition = ref('page-fade')

export function installTransition(router: Router): void {
  router.afterEach((to, from) => {
    // 一侧无 tab（进出控制台）或 tab 相同 → 纯 fade
    if (to.meta.tab === undefined || from.meta.tab === undefined || to.meta.tab === from.meta.tab) {
      pageTransition.value = 'page-fade'
      return
    }
    pageTransition.value = to.meta.tab > from.meta.tab ? 'page-forward' : 'page-back'
  })
}
