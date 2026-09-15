<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { getBotStatus, upsertBotConfig, getBotBindStatus, bindBot, unbindBot, type BotConfigInput } from '../../../api/bots'
import { useToast } from '../../../composables/useToast'

const toast = useToast()
const origin = window.location.origin

// ── 通道元信息：chip 文案 / 状态名 / webhook 路径 / 平台入口 ──
const CHANNELS = [
  { key: 'qq', label: 'QQ', statusName: 'QQ 机器人', webhookPath: '/api/qq/bot/webhook', docUrl: 'https://q.qq.com', docName: 'q.qq.com' },
  { key: 'feishu', label: '飞书', statusName: '飞书机器人', webhookPath: '/api/feishu/bot/webhook', docUrl: 'https://open.feishu.cn', docName: 'open.feishu.cn' },
  { key: 'wecom', label: '企业微信', statusName: '企微机器人', webhookPath: '/api/wecom/bot/webhook', docUrl: 'https://work.weixin.qq.com', docName: 'work.weixin.qq.com' },
] as const
type ChannelKey = (typeof CHANNELS)[number]['key']
const active = ref<ChannelKey>('qq')

// ── 各通道配置字段定义（驱动表单渲染；secret=true 掩码输入可切明文，required 为服务端必填项）──
interface FieldDef { key: string; label: string; placeholder: string; secret?: boolean; required?: boolean }
const FIELDS: Record<ChannelKey, FieldDef[]> = {
  qq: [
    { key: 'appId', label: 'AppID', placeholder: 'AppID，从 q.qq.com 获取', required: true },
    { key: 'clientSecret', label: 'ClientSecret', placeholder: 'ClientSecret，从 q.qq.com 获取', secret: true, required: true },
  ],
  feishu: [
    { key: 'appId', label: 'AppID', placeholder: 'AppID，从飞书开放平台获取', required: true },
    { key: 'appSecret', label: 'AppSecret', placeholder: 'AppSecret，从飞书开放平台获取', secret: true, required: true },
    { key: 'encryptKey', label: 'Encrypt Key', placeholder: 'Encrypt Key（可空，配了才走加密验签）' },
    { key: 'verificationToken', label: 'Verification Token', placeholder: 'Verification Token（可空）', secret: true },
  ],
  wecom: [
    { key: 'token', label: 'Token', placeholder: 'Token，从企业微信管理端获取', required: true },
    { key: 'encodingAesKey', label: 'EncodingAESKey', placeholder: 'EncodingAESKey，从企业微信管理端获取', secret: true, required: true },
    { key: 'pushWebhookUrl', label: '推送 Webhook', placeholder: '消息推送 Webhook URL（可空，用于日报主动推送）' },
  ],
}

// ── 每通道独立的加载/表单状态（切换 chip 不丢已填内容）──
interface ChannelUi {
  loaded: boolean
  configured: boolean
  mask: string            // 掩码回显（QQ/飞书 AppID、企微 Token）
  bound: boolean
  bindTail: string        // 绑定身份掩码尾巴
  bindCode: string        // 6 位绑定码输入
  showSecret: boolean     // secret 输入框明文切换
  err: string
  fields: Record<string, string>
}
function blankUi(keys: string[]): ChannelUi {
  const fields: Record<string, string> = {}
  keys.forEach(k => { fields[k] = '' })
  return { loaded: false, configured: false, mask: '', bound: false, bindTail: '', bindCode: '', showSecret: false, err: '', fields }
}
const ui = reactive<Record<ChannelKey, ChannelUi>>({
  qq: blankUi(FIELDS.qq.map(f => f.key)),
  feishu: blankUi(FIELDS.feishu.map(f => f.key)),
  wecom: blankUi(FIELDS.wecom.map(f => f.key)),
})

async function load(ch: ChannelKey) {
  try {
    const [s, b] = await Promise.all([getBotStatus(ch), getBotBindStatus(ch)])
    ui[ch].configured = s.configured === 'true'
    ui[ch].mask = s.maskedAppId || s.maskedToken || ''
    ui[ch].bound = b.bound === 'true'
    ui[ch].bindTail = b.openid || b.openId || ''   // QQ 返回 openid，飞书/企微返回 openId
    ui[ch].err = ''
  } catch { ui[ch].err = '加载失败，请稍后重试' }
  finally { ui[ch].loaded = true }
}
onMounted(() => { CHANNELS.forEach(c => load(c.key)) })

// 按通道把表单字段组装成服务端请求体（键与服务端 *BotConfigRequest 对齐）
function buildPayload(ch: ChannelKey): BotConfigInput {
  const f = (k: string) => (ui[ch].fields[k] || '').trim()
  switch (ch) {
    case 'qq': return { appId: f('appId'), clientSecret: f('clientSecret') }
    case 'feishu': return { appId: f('appId'), appSecret: f('appSecret'), encryptKey: f('encryptKey'), verificationToken: f('verificationToken') }
    case 'wecom': return { token: f('token'), encodingAesKey: f('encodingAesKey'), pushWebhookUrl: f('pushWebhookUrl') }
  }
}

async function saveConfig() {
  const ch = active.value
  const missing = FIELDS[ch].find(fd => fd.required && !ui[ch].fields[fd.key]?.trim())
  if (missing) { ui[ch].err = `请填写 ${missing.label}`; return }
  try {
    await upsertBotConfig(ch, buildPayload(ch))
    ui[ch].err = ''
    FIELDS[ch].forEach(fd => { ui[ch].fields[fd.key] = '' })
    ui[ch].showSecret = false
    toast.push('配置已保存')
    await load(ch)
  } catch (e: any) { ui[ch].err = e?.message || '保存失败' }
}

async function doBind() {
  const ch = active.value
  if (!ui[ch].bindCode.trim()) return
  try { await bindBot(ch, ui[ch].bindCode.trim()); ui[ch].err = ''; ui[ch].bindCode = ''; await load(ch) }
  catch (e: any) { ui[ch].err = e?.message || '绑定失败' }
}

async function doUnbind() {
  const ch = active.value
  try { await unbindBot(ch); toast.push('已解绑'); await load(ch) }
  catch (e: any) { toast.push(e?.message || '解绑失败', 'err') }
}
</script>

<template>
  <div class="card bot-card">
    <div class="card-title">多通道机器人</div>

    <!-- 通道切换：三通道共用一张卡，chip 高亮当前通道 -->
    <div class="channel-tabs">
      <button v-for="c in CHANNELS" :key="c.key" class="channel-tab" :class="{ active: active === c.key }" @click="active = c.key">{{ c.label }}</button>
    </div>

    <template v-for="c in CHANNELS" :key="c.key">
      <template v-if="active === c.key">
        <div v-if="!ui[c.key].loaded" class="hint">加载中…</div>

        <template v-else>
          <!-- 错误横幅：加载 / 保存 / 绑定失败都在此提示，不遮挡下方表单 -->
          <div v-if="ui[c.key].err" class="msg err">{{ ui[c.key].err }}</div>
          <div class="status-row">
            <span class="status-dot" :class="ui[c.key].configured ? 'on' : 'off'"></span>
            <span class="status-text">{{ ui[c.key].configured ? c.statusName + '已配置 · ' + ui[c.key].mask : c.statusName + '未配置' }}</span>
          </div>

          <div class="status-row">
            <span class="status-dot" :class="ui[c.key].bound ? 'on' : 'off'"></span>
            <span class="status-text">{{ ui[c.key].bound ? '本账号已绑定 · ...' + ui[c.key].bindTail : '本账号未绑定' }}</span>
          </div>
          <p class="hint">请在下方填入 {{ c.statusName }}的凭证（{{ c.docName }}）</p>

          <div class="section-label">机器人凭证</div>
          <template v-for="fd in FIELDS[c.key]" :key="fd.key">
            <div v-if="fd.secret" class="secret-row">
              <input class="sel" :type="ui[c.key].showSecret ? 'text' : 'password'" :placeholder="fd.placeholder" v-model="ui[c.key].fields[fd.key]" />
              <button class="eye" @click="ui[c.key].showSecret = !ui[c.key].showSecret" type="button">{{ ui[c.key].showSecret ? '🙈' : '👁' }}</button>
            </div>
            <input v-else class="sel" :placeholder="fd.placeholder" v-model="ui[c.key].fields[fd.key]" />
          </template>
          <button class="btn primary" @click="saveConfig">保存配置</button>

          <div class="section-label">账号绑定</div>
          <template v-if="ui[c.key].bound">
            <button class="btn ghost" @click="doUnbind">解绑</button>
          </template>
          <template v-else>
            <p class="hint">在对应 App 向机器人发送「登录」获取 6 位码，在下方提交或到 App「我的」页提交</p>
            <input class="sel" placeholder="绑定码 (如: 123456)" v-model="ui[c.key].bindCode" />
            <button class="btn primary" @click="doBind">绑定</button>
          </template>

          <details class="doc">
            <summary>接入说明</summary>
            <ol>
              <li>创建机器人: {{ c.docUrl }}</li>
              <li>填写上方凭证并保存</li>
              <li>配置 Webhook URL: <code>{{ origin + c.webhookPath }}</code></li>
              <li>向机器人发送「登录」获取绑定码，发送「午餐20元」即可记账</li>
            </ol>
          </details>
        </template>
      </template>
    </template>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); border-left: 3px solid var(--primary); }
.card-title { font-weight: 600; margin-bottom: 10px; }
.channel-tabs { display: flex; gap: 8px; margin-bottom: 14px; }
.channel-tab { flex: 1; padding: 8px 0; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--muted); font-size: 13px; cursor: pointer; }
.channel-tab.active { background: var(--primary); color: var(--on-primary); border-color: var(--primary); font-weight: 600; }
.status-row { display: flex; align-items: center; gap: 8px; }
.status-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.status-dot.on { background: var(--income); }
.status-dot.off { background: var(--border); }
.status-text { font-size: 14px; }
.hint { color: var(--muted); font-size: 13px; margin: 8px 0; }
.section-label { font-size: 12px; color: var(--muted); margin: 14px 0 8px; }
.sel { width: 100%; padding: 10px 12px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 14px; margin-bottom: 10px; }
.secret-row { display: flex; gap: 8px; align-items: center; }
.secret-row .sel { flex: 1; }
.eye { background: none; border: none; cursor: pointer; font-size: 16px; }
.btn { padding: 10px 16px; border-radius: 12px; border: none; font-size: 14px; cursor: pointer; margin-top: 4px; }
.btn.primary { background: var(--primary); color: var(--on-primary); font-weight: 600; }
.btn.ghost { background: var(--card); color: var(--text); border: 1px solid var(--border); }
.doc { margin-top: 16px; }
.doc summary { cursor: pointer; color: var(--muted); font-size: 13px; }
.doc ol { padding-left: 20px; color: var(--muted); font-size: 13px; }
.doc code { background: var(--border-light); border-radius: 4px; padding: 1px 5px; }
.msg.err { color: var(--expense); font-size: 14px; }
</style>
