<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { qqBot } from '../../../api/qqBot'
import { useToast } from '../../../composables/useToast'
import Card from '../../../components/ui/Card.vue'
import Btn from '../../../components/ui/Btn.vue'
import Skeleton from '../../../components/ui/Skeleton.vue'
import EmptyState from '../../../components/ui/EmptyState.vue'
import ConfirmDialog from '../../../components/ui/ConfirmDialog.vue'

const toast = useToast()
const configured = ref(false)
const maskedAppId = ref('')
const bound = ref(false)
const openid = ref('')
const appId = ref('')
const secret = ref('')
const showSecret = ref(false)
const code = ref('')
const err = ref('') // 表单校验/保存失败的行内提示
const loadErr = ref(false) // 加载失败（区别于行内 err，走 EmptyState + 重试）
const loaded = ref(false)
const saving = ref(false)
const binding = ref(false)
const origin = window.location.origin

/* 解绑确认（danger 态 + loading） */
const pendingUnbind = ref(false)
const unbinding = ref(false)

async function load() {
  loadErr.value = false
  err.value = ''
  try {
    const s = await qqBot.status()
    configured.value = s.configured === 'true'
    maskedAppId.value = s.maskedAppId || ''
    const b = await qqBot.bindStatus()
    bound.value = b.bound === 'true'
    openid.value = b.openid || ''
  } catch { loadErr.value = true }
  finally { loaded.value = true }
}
onMounted(load)

async function saveConfig() {
  if (!appId.value.trim()) { err.value = '请填写 AppID'; return }
  saving.value = true
  try {
    await qqBot.saveConfig(appId.value.trim(), secret.value)
    err.value = ''; appId.value = ''; secret.value = ''; showSecret.value = false
    toast.push('配置已保存')
    await load()
  } catch (e: any) { err.value = e?.message || '保存失败，请稍后重试' }
  finally { saving.value = false }
}
async function doBind() {
  if (!code.value.trim()) { err.value = '请输入绑定码'; return }
  binding.value = true
  try {
    await qqBot.bind(code.value.trim())
    err.value = ''; code.value = ''; toast.push('绑定成功')
    await load()
  } catch (e: any) { err.value = e?.message || '绑定失败，请稍后重试' }
  finally { binding.value = false }
}
async function confirmUnbind() {
  unbinding.value = true
  try { await qqBot.unbind(); toast.push('已解绑'); pendingUnbind.value = false; await load() }
  catch (e: any) { toast.push(e?.message || '解绑失败', 'err') }
  finally { unbinding.value = false }
}
</script>

<template>
  <Card title="QQ 机器人">
    <!-- 加载态 -->
    <div v-if="!loaded" class="loading" aria-hidden="true">
      <Skeleton height="14px" width="55%" /><Skeleton height="14px" width="80%" /><Skeleton height="34px" width="100%" />
    </div>
    <!-- 失败态 -->
    <template v-else-if="loadErr">
      <EmptyState icon="bot" text="加载失败" hint="无法获取机器人状态" />
      <div class="retry-row"><Btn variant="ghost" @click="load">重试</Btn></div>
    </template>
    <!-- 正常态（加载后校验/保存失败走行内提示） -->
    <template v-else>
      <div class="status-row">
        <span class="status-dot" :class="configured ? 'on' : 'off'"></span>
        <span class="status-text">{{ configured ? 'QQ 机器人已配置 · ' + maskedAppId : 'QQ 机器人未配置' }}</span>
      </div>
      <p class="hint">请在下方填入从 QQ 开放平台获取的凭证</p>

      <div class="section-label">机器人凭证</div>
      <input v-model="appId" class="sel" placeholder="AppID，从 q.qq.com 获取" />
      <div class="secret-row">
        <input v-model="secret" :type="showSecret ? 'text' : 'password'" class="sel" placeholder="ClientSecret，从 q.qq.com 获取" />
        <button class="eye" type="button" :aria-label="showSecret ? '隐藏密钥' : '显示密钥'" @click="showSecret = !showSecret">{{ showSecret ? '🙈' : '👁' }}</button>
      </div>
      <p v-if="err" class="msg err">{{ err }}</p>
      <Btn :loading="saving" @click="saveConfig">保存配置</Btn>

      <template v-if="configured">
        <div class="section-label">账号绑定</div>
        <template v-if="bound">
          <div class="status-text">已绑定 · {{ '...' + openid.slice(-6) }}</div>
          <Btn variant="ghost" @click="pendingUnbind = true">解绑</Btn>
        </template>
        <template v-else>
          <p class="hint">向 QQ 机器人发送任意消息获取 6 位绑定码</p>
          <input v-model="code" class="sel" placeholder="绑定码 (如: 123456)" />
          <Btn :loading="binding" @click="doBind">绑定</Btn>
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

    <!-- 解绑二次确认 -->
    <ConfirmDialog
      :open="pendingUnbind"
      title="解绑QQ机器人"
      message="确定解绑当前账号与QQ机器人的绑定？解绑后将无法通过机器人快捷记账。"
      confirm-text="解绑"
      danger
      :loading="unbinding"
      @close="pendingUnbind = false"
      @confirm="confirmUnbind"
    />
  </Card>
</template>

<style scoped>
/* 卡片/标题/输入/按钮样式全部走全局与基础件，仅保留状态点/文档等特有排版 */
.status-row { display: flex; align-items: center; gap: 8px; }
.status-dot { width: 10px; height: 10px; border-radius: 50%; }
.status-dot.on { background: var(--income); animation: dot-breath var(--dur-breath) ease-in-out infinite; }
.status-dot.off { background: var(--border); }
.status-text { font-size: 14px; }
@keyframes dot-breath { 50% { opacity: .45; } }
.hint { color: var(--muted); font-size: 13px; margin: 8px 0; }
.section-label { font-size: 12px; color: var(--muted); margin: 14px 0 8px; }
.secret-row { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; }
.secret-row .sel { flex: 1; margin-bottom: 0; }
.eye { background: none; border: none; cursor: pointer; font-size: 16px; }
.msg.err { color: var(--expense); font-size: 14px; margin: 4px 0 8px; }
.doc { margin-top: 16px; }
.doc summary { cursor: pointer; color: var(--muted); font-size: 13px; }
.doc ol { padding-left: 20px; color: var(--muted); font-size: 13px; }
.doc code { background: var(--border-light); border-radius: 4px; padding: 1px 5px; }
.loading { display: flex; flex-direction: column; gap: 10px; }
.retry-row { display: flex; justify-content: center; margin-top: 6px; }
</style>
