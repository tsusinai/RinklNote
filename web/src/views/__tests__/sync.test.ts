import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SyncCard from '../console/settings/SyncCard.vue'
import { useDataStore } from '../../stores/data'
import { useAuthStore } from '../../stores/auth'
import { useToast } from '../../composables/useToast'

/* Task 4：同步卡片状态单测（Web 的「同步」= 从云端刷新数据，不伪装 App 的待上传队列）。
 * 用真实 pinia store + mock fetch 走通 SyncCard 的完整状态流转。 */

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.restoreAllMocks())

/** 登录态下全资源成功的 fetch mock */
function okFetch() {
  return vi.fn(async (url: string) => {
    if (url.includes('/sync')) return { status: 200, ok: true, json: async () => ({ bills: [], hasMore: false, nextAfter: null, nextAfterId: null }) }
    return { status: 200, ok: true, json: async () => [] }
  })
}

describe('SyncCard 同步状态卡（Task 4）', () => {
  it('初始态：显示「尚未同步 / 从未」，语义说明为「从云端刷新」，不伪装待上传队列', () => {
    const wrapper = mount(SyncCard)
    const text = wrapper.text()
    expect(text).toContain('尚未同步')
    expect(text).toContain('从未')
    expect(text).toContain('从云端刷新')
    expect(text).not.toContain('待上传')
    expect(wrapper.find('button').text()).toContain('立即同步')
    wrapper.unmount()
  })

  it('同步成功：状态「已同步」+ 上次成功时间更新 + 成功提示，无失败详情块', async () => {
    useAuthStore().token = 't'
    globalThis.fetch = okFetch() as any
    const wrapper = mount(SyncCard)
    await wrapper.find('button').trigger('click')
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('已同步')
    expect(text).not.toContain('从未')
    expect(text).not.toContain('失败原因')
    expect(useToast().toasts.some((t) => t.msg === '同步完成')).toBe(true)
    const s = useDataStore()
    expect(s.syncStatus).toBe('success')
    expect(s.lastSync).toBeGreaterThan(0)
    wrapper.unmount()
  })

  it('部分失败：展示失败原因与受影响资源 + 本地数据可继续用提示 + 重试按钮；失败分类保留旧数据', async () => {
    useAuthStore().token = 't'
    const s = useDataStore()
    s.cats = [{ id: 9, name: '旧分类', type: 'EXPENSE', iconName: '', subCategories: [] }]
    globalThis.fetch = vi.fn(async (url: string) => {
      if (url.includes('/categories')) return { status: 502, ok: false, json: async () => '<html>' }
      if (url.includes('/sync')) return { status: 200, ok: true, json: async () => ({ bills: [], hasMore: false, nextAfter: null, nextAfterId: null }) }
      return { status: 200, ok: true, json: async () => [] }
    }) as any
    const wrapper = mount(SyncCard)
    await wrapper.find('button').trigger('click')
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('部分资源同步失败')
    expect(text).toContain('服务暂时不可用，请稍后重试') // 失败原因 = http.ts 稳定文案
    expect(text).toContain('受影响资源：分类')
    expect(text).toContain('页面内容仍可正常查看与记账') // 可继续使用本地已有数据的提示
    expect(wrapper.find('button').text()).toContain('重试')
    expect(s.cats[0]?.name).toBe('旧分类') // 失败资源未被静默置空
    wrapper.unmount()
  })

  it('网络不可用：状态「网络不可用」，点重试会再次触发 loadData', async () => {
    useAuthStore().token = 't'
    globalThis.fetch = vi.fn(async () => { throw new TypeError('Failed to fetch') }) as any
    const wrapper = mount(SyncCard)
    const data = useDataStore()
    const spy = vi.spyOn(data, 'loadData')
    await wrapper.find('button').trigger('click') // 第 1 次：立即同步
    await flushPromises()
    expect(wrapper.text()).toContain('网络不可用')
    expect(wrapper.text()).toContain('网络不可用，请检查网络后重试')
    await wrapper.find('button').trigger('click') // 第 2 次：重试
    await flushPromises()
    expect(spy).toHaveBeenCalledTimes(2)
    expect(data.syncStatus).toBe('offline')
    wrapper.unmount()
  })

  it('401（已登录态）：状态「需要重新登录」+「去登录」入口，不被硬跳转劫持', async () => {
    // 真实场景是已登录但 token 失效：allow401 让 401 走 HttpError 进状态卡，
    // 而不是在 api() 内部被清 token + window.location 硬跳转导致状态不可见。
    useAuthStore().token = 't'
    globalThis.fetch = vi.fn(async () => ({ status: 401, ok: false, json: async () => ({}) })) as any
    const wrapper = mount(SyncCard)
    await wrapper.find('button').trigger('click')
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('需要重新登录')
    expect(text).toContain('登录已过期，请重新登录')
    expect(wrapper.find('button').text()).toContain('去登录')
    wrapper.unmount()
  })
})
