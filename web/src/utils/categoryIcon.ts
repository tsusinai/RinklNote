// 分类图标工具：iconName 字符串 → emoji。
// 键与服务端/App 端 seed 的 iconName 一致，未命中回退分类名首字符。

const EMOJI_MAP: Record<string, string> = {
  meals: '🍚', daily: '🧴', transport: '🚌', study: '📚', sports: '🏋️',
  entertainment: '🎬', shopping: '🛒', medical: '💊', home: '🏠', social: '🎁',
  pet: '🐱', beauty: '💄', clothing: '👗', baby: '🍼', car: '🚗',
  digital: '📱', insurance: '🛡️', travel: '✈️', salary: '💰', parttime: '💼',
  finance: '📈', other: '📦', reimburse: '🧾', resale: '♻️', redpacket: '🧧',
}

/** iconName → emoji；未命中回退 fallbackName（分类名）首字符，再兜底「●」。 */
export function categoryEmoji(iconName: string | null | undefined, fallbackName?: string): string {
  if (iconName && EMOJI_MAP[iconName]) return EMOJI_MAP[iconName]
  return fallbackName?.slice(0, 1) || '●'
}
