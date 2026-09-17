<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import {
  createField, resizeField, stepField, twinkleOf,
  type ParticleField, type Pointer,
} from '../utils/particleField'

/* 记账页粒子星空背景（零依赖手写 Canvas，数值引擎见 utils/particleField.ts）。
 * - fixed 全视口 + pointer-events:none + z-index:0：不拦截任何点击，
 *   页面内容（.page z-index:1）与卡片浮在星点之上
 * - 颜色运行时读主题令牌（--primary/--muted/--text），data-theme 属性变化
 *   或系统深浅变化时实时重取色（MutationObserver + matchMedia 双通道）
 * - prefers-reduced-motion: reduce → 只画一帧静态星空，不起循环、不响应指针
 * - document.hidden 暂停推进；组件卸载（切 tab）即销毁全部监听与 rAF 循环 */
const canvasRef = ref<HTMLCanvasElement | null>(null)

let ctx: CanvasRenderingContext2D | null = null
let field: ParticleField | null = null
let rafId = 0
let dpr = 1
let w = 0
let h = 0
let resizeTimer: ReturnType<typeof setTimeout> | undefined
let lastTs = 0
// 指针默认远在屏外（无斥力），首次 pointermove 后进入微互动
const pointer: Pointer = { x: -1e4, y: -1e4 }

/* reduced-motion 只判定一次（组件随 tab 切换重建，回到页面时会重新判定） */
function prefersReduced(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}
const reduced = prefersReduced()

/* ── 主题取色 ── */
let dark = false
let primaryRgb: readonly [number, number, number] = [126, 193, 252]
let accentRgb: readonly [number, number, number] = [107, 114, 128]
let themeMo: MutationObserver | undefined
let darkMq: MediaQueryList | undefined

function isDarkNow(): boolean {
  const t = document.documentElement.dataset.theme
  if (t === 'dark') return true
  if (t === 'light') return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

/** #RRGGBB → [r,g,b]；解析失败返回 null（保留上次取色，避免切换瞬间闪黑） */
function hexToRgb(hex: string): [number, number, number] | null {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim())
  if (!m) return null
  const n = parseInt(m[1], 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

function refreshPalette(): void {
  dark = isDarkNow()
  const cs = getComputedStyle(document.documentElement)
  primaryRgb = hexToRgb(cs.getPropertyValue('--primary')) ?? primaryRgb
  // 点缀色：暗色用近白（--text）星点，亮色用灰蓝（--muted）保可读
  accentRgb = hexToRgb(cs.getPropertyValue(dark ? '--text' : '--muted')) ?? accentRgb
  if (reduced) drawFrame() // 静态帧也要跟随主题重画
}
const onThemeAttrChange = () => refreshPalette()
const onSystemSchemeChange = () => refreshPalette()

/* ── 尺寸与绘制 ── */
function applySize(): void {
  const canvas = canvasRef.value
  if (!canvas || !ctx || !field) return
  dpr = Math.min(window.devicePixelRatio || 1, 2) // DPR 上限 2：省填充率
  w = window.innerWidth
  h = window.innerHeight
  canvas.width = Math.round(w * dpr)
  canvas.height = Math.round(h * dpr)
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  resizeField(field, w, h) // 按新面积增删粒子
  if (reduced) drawFrame()
}

// resize 防抖：连续拖拽窗口时不逐帧重建
const onResize = () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(applySize, 150) }
// 画布 fixed 全屏：clientX/Y 即画布 CSS 坐标
const onPointerMove = (e: PointerEvent) => { pointer.x = e.clientX; pointer.y = e.clientY }

function drawFrame(): void {
  if (!ctx || !field) return
  ctx.clearRect(0, 0, w, h)
  // alpha 区间按主题（加浓后）：亮色 0.45~0.8，暗色 0.5~0.9（星空感）
  const minA = dark ? 0.5 : 0.45
  const maxA = dark ? 0.9 : 0.8
  for (const p of field.particles) {
    const rgb = p.tone === 1 ? accentRgb : primaryRgb
    const a = minA + (maxA - minA) * p.glow * twinkleOf(p)
    ctx.beginPath()
    ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
    ctx.fillStyle = `rgba(${rgb[0]},${rgb[1]},${rgb[2]},${a.toFixed(3)})`
    ctx.fill()
  }
}

function tick(ts: number): void {
  rafId = requestAnimationFrame(tick)
  if (!field) return
  // dt 钳制 ≤50ms：切后台回来不出现大步长跳变
  const dt = lastTs ? Math.min((ts - lastTs) / 1000, 0.05) : 0.016
  lastTs = ts
  if (document.hidden) return // 页面隐藏暂停推进（rAF 本就不触发，双保险）
  stepField(field, dt, pointer)
  drawFrame()
}

onMounted(() => {
  const canvas = canvasRef.value
  ctx = canvas?.getContext('2d') ?? null
  if (!ctx || !canvas) return // jsdom 等无 2D 上下文环境直接静默退出
  field = createField(window.innerWidth, window.innerHeight)
  applySize()
  refreshPalette()
  window.addEventListener('resize', onResize)
  if (!reduced) {
    window.addEventListener('pointermove', onPointerMove)
    rafId = requestAnimationFrame(tick)
  }
  // 主题切换实时重取色：data-theme 属性（store 显式切换）+ 系统深浅变化
  themeMo = new MutationObserver(onThemeAttrChange)
  themeMo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  darkMq = window.matchMedia('(prefers-color-scheme: dark)')
  darkMq.addEventListener?.('change', onSystemSchemeChange)
})

onUnmounted(() => {
  cancelAnimationFrame(rafId)
  clearTimeout(resizeTimer)
  window.removeEventListener('resize', onResize)
  window.removeEventListener('pointermove', onPointerMove)
  darkMq?.removeEventListener?.('change', onSystemSchemeChange)
  themeMo?.disconnect()
  themeMo = undefined
  field = null
  ctx = null
})
</script>

<template>
  <!-- Teleport 到 body：页面转场会给 .page 加 transform，祖先带 transform 时
       position:fixed 会退化为相对该祖先定位并被 .content 裁剪；挂到 body 后
       画布始终视口定位，转场期间星空保持不动。 -->
  <!-- aria-hidden：纯装饰背景，读屏与交互一律忽略 -->
  <Teleport to="body">
    <canvas ref="canvasRef" class="particle-bg" aria-hidden="true"></canvas>
  </Teleport>
</template>

<style scoped>
.particle-bg {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 0;
}
</style>
