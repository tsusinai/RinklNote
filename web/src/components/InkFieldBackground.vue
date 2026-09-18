<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import {
  INK_PARAMS, inkTierParams, bufferHeight, sampleInkField, composePixel, hexToRgb,
} from '../utils/inkField'
import { FrameQualityMonitor, createVisibilityController } from '../utils/perfTier'
import { prefersReducedMotion } from '../utils/motion'

/* Landing 墨晕热力场背景（Task 3.6，骨架镜像 ParticleBackground.vue）。
 * - Teleport body + fixed + pointer-events:none：不拦截任何交互（无指针交互、无滚动视差，
 *   与记账页斥力星空形成「门面静、工作台动」对照）
 * - 渲染：离屏低清缓冲（默认 192px 宽、随视口比例）逐像素 ImageData → drawImage 平滑放大铺满；
 *   DPR 无关（雾化模糊是特性）；30fps 隔帧 + dt 钳制
 * - 墨色运行时读 --primary-ink（亮 #285c82 / 暗 #a5d8ff），data-theme 属性变化或系统深浅
 *   变化时实时重取色（MutationObserver + matchMedia 双通道）
 * - prefers-reduced-motion → 单帧静图不起循环；页面隐藏暂停；resize 防抖重建缓冲
 * - 帧率降档复用 perfTier 可复用模块：连续 <45fps → 缓冲减半、30→20→15fps（只降不升） */
const canvasRef = ref<HTMLCanvasElement | null>(null)

let ctx: CanvasRenderingContext2D | null = null
let buf: HTMLCanvasElement | null = null // 离屏低清缓冲
let bufCtx: CanvasRenderingContext2D | null = null
let bufW = 0
let bufH = 0
let rafId = 0
let viewW = 0
let viewH = 0
let resizeTimer: ReturnType<typeof setTimeout> | undefined
let lastTs = 0
let acc = 0 // 帧间隔累计（隔帧节流）
let simT = 0 // 墨场累计漂移时间（秒）

/* 档位状态：帧率监测降档 → 重建低清缓冲并放宽目标帧间隔 */
let tier: 0 | 1 | 2 = 0
const perfMonitor = new FrameQualityMonitor()
let visibility: { stop: () => void } | null = null

/* ── 主题取色：墨色读 --primary-ink ── */
let dark = false
let inkRgb: readonly [number, number, number] = [40, 92, 130]
let themeMo: MutationObserver | undefined
let darkMq: MediaQueryList | undefined

function isDarkNow(): boolean {
  const t = document.documentElement.dataset.theme
  if (t === 'dark') return true
  if (t === 'light') return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

function refreshPalette(): void {
  dark = isDarkNow()
  const cs = getComputedStyle(document.documentElement)
  inkRgb = hexToRgb(cs.getPropertyValue('--primary-ink')) ?? inkRgb
  if (reduced) renderFrame() // 静态帧也要跟随主题重画
}
const onThemeAttrChange = () => refreshPalette()
const onSystemSchemeChange = () => refreshPalette()

/* reduced-motion 单帧静图（组件随路由重建，回到页面时重新判定） */
const reduced = prefersReducedMotion()

/* ── 缓冲与渲染 ── */
function rebuildBuffer(): void {
  const { bufferWidth } = inkTierParams(tier)
  bufW = bufferWidth
  bufH = bufferHeight(bufferWidth, viewW, viewH)
  if (!buf) {
    buf = document.createElement('canvas')
    bufCtx = buf.getContext('2d')
  }
  if (buf) {
    buf.width = bufW
    buf.height = bufH
  }
}

/** 渲染一帧：逐像素采样墨场 → ImageData → drawImage 平滑放大铺满 */
function renderFrame(): void {
  if (!ctx || !bufCtx || !buf || !bufW || !bufH) return
  const img = bufCtx.createImageData(bufW, bufH)
  const px = img.data
  const kx = viewW / bufW // 缓冲像素 → 视口 CSS 坐标
  const ky = viewH / bufH
  let i = 0
  for (let by = 0; by < bufH; by++) {
    const y = (by + 0.5) * ky
    for (let bx = 0; bx < bufW; bx++) {
      const x = (bx + 0.5) * kx
      const s = sampleInkField(x, y, simT, INK_PARAMS)
      const [r, g, b, a] = composePixel(s, inkRgb, dark, INK_PARAMS)
      px[i++] = r
      px[i++] = g
      px[i++] = b
      px[i++] = Math.round(a * 255)
    }
  }
  bufCtx.putImageData(img, 0, 0)
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.clearRect(0, 0, viewW, viewH)
  ctx.drawImage(buf, 0, 0, viewW, viewH)
}

function tick(ts: number): void {
  rafId = requestAnimationFrame(tick)
  // dt 钳制 ≤100ms：切后台回来不出现大步长跳变
  const dt = lastTs ? Math.min(ts - lastTs, 100) : 16.7
  lastTs = ts
  acc += dt
  const { frameIntervalMs } = inkTierParams(tier)
  if (acc < frameIntervalMs) return // 隔帧节流：60Hz 显示器上每两帧渲染一次（≈30fps）
  simT += acc / 1000
  acc = 0
  // 帧率监测：连续慢帧 → 降档（缓冲减半、帧率下调，只降不升）
  const decision = perfMonitor.push(dt)
  if (decision.changed && decision.tier !== tier) {
    tier = decision.tier
    rebuildBuffer()
  }
  renderFrame()
}

function applySize(): void {
  viewW = window.innerWidth
  viewH = window.innerHeight
  rebuildBuffer()
  if (reduced) renderFrame()
}

// resize 防抖：连续拖拽窗口时不逐帧重建
const onResize = () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(applySize, 150) }

function pauseLoop(): void {
  cancelAnimationFrame(rafId)
  rafId = 0
}
function resumeLoop(): void {
  if (reduced || rafId) return
  lastTs = 0
  acc = 0
  rafId = requestAnimationFrame(tick)
}

onMounted(() => {
  const canvas = canvasRef.value
  ctx = canvas?.getContext('2d') ?? null
  if (!ctx || !canvas) return // jsdom 等无 2D 上下文环境直接静默退出
  applySize()
  refreshPalette()
  window.addEventListener('resize', onResize)
  if (!reduced) rafId = requestAnimationFrame(tick)
  // 主题切换实时重取色：data-theme 属性（store 显式切换）+ 系统深浅变化
  themeMo = new MutationObserver(onThemeAttrChange)
  themeMo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  darkMq = window.matchMedia('(prefers-color-scheme: dark)')
  darkMq.addEventListener?.('change', onSystemSchemeChange)
  // 页面隐藏暂停 / 回前台恢复（perfTier 可复用控制器）
  visibility = createVisibilityController(pauseLoop, resumeLoop)
})

onUnmounted(() => {
  pauseLoop()
  clearTimeout(resizeTimer)
  window.removeEventListener('resize', onResize)
  darkMq?.removeEventListener?.('change', onSystemSchemeChange)
  themeMo?.disconnect()
  themeMo = undefined
  visibility?.stop()
  visibility = null
  buf = null
  bufCtx = null
  ctx = null
})
</script>

<template>
  <!-- Teleport 到 body：fixed 全视口铺底；aria-hidden 纯装饰，读屏与交互一律忽略 -->
  <Teleport to="body">
    <canvas ref="canvasRef" class="ink-field" aria-hidden="true"></canvas>
  </Teleport>
</template>

<style scoped>
.ink-field {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: -1;
}
</style>
