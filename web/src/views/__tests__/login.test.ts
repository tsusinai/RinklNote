import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { useToast, type Toast } from '../../composables/useToast'
import Login from '../Login.vue'

/* W3c 硬伤回归：注册表单必须真正调用 /api/auth/register（此前注册分支复制了登录逻辑），
 * 且失败路径不得发生跳转。 */

function resp(status: number, ok: boolean, body: unknown) {
  return { status, ok, json: async () => body } as unknown as Response
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: Login },
      { path: '/console', component: { template: '<div />' } },
    ],
  })
}

async function mountLogin(router: ReturnType<typeof makeRouter>) {
  await router.push('/login')
  await router.isReady()
  return mount(Login, { global: { plugins: [createPinia(), router] } })
}

// 进入注册态并填入合法手机号/密码后提交
async function submitRegister(wrapper: Awaited<ReturnType<typeof mountLogin>>) {
  await wrapper.findAll('.toggle button')[1].trigger('click')
  await wrapper.find('form input[type="text"]').setValue('13800001234')
  await wrapper.find('form input[type="password"]').setValue('pwd123')
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

beforeEach(() => { localStorage.clear() })

describe('Login 注册硬伤修复', () => {
  it('注册成功：真正调用 register API 并自动登录进控制台', async () => {
    const router = makeRouter()
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url
      if (url.includes('/api/auth/register')) return resp(201, true, { userId: 9, token: 'reg-token' })
      if (url.includes('/api/auth/me')) {
        return resp(200, true, { id: 9, phone: '13800001234', qqNumber: null, qqOpenid: null, createdAt: null, aiDisabled: false })
      }
      return resp(404, false, { message: 'not found' })
    })
    const wrapper = await mountLogin(router)
    await submitRegister(wrapper)

    // register API 确实被调用，请求体为表单值
    const regCall = fetchSpy.mock.calls.find(([u]) => String(u).includes('/api/auth/register'))
    expect(regCall).toBeTruthy()
    expect(regCall![1]?.method).toBe('POST')
    expect(JSON.parse(String(regCall![1]?.body))).toEqual({ phone: '13800001234', password: 'pwd123' })
    // 服务端 201 返回 token → 自动登录（token 落地 + 跳转控制台）
    expect(localStorage.getItem('rkl_token')).toBe('reg-token')
    expect(router.currentRoute.value.path).toBe('/console')
  })

  it('注册失败（手机号已注册）：不跳转、不落 token、toast 报服务端文案', async () => {
    const router = makeRouter()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url
      if (url.includes('/api/auth/register')) return resp(409, false, { message: '该手机号已注册' })
      return resp(404, false, { message: 'not found' })
    })
    const toast = useToast()
    const wrapper = await mountLogin(router)
    await submitRegister(wrapper)

    // 失败路径：停留在登录页，token 未写入
    expect(router.currentRoute.value.path).toBe('/login')
    expect(localStorage.getItem('rkl_token')).toBeNull()
    // 错误提示优先展示服务端业务文案（HttpError.data.message）
    expect(toast.toasts.some((t: Toast) => t.kind === 'err' && t.msg.includes('该手机号已注册'))).toBe(true)
  })
})
