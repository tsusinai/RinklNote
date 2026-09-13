import { describe, expect, it } from 'vitest'
import {
  accountIconPath,
  inferAccountIconKey,
  normalizeAccountIconKey,
  resolveAccountIconKey,
} from '../accountIcon'

describe('account icon keys', () => {
  it('keeps known keys and falls back unknown keys to wallet', () => {
    expect(normalizeAccountIconKey('BANK_CARD')).toBe('BANK_CARD')
    expect(normalizeAccountIconKey('NOT_A_KEY')).toBe('WALLET')
  })

  it('infers legacy accounts by name when iconKey is absent', () => {
    expect(inferAccountIconKey('微信')).toBe('WECHAT')
    expect(resolveAccountIconKey(undefined, '支付宝')).toBe('ALIPAY')
    expect(resolveAccountIconKey(null, '无账户')).toBe('OTHER')
  })

  it('returns different paths for different accounts', () => {
    expect(accountIconPath('WECHAT', '微信')).not.toBe(accountIconPath('ALIPAY', '支付宝'))
  })
})
