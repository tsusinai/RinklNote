export class HttpError extends Error {
  statusCode: number
  data: unknown
  constructor(status: number, message?: string, data?: unknown) {
    super(message ?? `HTTP ${status}`)
    this.statusCode = status
    this.data = data
  }
}

const TOKEN_KEY = 'rkl_token'
// 只返回「有效」token。历史上登录失败可能把字面 'undefined'/'null' 写进 localStorage，
// 这类垃圾值若不归一为 null 会被守卫误判为「已登录」，导致未登录也能进入 /console。
export function getToken(): string | null {
  const t = localStorage.getItem(TOKEN_KEY)
  return t && t !== 'undefined' && t !== 'null' ? t : null
}
export function setToken(t: string): void { localStorage.setItem(TOKEN_KEY, t) }
export function clearToken(): void { localStorage.removeItem(TOKEN_KEY) }

interface ApiOptions {
  method?: string
  body?: unknown
  headers?: Record<string, string>
  query?: Record<string, string | number | undefined>
  allow401?: boolean   // 新增
}

export async function api<T = any>(path: string, opts: ApiOptions = {}): Promise<T> {
  const token = getToken()
  const h: Record<string, string> = { 'Content-Type': 'application/json', ...opts.headers }
  if (token) h['Authorization'] = 'Bearer ' + token

  let url = path
  if (opts.query) {
    const q = new URLSearchParams()
    Object.entries(opts.query).forEach(([k, v]) => { if (v !== undefined) q.set(k, String(v)) })
    const qs = q.toString()
    if (qs) url += (url.includes('?') ? '&' : '?') + qs
  }

  const r = await fetch(url, {
    method: opts.method ?? 'GET',
    headers: h,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })

  if (r.status === 401 && token) {
    if (opts.allow401) {
      let body: unknown
      try { body = await r.json() } catch { /* 无 body */ }
      throw new HttpError(401, (body as { message?: string } | null)?.message ?? 'HTTP 401', body)
    }
    clearToken()
    // 401 掉登录：带当前地址回登录页，登录成功后原路返回（Login.vue 读取 redirect）；
    // 本就在登录页时不带参数，避免回跳到 /login 自身
    const here = window.location.pathname + window.location.search + window.location.hash
    window.location.href = here.startsWith('/login') ? '/login' : '/login?redirect=' + encodeURIComponent(here)
    return r.json() as Promise<T>
  }
  if (r.status === 409) {
    let body: unknown
    try { body = await r.json() } catch { /* 无 body */ }
    throw new HttpError(409, '并发冲突', body)
  }
  if (!r.ok) {
    let body: unknown
    try { body = await r.json() } catch { /* 无 body */ }
    throw new HttpError(r.status, (body as { message?: string } | null)?.message ?? `HTTP ${r.status}`, body)
  }
  return r.json() as Promise<T>
}
