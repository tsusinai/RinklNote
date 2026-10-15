export interface InteractionBill {
  id: number
  amountMinor: number
  categoryId: number
  categoryName: string
  billType: string
  date: number
  accountName: string
  updatedAt: number
}

export interface InteractionBudget {
  budgetMinor: number | null
  spentMinor: number
  remainingMinor: number | null
  enabled: boolean
  hour: number
  minute: number
}

export interface InteractionView {
  kind: 'bill' | 'budget'
  title: string
  text: string
  expiresAt: number
  consumed: boolean
  message?: string | null
  bill?: InteractionBill | null
  categories?: { id: number; name: string }[]
  budget?: InteractionBudget | null
}

export type InteractionEdit =
  | { operation: 'save'; amountMinor: number; categoryId: number }
  | { operation: 'undo' }
  | { operation: 'reminders'; enabled: boolean; hour: number; minute: number }

export class BotInteractionError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

// 入口票据只在当前页面内存中使用，不借用控制台登录态，也不缓存账单响应。
async function request(token: string, edit?: InteractionEdit): Promise<InteractionView> {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        controller.abort()
        reject(new BotInteractionError(0, '请求超时，请检查网络后重试。'))
      }, 15_000)
    })
    const operation = async (): Promise<InteractionView> => {
      const response = await fetch('/api/bot/interaction', {
        method: edit ? 'POST' : 'GET',
        headers: { 'X-Bot-Action': token, ...(edit ? { 'Content-Type': 'application/json' } : {}) },
        body: edit ? JSON.stringify(edit) : undefined,
        credentials: 'omit',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
        signal: controller.signal,
      })
      const body = await response.json().catch(() => null)
      if (!response.ok) {
        const fallback = [401, 403, 410].includes(response.status)
          ? '入口已失效，请回到小盘重新打开。'
          : response.status === 409 ? '这笔账单已被修改，请回到小盘重新查看。' : '暂时无法完成操作，请稍后重试。'
        throw new BotInteractionError(response.status, typeof body?.message === 'string' ? body.message : fallback)
      }
      if (!body || !['bill', 'budget'].includes(body.kind) || !Number.isFinite(body.expiresAt)) {
        throw new BotInteractionError(502, '暂时无法读取账单，请稍后重试。')
      }
      return body as InteractionView
    }
    // 同一超时覆盖连接与正文读取；中止后不等待可能停滞的响应流。
    return await Promise.race([operation(), timeout])
  } catch (cause) {
    if (cause instanceof BotInteractionError) throw cause
    throw new BotInteractionError(0, '网络连接失败，请检查网络后重试。')
  } finally {
    clearTimeout(timer)
  }
}

export const botInteraction = {
  load: (token: string) => request(token),
  edit: (token: string, edit: InteractionEdit) => request(token, edit),
}

// fragment 不会发送给服务端；读取后立即清理地址栏，刷新须从机器人重新打开。
export function takeBotToken(hash: string): string {
  return new URLSearchParams(hash.slice(1)).get('token') ?? ''
}
