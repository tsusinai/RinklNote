/* ============================================================
 * 多币种汇率（Task 4.2 Web 侧）
 * - 真实汇率接服务端 `GET /api/rates`，契约（钉死）：
 *   {"base":"CNY","rates":{"USD":<每 1 单位该币种兑 CNY 的数>,...},"updatedAt":"ISO-8601"}
 * - 失败 / 超时 / 响应非法 → 回落内置演示表（与 App DEMO_RATES_VS_CNY 数值一致）
 * - 整数分约定不动：bills 金额仍为本位币（CNY）整数分；换算仅用于展示，
 *   实现用缩放整数（RATE_SCALE=1e4，即汇率精度 4 位小数）避免浮点误差
 * ============================================================ */

import { getToken } from '../api/http'

/** 币种定义：ISO 4217 代码 + 常用符号 + 中文名（首项为本位币 CNY，顺序即展示顺序） */
export interface CurrencyDef { code: string; symbol: string; name: string }

export const SUPPORTED_CURRENCIES: readonly CurrencyDef[] = [
  { code: 'CNY', symbol: '¥', name: '人民币' },
  { code: 'USD', symbol: '$', name: '美元' },
  { code: 'EUR', symbol: '€', name: '欧元' },
  { code: 'JPY', symbol: '¥', name: '日元' },
  { code: 'GBP', symbol: '£', name: '英镑' },
  { code: 'HKD', symbol: 'HK$', name: '港币' },
  { code: 'KRW', symbol: '₩', name: '韩元' },
]

/** 演示汇率表（相对 CNY 的静态近似牌价，与 App DEMO_RATES_VS_CNY 数值一致） */
export const DEMO_RATES_VS_CNY: Record<string, number> = {
  CNY: 1.00,
  USD: 7.10,
  EUR: 7.80,
  JPY: 0.0480,
  GBP: 9.00,
  HKD: 0.91,
  KRW: 0.0052,
}

/** 服务端汇率响应契约 */
export interface RatesResponse {
  base: 'CNY' | string
  rates: Record<string, number>
  updatedAt: string
}

/** 页面消费形态：汇率表 + 来源 + 更新时间 */
export interface RatesVsCny {
  rates: Record<string, number>
  updatedAt: string
  source: 'server' | 'demo'
}

/** 汇率缩放精度（4 位小数）：rateI = round(rate × 1e4) */
export const RATE_SCALE = 10000

/**
 * 拉取服务端汇率；失败 / 超时 / 响应非法一律回落演示表（不抛错、不触发 401 跳登录）。
 * @param timeoutMs 超时毫秒数（默认 3s）
 */
export async function fetchRatesVsCny(timeoutMs = 3000): Promise<RatesVsCny> {
  try {
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), timeoutMs)
    try {
      const headers: Record<string, string> = { Accept: 'application/json' }
      const token = getToken()
      if (token) headers.Authorization = 'Bearer ' + token
      const res = await fetch('/api/rates', { headers, signal: ctl.signal })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const body = (await res.json()) as RatesResponse
      return { rates: normalizeRates(body), updatedAt: typeof body.updatedAt === 'string' ? body.updatedAt : '', source: 'server' }
    } finally {
      clearTimeout(timer)
    }
  } catch {
    return { rates: { ...DEMO_RATES_VS_CNY }, updatedAt: '', source: 'demo' }
  }
}

/** 响应校验与归一化：base 必须为 CNY，汇率必须为有限正数；非法抛错由上层回落 */
export function normalizeRates(body: RatesResponse): Record<string, number> {
  if (!body || body.base !== 'CNY' || !body.rates || typeof body.rates !== 'object') {
    throw new Error('汇率响应格式非法')
  }
  const out: Record<string, number> = {}
  for (const [code, rate] of Object.entries(body.rates)) {
    if (typeof rate !== 'number' || !Number.isFinite(rate) || rate <= 0) continue
    out[code] = rate
  }
  if (!out.CNY) out.CNY = 1 // 本位币兜底
  return out
}

/**
 * 本位币整数分 → 目标币种整数分（展示用）：cnyMinor ÷ rate。
 * rate 以 RATE_SCALE 缩放为整数参与运算（¥710.00 = 71000 分 ÷ 7.1 → $100.00 = 10000 分）。
 */
export function convertFromCnyMinor(cnyMinor: number, rate: number): number {
  const rateI = Math.round(rate * RATE_SCALE)
  if (rateI <= 0) return 0
  return Math.round((cnyMinor * RATE_SCALE) / rateI)
}

/** 目标币种整数分 → 本位币整数分（展示回算）：minor × rate */
export function convertToCnyMinor(minor: number, rate: number): number {
  const rateI = Math.round(rate * RATE_SCALE)
  return Math.round((minor * rateI) / RATE_SCALE)
}
