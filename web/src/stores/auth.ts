import { defineStore } from 'pinia'
import { auth } from '../api/auth'
import { setToken, getToken, clearToken } from '../api/http'
import type { MeResponse } from '../types'

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: getToken() as string | null, user: null as MeResponse | null, ready: false }),
  actions: {
    async login(phone: string, password: string) {
      const r = await auth.login(phone, password)
      if (!r.token) throw new Error((r as any).message || '登录失败')
      this.token = r.token; setToken(r.token); this.ready = true
      await this.refresh()
    },
    /* 注册（修 W3c 硬伤：此前注册表单从未调过 register API）。
     * 服务端 POST /api/auth/register 成功返回 201 + AuthResponse(userId, token)（AuthRoutes.kt），
     * 与 login 同构 → 注册成功即自动登录进控制台。 */
    async register(phone: string, password: string) {
      const r = await auth.register(phone, password)
      if (!r.token) throw new Error((r as any).message || '注册失败')
      this.token = r.token; setToken(r.token); this.ready = true
      await this.refresh()
    },
    async loginByQq(code: string) {
      const r = await auth.qqLogin(code)
      if (!r.token) throw new Error((r as any).message || 'QQ 登录失败')
      this.token = r.token; setToken(r.token); this.ready = true
      await this.refresh()
    },
    async refresh() { if (this.token) this.user = await auth.me() },
    logout() { this.token = null; this.user = null; this.ready = false; clearToken() },
    async hydrate() { if (this.token) { this.ready = true; try { await this.refresh() } catch { this.logout() } } },
  },
})
