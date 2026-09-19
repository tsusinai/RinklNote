import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import InkFieldBackground from '../InkFieldBackground.vue'

/* InkFieldBackground 组件级单测：
 * 可见画布的**位图尺寸**必须随视口设置（applySize 里 canvas.width/height = 视口 CSS 像素）。
 * 位图若停留在浏览器默认 300×150，renderFrame 的 drawImage 目标矩形（viewW×viewH）
 * 会被裁剪到左上角一角，再被 CSS 拉伸铺满 —— 画面只显示墨场左上角且纵横比失真。 */

function stubContext2D() {
  return {
    // renderFrame 用到的最小接口
    createImageData: (w: number, h: number) => ({
      width: w, height: h, data: new Uint8ClampedArray(w * h * 4),
    }),
    putImageData: vi.fn(),
    clearRect: vi.fn(),
    drawImage: vi.fn(),
  }
}

let ctx2d: ReturnType<typeof stubContext2D>

beforeEach(() => {
  ctx2d = stubContext2D()
  // jsdom 无 2D 上下文与 matchMedia，统一打桩
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
    .mockReturnValue(ctx2d as unknown as CanvasRenderingContext2D)
  ;(window as any).matchMedia = vi.fn().mockReturnValue({
    matches: false, media: '', onchange: null,
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(),
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  delete (window as any).matchMedia
})

describe('InkFieldBackground 可见画布位图尺寸', () => {
  it('挂载后 canvas 位图尺寸 = 视口 CSS 像素（而非默认 300×150）', async () => {
    window.innerWidth = 1280
    window.innerHeight = 800
    const wrapper = mount(InkFieldBackground, { attachTo: document.body })
    await wrapper.vm.$nextTick()
    const canvas = document.querySelector('canvas.ink-field') as HTMLCanvasElement
    expect(canvas).toBeTruthy()
    expect(canvas.width).toBe(1280)
    expect(canvas.height).toBe(800)
    wrapper.unmount()
  })

  it('resize 防抖（150ms）后位图跟随新视口尺寸重建', async () => {
    vi.useFakeTimers()
    try {
      window.innerWidth = 1280
      window.innerHeight = 800
      const wrapper = mount(InkFieldBackground, { attachTo: document.body })
      await wrapper.vm.$nextTick()
      window.innerWidth = 500
      window.innerHeight = 900
      window.dispatchEvent(new Event('resize'))
      vi.advanceTimersByTime(200)
      const canvas = document.querySelector('canvas.ink-field') as HTMLCanvasElement
      expect(canvas.width).toBe(500)
      expect(canvas.height).toBe(900)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })
})
