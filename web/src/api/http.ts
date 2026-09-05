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
export function getToken(): string | null { return localStorage.getItem(TOKEN_KEY) }
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
    clearToken(); window.location.href = '/login'
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
