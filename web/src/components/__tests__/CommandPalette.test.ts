import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import CommandPalette from '../CommandPalette.vue'
import { COMMAND_ACTIONS } from '../../utils/commands'

/* 命令面板组件组合单测（Task 3.4）：过滤渲染、Enter 执行路由跳转、Esc 关闭、↑↓ 选中。 */

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/c/bookkeeping' },
      { path: '/c/bookkeeping', name: 'bookkeeping', component: { template: '<div/>' } },
      { path: '/c/charts', name: 'charts', component: { template: '<div/>' } },
      { path: '/c/bills', name: 'bills', component: { template: '<div/>' } },
    ],
  })
}

function mountPalette(open = true) {
  const router = makeRouter()
  const wrapper = mount(CommandPalette, {
    props: { open },
    global: { plugins: [createPinia(), router] },
    attachTo: document.body, // Teleport 到 body 的内容需要真实挂载才能查询
  })
  return { wrapper, router }
}

beforeEach(() => { localStorage.clear() })

describe('CommandPalette 命令面板', () => {
  it('open=false 不渲染任何内容', () => {
    mountPalette(false)
    expect(document.querySelector('.palette-overlay')).toBeNull()
  })
  it('打开后渲染全部命令，输入查询即过滤', async () => {
    const { wrapper } = mountPalette()
    await flushPromises()
    expect(document.querySelectorAll('.palette-item')).toHaveLength(COMMAND_ACTIONS.length)
    const input = document.querySelector<HTMLInputElement>('.palette-input')!
    input.value = '图表'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    const items = document.querySelectorAll('.palette-item')
    expect(items).toHaveLength(1)
    expect(items[0].textContent).toContain('图表分析')
    wrapper.unmount()
  })
  it('Enter 执行选中命令 → 路由跳转并 emit close', async () => {
    const { wrapper, router } = mountPalette()
    await flushPromises()
    await router.push({ name: 'bookkeeping' })
    const palette = document.querySelector('.palette')!
    palette.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('bookkeeping')
    expect(wrapper.emitted('close')).toBeTruthy()
    wrapper.unmount()
  })
  it('Esc 仅关闭面板', async () => {
    const { wrapper, router } = mountPalette()
    await flushPromises()
    await router.push({ name: 'bookkeeping' })
    const palette = document.querySelector('.palette')!
    palette.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(wrapper.emitted('close')).toBeTruthy()
    expect(router.currentRoute.value.name).toBe('bookkeeping') // 未跳转
    wrapper.unmount()
  })
  it('ArrowDown 在列表末尾环形回绕到首项', async () => {
    const { wrapper } = mountPalette()
    await flushPromises()
    const palette = document.querySelector('.palette')!
    palette.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await flushPromises()
    // 初始选中 0，↓ 后为 1
    const items = document.querySelectorAll('.palette-item')
    expect(items[1].classList.contains('on')).toBe(true)
    wrapper.unmount()
  })

  it('IME 组合期（isComposing）Enter 提交的是候选词，不得执行命令也不得关闭面板', async () => {
    const { wrapper, router } = mountPalette()
    await flushPromises()
    await router.push({ name: 'bookkeeping' })
    // 取最后一个挂载的面板（失败用例遗留 DOM 时避免误投递到旧元素）
    const palette = [...document.querySelectorAll('.palette')].pop()!
    const ev = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })
    Object.defineProperty(ev, 'isComposing', { value: true }) // jsdom 构造器不支持该字段
    palette.dispatchEvent(ev)
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('bookkeeping') // 未跳转
    expect(wrapper.emitted('close')).toBeFalsy() // 面板未关
    wrapper.unmount()
  })

  it('IME 组合期（keyCode 229，Safari 口径）Enter/Esc 均不触发面板行为', async () => {
    const { wrapper, router } = mountPalette()
    await flushPromises()
    await router.push({ name: 'bookkeeping' })
    const palette = [...document.querySelectorAll('.palette')].pop()!
    const enter229 = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })
    Object.defineProperty(enter229, 'keyCode', { value: 229 })
    palette.dispatchEvent(enter229)
    const esc229 = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })
    Object.defineProperty(esc229, 'keyCode', { value: 229 })
    palette.dispatchEvent(esc229)
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('bookkeeping')
    expect(wrapper.emitted('close')).toBeFalsy()
    wrapper.unmount()
  })

  it('IME 组合期 ArrowUp/Down 在候选词间移动，不移动面板游标', async () => {
    const { wrapper } = mountPalette()
    await flushPromises()
    const palette = [...document.querySelectorAll('.palette')].pop()!
    const ev = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true })
    Object.defineProperty(ev, 'isComposing', { value: true })
    palette.dispatchEvent(ev)
    await flushPromises()
    const items = palette.querySelectorAll('.palette-item')
    expect(items[0].classList.contains('on')).toBe(true) // 游标仍在首项
    wrapper.unmount()
  })

  it('打开后焦点守卫：焦点被移出面板（点击非可聚焦区落到 body）时拉回过滤框', async () => {
    // 生产路径是 open:false → true 切换触发 watch 聚焦，这里同样用切换复现
    const router = makeRouter()
    const wrapper = mount(CommandPalette, {
      props: { open: false },
      global: { plugins: [createPinia(), router] },
      attachTo: document.body,
    })
    await wrapper.setProps({ open: true })
    await flushPromises()
    const input = document.querySelector<HTMLInputElement>('.palette-input')!
    expect(document.activeElement).toBe(input) // 打开即聚焦

    // 模拟点击面板内非可聚焦区域（面板头/底注）→ 焦点落到 body
    input.blur()
    await flushPromises()
    // 焦点必须被拉回过滤框：否则数字键/Enter/Esc 会穿透到底下的路由页
    // （记账页数字直输改金额、Enter 直接提交账单），Esc 也不再能关面板
    expect(document.activeElement).toBe(input)
    wrapper.unmount()
  })
})
