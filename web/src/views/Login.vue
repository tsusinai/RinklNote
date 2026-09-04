<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useToast } from '../composables/useToast'
import { useThemeStore } from '../stores/theme'

const router = useRouter()
const store = useAuthStore()
const theme = useThemeStore()
const toast = useToast()

const mode = ref<'login' | 'register'>('login')
const phone = ref('')
const pwd = ref('')
const qqCode = ref('')
const busy = ref(false)

async function submit() {
  if (!phone.value || !pwd.value) { toast.push('手机号或密码不能为空', 'err'); return }
  if (mode.value === 'register' && pwd.value.length < 6) { toast.push('密码长度至少6位', 'err'); return }
  busy.value = true
  try {
    if (mode.value === 'login') await store.login(phone.value, pwd.value)
    else await store.login(phone.value, pwd.value) // register 返回同结构 token
    toast.push('登录成功')
    router.replace('/console')
  } catch (e: any) {
    toast.push(e?.message || '登录失败', 'err')
  } finally { busy.value = false }
}

async function qqLogin() {
  if (!qqCode.value) { toast.push('请输入 QQ 登录码', 'err'); return }
  busy.value = true
  try { await store.loginByQq(qqCode.value); toast.push('QQ登录成功'); router.replace('/console') }
  catch (e: any) { toast.push(e?.message || 'QQ 登录失败', 'err') }
  finally { busy.value = false }
}
function toggleMode() { mode.value = mode.value === 'login' ? 'register' : 'login' }
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1>RinklNote</h1>
      <p class="sub">记账 · 资产 · AI 洞察</p>
      <div class="toggle">
        <button :class="mode === 'login' ? 'on exp' : ''" @click="mode = 'login'">登录</button>
        <button :class="mode === 'register' ? 'on exp' : ''" @click="mode = 'register'">注册</button>
      </div>
      <input v-model="phone" type="text" placeholder="手机号" />
      <input v-model="pwd" type="password" placeholder="密码" @keyup.enter="submit" />
      <button class="btn btn-primary" :disabled="busy" @click="submit">{{ mode === 'login' ? '登录' : '注册' }}</button>
      <hr />
      <input v-model="qqCode" type="text" placeholder="QQ 登录码（向机器人发「登录」获取）" />
      <button class="btn" :disabled="busy" @click="qqLogin">QQ 登录</button>
      <button class="theme-btn" @click="theme.toggle()">{{ theme.theme === 'dark' ? '🌙 暗色' : '☀️ 亮色' }}</button>
    </div>
  </div>
</template>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--bg, #F7F7F9); padding: 16px; }
.login-card { width: 100%; max-width: 360px; background: var(--card, #FFF); border-radius: 18px; padding: 28px 24px; box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.1)); display: flex; flex-direction: column; gap: 12px; }
.login-card h1 { font-size: 28px; margin: 0; }
.login-card .sub { color: var(--muted, #888); margin: 0 0 8px; font-size: 13px; }
.toggle { display: flex; gap: 8px; }
.toggle button { flex: 1; padding: 10px; border-radius: 10px; border: 1px solid var(--border, #EEE); background: none; color: var(--muted, #888); font-size: 14px; cursor: pointer; }
.toggle button.on.exp { background: var(--expense, #CA3032); color: #fff; border-color: transparent; font-weight: 600; }
input { padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border, #EEE); font-size: 16px; background: var(--card, #FFF); color: var(--text, #111); }
.btn { padding: 12px 18px; border-radius: 12px; border: none; font-size: 15px; cursor: pointer; background: var(--card, #FFF); color: var(--text, #111); border: 1px solid var(--border, #EEE); }
.btn btn-primary, .btn-primary { background: var(--primary, #7EC1FC); color: #0b2b44; font-weight: 600; }
hr { border: none; border-top: 1px solid var(--border-light, #EEE); margin: 8px 0; }
.theme-btn { align-self: flex-start; background: none; border: none; color: var(--muted, #888); cursor: pointer; font-size: 13px; }
</style>
