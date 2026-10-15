import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory, RouterView, type Router } from 'vue-router'
import BotInteraction from '../BotInteraction.vue'
import { authGuard } from '../../router/guards'
import { useAuthStore } from '../../stores/auth'
import type { InteractionView } from '../../api/botInteraction'

const billView = (): InteractionView => ({
  kind: 'bill', title: '🧾 这笔账单', text: '可以修改金额、分类或撤销。', expiresAt: Date.now() + 60_000, consumed: false,
  bill: { id: 4, amountMinor: 1800, categoryId: 1, categoryName: '餐饮', billType: 'EXPENSE', date: 1_792_012_800_000, accountName: '无账户', updatedAt: 100 },
  categories: [{ id: 1, name: '餐饮' }, { id: 2, name: '交通' }],
})

const budgetView = (): InteractionView => ({
  kind: 'budget', title: '📊 本月预算', text: '餐饮：¥200.00\n交通：¥80.00', expiresAt: Date.now() + 60_000, consumed: false,
  budget: { budgetMinor: 100000, spentMinor: 28000, remainingMinor: 72000, enabled: true, hour: 9, minute: 5 },
})

function response(status: number, body: unknown) {
  return { status, ok: status >= 200 && status < 300, json: async () => body } as Response
}

const wrappers: ReturnType<typeof mount>[] = []
const routers: Router[] = []
let activeRouter: Router
async function openPage() {
  const router = createRouter({ history: createWebHistory(), routes: [{ path: '/bot/interaction', component: BotInteraction }] })
  routers.push(router)
  activeRouter = router
  router.beforeEach(authGuard)
  await router.push(window.location.pathname + window.location.search + window.location.hash)
  await router.isReady()
  const wrapper = mount(RouterView, { global: { plugins: [createPinia(), router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/bot/interaction#token=short-lived-token')
})
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  routers.splice(0).forEach(router => router.options.history.destroy())
  vi.useRealTimers()
})

describe('小盘限时交互页', () => {
  it('独立入口不刷新现有登录态', async () => {
    setActivePinia(createPinia())
    const store = useAuthStore()
    store.token = 'existing-login'; store.ready = false
    const hydrate = vi.spyOn(store, 'hydrate')
    expect(await authGuard({ path: '/bot/interaction' })).toBe(true)
    expect(hydrate).not.toHaveBeenCalled()
  })

  it('从 fragment 取票据后清理地址，只发送票据头，不使用 JWT 或缓存', async () => {
    localStorage.setItem('rkl_token', 'existing-login')
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(response(200, billView()))
    const wrapper = await openPage()
    expect(window.location.hash).toBe('')
    expect(window.history.state.current).toBe('/bot/interaction')
    expect(activeRouter.currentRoute.value.fullPath).toBe('/bot/interaction')
    expect(wrapper.text()).toContain('¥18.00')
    const [url, options] = fetchSpy.mock.calls[0]
    expect(url).toBe('/api/bot/interaction')
    expect(options).toMatchObject({ method: 'GET', headers: { 'X-Bot-Action': 'short-lived-token' }, credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer' })
    expect((options?.headers as Record<string, string>).Authorization).toBeUndefined()
    expect(localStorage.length).toBe(1)
    expect(localStorage.getItem('rkl_token')).toBe('existing-login')
    expect(sessionStorage.length).toBe(0)
  })

  it('同路由打开新票据时复用页面，重新清理路由并使用新票据提交', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(response(200, billView()))
      .mockResolvedValueOnce(response(200, { ...billView(), bill: { ...billView().bill!, id: 5, amountMinor: 2200 } }))
      .mockResolvedValueOnce(response(200, { ...billView(), consumed: true, message: '✅ 新账单已修改' }))
    const wrapper = await openPage()
    const originalElement = wrapper.get('.bot-page').element
    await activeRouter.push('/bot/interaction#token=second-token')
    await flushPromises()
    expect(wrapper.get('.bot-page').element).toBe(originalElement)
    expect(wrapper.text()).toContain('¥22.00')
    expect(activeRouter.currentRoute.value.fullPath).toBe('/bot/interaction')
    expect(window.history.state.current).toBe('/bot/interaction')
    expect(window.location.hash).toBe('')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(fetchSpy.mock.calls.map(([, options]) => (options?.headers as Record<string, string>)['X-Bot-Action']))
      .toEqual(['short-lived-token', 'second-token', 'second-token'])
  })

  it('新入口加载后旧请求晚到不会覆盖当前账单', async () => {
    let completeOld!: (response: Response) => void
    vi.spyOn(globalThis, 'fetch').mockImplementationOnce(() => new Promise(resolve => { completeOld = resolve }))
      .mockResolvedValueOnce(response(200, { ...billView(), bill: { ...billView().bill!, id: 5, amountMinor: 2200 } }))
    const wrapper = await openPage()
    await activeRouter.push('/bot/interaction#token=second-token')
    await flushPromises()
    completeOld(response(200, billView()))
    await flushPromises()
    expect(wrapper.text()).toContain('¥22.00')
    expect(wrapper.get('#bill-amount').element).toHaveProperty('value', '22.00')
  })

  it('GET 连接超时中止请求并允许重试', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementationOnce(() => new Promise(() => {}))
      .mockResolvedValueOnce(response(200, billView()))
    const wrapper = await openPage()
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(wrapper.text()).toContain('请求超时')
    expect(fetchSpy.mock.calls[0][1]?.signal?.aborted).toBe(true)
    await wrapper.get('.bot-error button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('¥18.00')
    expect(fetchSpy.mock.calls.map(([, options]) => options?.method)).toEqual(['GET', 'GET'])
  })

  it('GET 正文读取也受同一超时保护', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const hangingBody = { status: 200, ok: true, json: () => new Promise(() => {}) } as Response
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(hangingBody)
    const wrapper = await openPage()
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(wrapper.text()).toContain('请求超时')
    expect(fetchSpy.mock.calls[0][1]?.signal?.aborted).toBe(true)
    expect(wrapper.get('.bot-error button').attributes('disabled')).toBeUndefined()
  })

  it('POST 超时禁重复提交，只通过 GET 查询操作结果', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response(200, billView()))
      .mockImplementationOnce(() => new Promise(() => {}))
      .mockResolvedValueOnce(response(200, { ...billView(), consumed: true, message: '✅ 已修改' }))
    const wrapper = await openPage()
    await wrapper.get('form').trigger('submit')
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(wrapper.text()).toContain('操作结果暂未确认')
    expect(fetchSpy.mock.calls[1][1]?.signal?.aborted).toBe(true)
    await wrapper.get('form').trigger('submit')
    expect(fetchSpy).toHaveBeenCalledTimes(2)
    await wrapper.get('.bot-error button').trigger('click')
    await flushPromises()
    expect(fetchSpy.mock.calls.map(([, options]) => options?.method)).toEqual(['GET', 'POST', 'GET'])
    expect(wrapper.text()).toContain('已修改')
  })

  it('无票据时显示重新打开提示，不发请求', async () => {
    window.history.replaceState({}, '', '/bot/interaction')
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const wrapper = await openPage()
    expect(wrapper.text()).toContain('入口已失效')
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('保存金额与分类使用整数分，完成后禁止继续修改', async () => {
    const result = { ...billView(), consumed: true, message: '✅ 已修改', bill: { ...billView().bill!, amountMinor: 5797, categoryId: 2, categoryName: '交通' } }
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response(200, billView())).mockResolvedValueOnce(response(200, result))
    const wrapper = await openPage()
    await wrapper.get('#bill-amount').setValue('57.97')
    await wrapper.get('#bill-category').setValue('2')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(JSON.parse(String(fetchSpy.mock.calls[1][1]?.body))).toEqual({ operation: 'save', amountMinor: 5797, categoryId: 2 })
    expect(wrapper.text()).toContain('¥57.97')
    expect(wrapper.text()).toContain('已修改')
    expect(wrapper.get('fieldset').attributes()).toHaveProperty('disabled')
    await wrapper.get('form').trigger('submit')
    expect(fetchSpy).toHaveBeenCalledTimes(2)
  })

  it.each(['0', '0.001', '-2', 'NaN', '900719925474099.12'])('拒绝无效金额 %s', async (input) => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(response(200, billView()))
    const wrapper = await openPage()
    await wrapper.get('#bill-amount').setValue(input)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请输入大于 0 的金额')
    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })

  it('撤销具体账单先确认，取消不提交，确认只发一次', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response(200, billView())).mockResolvedValueOnce(response(200, { ...billView(), consumed: true, message: '✅ 这笔账单已撤销' }))
    const wrapper = await openPage()
    await wrapper.get('.undo').trigger('click')
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    await wrapper.get('.button-row .secondary').trigger('click')
    expect(wrapper.find('.undo-confirm').exists()).toBe(false)
    await wrapper.get('.undo').trigger('click')
    await wrapper.get('.danger').trigger('click')
    await flushPromises()
    expect(JSON.parse(String(fetchSpy.mock.calls[1][1]?.body))).toEqual({ operation: 'undo' })
    expect(wrapper.text()).toContain('这笔账单已撤销')
  })

  it('提交未结束时不允许重复修改', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response(200, billView())).mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = await openPage()
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(fetchSpy).toHaveBeenCalledTimes(2)
    expect(wrapper.get('fieldset').attributes()).toHaveProperty('disabled')
  })

  it('网络中断后先查询结果，查询也失败时仍禁止重复提交', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response(200, billView())).mockRejectedValueOnce(new Error('disconnect')).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(response(200, { ...billView(), consumed: true, message: '✅ 已修改' }))
    const wrapper = await openPage()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('操作结果暂未确认')
    expect(wrapper.get('fieldset').attributes()).toHaveProperty('disabled')
    await wrapper.get('.bot-error button').trigger('click')
    await flushPromises()
    expect(wrapper.get('fieldset').attributes()).toHaveProperty('disabled')
    await wrapper.get('.bot-error button').trigger('click')
    await flushPromises()
    expect(fetchSpy.mock.calls.map(([, options]) => options?.method)).toEqual(['GET', 'POST', 'GET', 'GET'])
    expect(wrapper.text()).toContain('已修改')
  })

  it.each([401, 403, 410, 409])('HTTP %s 入口错误保持原登录态并锁定提交', async (status) => {
    localStorage.setItem('rkl_token', 'keep-login')
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response(200, billView())).mockResolvedValueOnce(response(status, { message: status === 409 ? '版本冲突，请重新打开' : '入口已失效' }))
    const wrapper = await openPage()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain(status === 409 ? '版本冲突' : '入口已失效')
    expect(wrapper.get('fieldset').attributes()).toHaveProperty('disabled')
    expect(localStorage.getItem('rkl_token')).toBe('keep-login')
    expect(window.location.pathname).toBe('/bot/interaction')
    await wrapper.get('form').trigger('submit')
    expect(fetchSpy).toHaveBeenCalledTimes(2)
  })

  it('过期票据可查看已有信息但不能提交', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(response(200, { ...billView(), expiresAt: Date.now() - 1 }))
    const wrapper = await openPage()
    expect(wrapper.text()).toContain('入口已过期')
    await wrapper.get('form').trigger('submit')
    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })

  it('预算明细与提醒开关、时间可查看并保存', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response(200, budgetView())).mockResolvedValueOnce(response(200, { ...budgetView(), consumed: true, message: '✅ 日报提醒已关闭' }))
    const wrapper = await openPage()
    expect(wrapper.text()).toContain('¥1,000.00')
    expect(wrapper.text()).toContain('¥280.00')
    expect(wrapper.text()).toContain('¥720.00')
    expect(wrapper.get('details').text()).toContain('交通：¥80.00')
    expect((wrapper.get('#reminder-time').element as HTMLInputElement).value).toBe('09:05')
    await wrapper.get('#reminder-enabled').setValue(false)
    await wrapper.get('#reminder-time').setValue('21:30')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(JSON.parse(String(fetchSpy.mock.calls[1][1]?.body))).toEqual({ operation: 'reminders', enabled: false, hour: 21, minute: 30 })
    expect(wrapper.text()).toContain('日报提醒已关闭')
  })

  it('未设置预算时不把空值格式化成零元', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(response(200, { ...budgetView(), budget: { ...budgetView().budget!, budgetMinor: null, remainingMinor: null } }))
    const wrapper = await openPage()
    expect(wrapper.text()).toContain('尚未设置')
    expect(wrapper.get('.budget-summary').text()).not.toContain('¥0.00')
  })
})
