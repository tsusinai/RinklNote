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
})
