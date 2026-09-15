import { api } from './http'
import type { BotChannel, BotChannelStatus, BotChannelConfig, BotChannelBindStatus, MessageResponse } from '../types'

/**
 * 多通道机器人管理面 API（Phase E）：QQ / 飞书 / 企业微信三通道端点同构
 * （服务端对应 QQBotManageRoutes / FeishuBotManageRoutes / WecomBotManageRoutes），
 * 这里按 channel 参数统一封装，路由前缀与服务端一一对应。
 * 订阅号（/api/mp/bot/webhook）只收不推、无管理面，故不在此列。
 */
const CHANNEL_BASE: Record<BotChannel, string> = {
  qq: '/api/qq-bot',
  feishu: '/api/feishu-bot',
  wecom: '/api/wecom-bot',
}

/** QQ 通道配置请求体（对应服务端 BotConfigRequest）。 */
export interface QQBotConfigInput { appId: string; clientSecret: string }

/** 飞书通道配置请求体（对应服务端 FeishuBotConfigRequest；encryptKey/verificationToken 可空=清除）。 */
export interface FeishuBotConfigInput { appId: string; appSecret: string; encryptKey?: string; verificationToken?: string }

/** 企微通道配置请求体（对应服务端 WecomBotConfigRequest；pushWebhookUrl 可空=清除推送 webhook）。 */
export interface WecomBotConfigInput { token: string; encodingAesKey: string; pushWebhookUrl?: string }

export type BotConfigInput = QQBotConfigInput | FeishuBotConfigInput | WecomBotConfigInput

/** 机器人状态（configured + 掩码回显：QQ/飞书为 maskedAppId，企微为 maskedToken）。 */
export function getBotStatus(channel: BotChannel) {
  return api<BotChannelStatus>(`${CHANNEL_BASE[channel]}/status`)
}

/** 读取配置回显（掩码 + hasSaved / hasEncryptKey / hasPushWebhook 等标记）。 */
export function getBotConfig(channel: BotChannel) {
  return api<BotChannelConfig>(`${CHANNEL_BASE[channel]}/config`)
}

/** 保存/更新通道配置（覆盖式 upsert；可空项传空串表示清除）。 */
export function upsertBotConfig(channel: BotChannel, config: BotConfigInput) {
  return api<MessageResponse>(`${CHANNEL_BASE[channel]}/config`, { method: 'POST', body: config })
}

/** 账号绑定状态（bound + 身份掩码尾巴）。 */
export function getBotBindStatus(channel: BotChannel) {
  return api<BotChannelBindStatus>(`${CHANNEL_BASE[channel]}/bind-status`)
}

/** 提交 6 位绑定码（与「向机器人发送『登录』」取码配对）。 */
export function bindBot(channel: BotChannel, code: string) {
  return api<MessageResponse>(`${CHANNEL_BASE[channel]}/bind`, { method: 'POST', body: { code } })
}

/** 解绑当前账号在该通道的机器人身份。 */
export function unbindBot(channel: BotChannel) {
  return api<MessageResponse>(`${CHANNEL_BASE[channel]}/unbind`, { method: 'POST' })
}
