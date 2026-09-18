import { describe, it, expect, afterEach, vi } from 'vitest'
import {
  DEMO_RATES_VS_CNY, SUPPORTED_CURRENCIES, fetchRatesVsCny, normalizeRates,
  convertFromCnyMinor, convertToCnyMinor, RATE_SCALE,
} from '../rates'

/* 多币种汇率单测（Task 4.2）：响应校验、超时/失败回落演示表、缩放整数换算。 */

const SERVER_BODY = {
  base: 'CNY',
  rates: { USD: 7.15, EUR: 7.9, CNY: 1 },
  updatedAt: '2026-09-18T08:00:00+08:00',
}

describe('normalizeRates 响应校验', () => {
  it('合法响应归一化', () => {
    const r = normalizeRates(SERVER_BODY)
    expect(r.USD).toBe(7.15)
    expect(r.CNY).toBe(1)
  })
  it('base 非 CNY / 缺 rates / 非对象 → 抛错', () => {
    expect(() => normalizeRates({ base: 'USD', rates: { USD: 1 }, updatedAt: '' })).toThrow()
    expect(() => normalizeRates(null as any)).toThrow()
    expect(() => normalizeRates({ base: 'CNY', rates: null as any, updatedAt: '' })).toThrow()
  })
  it('非法汇率项剔除；缺 CNY 兜底 1', () => {
    const r = normalizeRates({ base: 'CNY', rates: { USD: 7.1, JPY: -1, GBP: NaN, KRW: 0 }, updatedAt: '' })
    expect(r.USD).toBe(7.1)
    expect(r.JPY).toBeUndefined()
    expect(r.GBP).toBeUndefined()
    expect(r.KRW).toBeUndefined()
    expect(r.CNY).toBe(1)
  })
})

describe('fetchRatesVsCny 失败 / 超时回落演示表', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

  it('成功：来源 server + 更新时间', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => SERVER_BODY })))
    const r = await fetchRatesVsCny()
    expect(r.source).toBe('server')
    expect(r.updatedAt).toBe(SERVER_BODY.updatedAt)
    expect(r.rates.USD).toBe(7.15)
  })
  it('网络失败 → 回落演示表', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline') }))
    const r = await fetchRatesVsCny()
    expect(r.source).toBe('demo')
    expect(r.rates).toEqual(DEMO_RATES_VS_CNY)
  })
  it('响应非法（HTTP 500 / base 非 CNY）→ 回落演示表', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500 })))
    expect((await fetchRatesVsCny()).source).toBe('demo')
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ base: 'USD', rates: {}, updatedAt: '' }) })))
    expect((await fetchRatesVsCny()).source).toBe('demo')
  })
  it('超时（AbortController 触发）→ 回落演示表', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new Error('aborted')))
      })))
    const p = fetchRatesVsCny(50)
    await vi.advanceTimersByTimeAsync(60)
    const r = await p
    expect(r.source).toBe('demo')
  })
})

describe('缩放整数换算（整数分约定不动）', () => {
  it('CNY → 目标币种：¥710.00 → $100.00（rate 7.1）', () => {
    expect(convertFromCnyMinor(71000, 7.1)).toBe(10000)
  })
  it('目标币种 → CNY 回算：$100.00 → ¥710.00', () => {
    expect(convertToCnyMinor(10000, 7.1)).toBe(71000)
  })
  it('小币种精度：JPY 0.048 —— ¥1.00 → ¥20.83（2083 分）', () => {
    expect(convertFromCnyMinor(100, 0.048)).toBe(2083)
    expect(convertToCnyMinor(2083, 0.048)).toBe(100)
  })
  it('换算往返误差 ≤ 2 分（两次独立四舍五入，各 ≤0.5 分，小币种取整放大累计）', () => {
    for (const c of SUPPORTED_CURRENCIES) {
      const rate = DEMO_RATES_VS_CNY[c.code]
      const back = convertToCnyMinor(convertFromCnyMinor(1234567, rate), rate)
      expect(Math.abs(back - 1234567)).toBeLessThanOrEqual(2)
    }
  })
  it('非法汇率（0 / 负数）→ 0 防御', () => {
    expect(convertFromCnyMinor(100, 0)).toBe(0)
    expect(convertFromCnyMinor(100, -1)).toBe(0)
  })
  it('RATE_SCALE 缩放不破坏安全整数范围', () => {
    expect(RATE_SCALE).toBe(10000)
    expect(convertFromCnyMinor(Number.MAX_SAFE_INTEGER / 10000, 7.1)).toBeLessThan(Number.MAX_SAFE_INTEGER)
  })
})
