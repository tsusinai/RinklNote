<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { qqBot } from '../../../api/qqBot'
import { useToast } from '../../../composables/useToast'
const toast = useToast()
const configured = ref(false)
const maskedAppId = ref('')
const bound = ref(false)
const openid = ref('')
const appId = ref('')
const secret = ref('')
const showSecret = ref(false)
const code = ref('')
const err = ref('')
const loaded = ref(false)
const origin = window.location.origin

async function load() {
  try {
    const s = await qqBot.status()
    configured.value = s.configured === 'true'
    maskedAppId.value = s.maskedAppId || ''
    const b = await qqBot.bindStatus()
    bound.value = b.bound === 'true'
    openid.value = b.openid || ''
  } catch { err.value = '加载失败，请稍后重试' }
  finally { loaded.value = true }
}
onMounted(load)

async function saveConfig() {
  if (!appId.value) { err.value = '请填写 AppID'; return }
  try { await qqBot.saveConfig(appId.value, secret.value); err.value = ''; appId.value = ''; secret.value = ''; showSecret.value = false; await load() }
  catch (e: any) { err.value = e?.message || '保存失败' }
}
async function doBind() {
  if (!code.value) return
  try { await qqBot.bind(code.value); err.value = ''; code.value = ''; await load() }
  catch (e: any) { err.value = e?.message || '绑定失败' }
}
async function doUnbind() {
  try { await qqBot.unbind(); toast.push('已解绑'); await load() }
  catch (e: any) { toast.push(e?.message || '解绑失败', 'err') }
}
</script>

<template>
  <div class="card bot-card">
    <div class="card-title">QQ 机器人</div>
    <div v-if="err && !loaded" class="msg err">{{ err }}</div>
    <template v-else>
      <div class="status-row">
        <span class="status-dot" :class="configured ? 'on' : 'off'"></span>
        <span class="status-text">{{ configured ? 'QQ 机器人已配置 · ' + maskedAppId : 'QQ 机器人未配置' }}</span>
      </div>
      <p class="hint">请在下方填入从 QQ 开放平台获取的凭证</p>

      <div class="section-label">机器人凭证</div>
      <input class="sel" placeholder="AppID，从 q.qq.com 获取" v-model="appId" />
      <div class="secret-row">
        <input class="sel" :type="showSecret ? 'text' : 'password'" placeholder="ClientSecret，从 q.qq.com 获取" v-model="secret" />
        <button class="eye" @click="showSecret = !showSecret">{{ showSecret ? '🙈' : '👁' }}</button>
      </div>
      <button class="btn primary" @click="saveConfig">保存配置</button>

      <template v-if="configured">
        <div class="section-label">账号绑定</div>
        <template v-if="bound">
          <div class="status-text">已绑定 · {{ '...' + openid.slice(-6) }}</div>
          <button class="btn ghost" @click="doUnbind">解绑</button>
        </template>
        <template v-else>
          <p class="hint">向 QQ 机器人发送任意消息获取 6 位绑定码</p>
          <input class="sel" placeholder="绑定码 (如: 123456)" v-model="code" />
          <button class="btn primary" @click="doBind">绑定</button>
        </template>
      </template>

      <details class="doc">
        <summary>接入说明</summary>
        <ol>
          <li>创建机器人: https://q.qq.com</li>
          <li>填写上方 AppID / ClientSecret 凭证</li>
          <li>配置 Webhook URL: <code>{{ origin }}/api/qq/bot/webhook</code></li>
          <li>向机器人发送任意消息获取绑定码</li>
        </ol>
      </details>
    </template>
  </div>
</template>

<style scoped>
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); border-left: 3px solid var(--primary); }
.card-title { font-weight: 600; margin-bottom: 10px; }
.status-row { display: flex; align-items: center; gap: 8px; }
.status-dot { width: 10px; height: 10px; border-radius: 50%; }
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
