import { describe, it, expect, vi, afterEach } from 'vitest'
import { getBotStatus, getBotConfig, upsertBotConfig, getBotBindStatus, bindBot, unbindBot } from '../bots'
import type { BotChannelStatus, BotChannelConfig, BotChannelBindStatus, MessageResponse } from '../../types'

// 多通道机器人管理面 API：三通道端点同构（/api/{qq,feishu,wecom}-bot/*），
// 这里按 channel 维度断言路径与方法体，防止通道前缀或字段名回归。
const lastCall = () => (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.at(-1) as [string, RequestInit]

function mockFetchJson(body: unknown) {
  globalThis.fetch = vi.fn(async () => ({ status: 200, ok: true, json: async () => body })) as any
}

afterEach(() => { vi.restoreAllMocks() })

describe('bots 管理面 API（三通道统一封装）', () => {
  it('getBotStatus 按通道命中对应前缀', async () => {
    const byChannel: Record<string, BotChannelStatus> = {
      '/api/qq-bot/status': { configured: 'true', maskedAppId: 'abc***' },
      '/api/feishu-bot/status': { configured: 'true', maskedAppId: 'cli***' },
      '/api/wecom-bot/status': { configured: 'false', maskedToken: '' },
    }
    globalThis.fetch = vi.fn(async (url: string) => ({
      status: 200, ok: true, json: async () => byChannel[url],
    })) as any
    expect((await getBotStatus('qq')).maskedAppId).toBe('abc***')
    expect((await getBotStatus('feishu')).maskedAppId).toBe('cli***')
    expect((await getBotStatus('wecom')).configured).toBe('false')
  })

  it('getBotConfig 返回各通道掩码与标记字段', async () => {
    mockFetchJson({ configured: 'true', maskedAppId: 'ww***', hasSaved: 'true', hasPushWebhook: 'false' })
    const cfg: BotChannelConfig = await getBotConfig('wecom')
    expect(cfg.hasSaved).toBe('true')
    expect(lastCall()[0]).toBe('/api/wecom-bot/config')
  })

  it('upsertBotConfig 以 POST JSON 透传各通道请求体', async () => {
    mockFetchJson({ message: 'ok' } as MessageResponse)
    await upsertBotConfig('qq', { appId: 'a', clientSecret: 's' })
    let [url, init] = lastCall()
    expect(url).toBe('/api/qq-bot/config')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ appId: 'a', clientSecret: 's' })

    await upsertBotConfig('feishu', { appId: 'a', appSecret: 's', encryptKey: '', verificationToken: 't' })
    ;[url, init] = lastCall()
    expect(url).toBe('/api/feishu-bot/config')
    expect(JSON.parse(init.body as string).verificationToken).toBe('t')

    await upsertBotConfig('wecom', { token: 't', encodingAesKey: 'k', pushWebhookUrl: 'https://x' })
    ;[url, init] = lastCall()
    expect(url).toBe('/api/wecom-bot/config')
    expect(JSON.parse(init.body as string).encodingAesKey).toBe('k')
  })

  it('getBotBindStatus 按通道命中 bind-status', async () => {
    mockFetchJson({ bound: 'true', openId: '123456' } as BotChannelBindStatus)
    const b = await getBotBindStatus('feishu')
    expect(b.bound).toBe('true')
    expect(lastCall()[0]).toBe('/api/feishu-bot/bind-status')
  })

  it('bindBot POST 绑定码到对应通道', async () => {
    mockFetchJson({ message: '绑定成功' })
    await bindBot('wecom', '123456')
    const [url, init] = lastCall()
    expect(url).toBe('/api/wecom-bot/bind')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ code: '123456' })
  })

  it('unbindBot POST 到对应通道的 unbind', async () => {
    mockFetchJson({ message: '已解绑' })
    await unbindBot('qq')
    const [url, init] = lastCall()
    expect(url).toBe('/api/qq-bot/unbind')
    expect(init.method).toBe('POST')
  })
})
