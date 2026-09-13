export const ACCOUNT_ICON_KEYS = [
  'WALLET',
  'BANK_CARD',
  'CASH',
  'WECHAT',
  'ALIPAY',
  'CREDIT_CARD',
  'INVESTMENT',
  'OTHER',
] as const

export type AccountIconKey = typeof ACCOUNT_ICON_KEYS[number]

const paths: Record<AccountIconKey, string> = {
  WALLET:
    'M20 7V6a2 2 0 0 0-2-2H5a3 3 0 0 0-3 3v10a3 3 0 0 0 3 3h15a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2zM5 6h13v1H5a1 1 0 1 1 0-2zm15 10H5a3 3 0 0 1-1-.17V9.83A3 3 0 0 1 5 9.66h15v6.34zm-4-2a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z',
  BANK_CARD:
    'M21 4H3a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h18a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 14H3v-6h18v6zm0-10H3V6h18v2zM6 14h5v2H6z',
  CASH:
    'M21 4H3a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h18a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 14H3V6h18v12zM6 16h12v-2H6v2zm1.5-5A1.5 1.5 0 1 0 6 9.5 1.5 1.5 0 0 0 7.5 11zm9 0a1.5 1.5 0 1 0-1.5-1.5 1.5 1.5 0 0 0 1.5 1.5z',
  WECHAT:
    'M8.2 4C4.8 4 2 6.3 2 9.1c0 1.6.9 3 2.4 4l-.6 2 2.3-1.2c.7.2 1.4.3 2.2.3h.4A6.5 6.5 0 0 1 15 7.7c.4 0 .7 0 1.1.1C15.5 5.6 12.2 4 8.2 4zm10.8 5c-3 0-5.5 2-5.5 4.5S16 18 19 18c.7 0 1.3-.1 1.9-.3l1.6.8-.4-1.5c1-0.8 1.6-1.9 1.6-3.2C23.7 11.3 21.2 9 19 9zM6.7 8.2a1 1 0 1 1 0-2 1 1 0 0 1 0 2zm4.3-1a1 1 0 1 1-2 0 1 1 0 0 1 2 0zm7.5 6a.8.8 0 1 1 0-1.6.8.8 0 0 1 0 1.6zm2.8 0a.8.8 0 1 1 0-1.6.8.8 0 0 1 0 1.6z',
  ALIPAY:
    'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16zm1-13h-2v3H8v2h3v1H8v2h3v3h2v-3h3v-2h-3v-1h3V10h-3V7z',
  CREDIT_CARD:
    'M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 14H4v-6h16v6zm0-10H4V6h16v2zm-5 7h5v2h-5z',
  INVESTMENT:
    'M3.5 18.49l6-6.01 4 4L22 6.92 20.59 5.51l-7.09 7.92-4-4L2 16.99zM14 5h7v7h-2V8.41l-4 4-4-4-8 8 1.41 1.41 6.59-6.59 4 4 5.59-5.59V15h2V5z',
  OTHER:
    'M6 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4z',
}

export function normalizeAccountIconKey(iconKey?: string | null): AccountIconKey {
  return ACCOUNT_ICON_KEYS.includes(iconKey as AccountIconKey)
    ? iconKey as AccountIconKey
    : 'WALLET'
}

export function inferAccountIconKey(name: string): AccountIconKey {
  if (name === '微信') return 'WECHAT'
  if (name === '支付宝') return 'ALIPAY'
  if (name === '无账户') return 'OTHER'
  return 'WALLET'
}

export function resolveAccountIconKey(iconKey: string | null | undefined, name: string): AccountIconKey {
  if (ACCOUNT_ICON_KEYS.includes(iconKey as AccountIconKey)) return iconKey as AccountIconKey
  if (iconKey == null || iconKey === '') return inferAccountIconKey(name)
  return 'WALLET'
}

export function accountIconPath(iconKey?: string | null, name = ''): string {
  return paths[resolveAccountIconKey(iconKey, name)]
}

export function withAlpha(hex: string, alpha: number): string {
  const normalized = hex.replace('#', '')
  if (!/^[0-9a-fA-F]{6}$/.test(normalized)) return `rgba(100, 116, 139, ${alpha})`
  const value = Number.parseInt(normalized, 16)
  return `rgba(${value >> 16}, ${(value >> 8) & 255}, ${value & 255}, ${alpha})`
}
