/** 稳定错误类型：页面据此展示「用户能采取行动」的文案，不透出原始报文/堆栈。
 *  statusCode 始终保留原始 HTTP 状态码（网络层失败无状态码，约定为 0）供日志与测试断言。 */
export type ErrorKind =
  | 'unauthorized'  // 401：需要重新登录
  | 'conflict'      // 409：并发冲突（乐观锁）
  | 'badGateway'    // 502 / 503 / 504：网关或上游暂时不可用
  | 'server'        // 其余 5xx：服务端错误
  | 'network'       // fetch 直接失败：网络不可用 / 请求被中断
  | 'http'          // 其余 HTTP 错误（如 404、422）

/** 各错误类型对应的用户文案（稳定中文、行动导向）。
 *  注意：allow401 场景（如改密时原密码错误）的细分原因仍走 message，由调用方自行取舍。 */
export const ERROR_KIND_MESSAGES: Record<ErrorKind, string> = {
  unauthorized: '登录已过期，请重新登录',
  conflict: '数据已被其他设备修改，请刷新后再试',
  badGateway: '服务暂时不可用，请稍后重试',
  server: '服务出错了，请稍后重试',
  network: '网络不可用，请检查网络后重试',
  http: '请求失败，请稍后重试',
}

/** 按 HTTP 状态码推导稳定错误类型 */
function kindOfStatus(status: number): ErrorKind {
  if (status === 401) return 'unauthorized'
  if (status === 409) return 'conflict'
  if (status === 502 || status === 503 || status === 504) return 'badGateway'
  if (status >= 500) return 'server'
  return 'http'
}

export class HttpError extends Error {
  statusCode: number
  data: unknown
  kind: ErrorKind
  /** 面向用户展示的稳定中文文案；message 保留原始信息（服务端 message / HTTP nnn）供日志与调试 */
  userMessage: string
  constructor(status: number, message?: string, data?: unknown, kind?: ErrorKind) {
    super(message ?? `HTTP ${status}`)
    this.statusCode = status
    this.data = data
    this.kind = kind ?? kindOfStatus(status)
    this.userMessage = ERROR_KIND_MESSAGES[this.kind]
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

  // fetch 只有在网络不可用 / 请求被中断等传输层失败时才会 reject，
  // 统一包装为 kind=network 的 HttpError（statusCode=0，无原始 HTTP 状态可言）
  let r: Response
  try {
    r = await fetch(url, {
      method: opts.method ?? 'GET',
      headers: h,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    })
  } catch {
    throw new HttpError(0, '网络不可用', undefined, 'network')
  }

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
