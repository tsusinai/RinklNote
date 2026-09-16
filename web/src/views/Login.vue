<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useToast } from '../composables/useToast'
import { useThemeStore } from '../stores/theme'
import { sanitizeRedirect } from '../router/guards'
import Card from '../components/ui/Card.vue'
import Btn from '../components/ui/Btn.vue'
import xiaopanAvatar from '../assets/xiaopan-avatar.png'

const router = useRouter()
const route = useRoute()
const store = useAuthStore()
const theme = useThemeStore()
const toast = useToast()

const mode = ref<'login' | 'register'>('login')
const phone = ref('')
const pwd = ref('')
const qqCode = ref('')
const busy = ref(false)
/* 表单内红字：本地校验错误就地展示；接口错误仍走全局 toast */
const formErr = ref('')

/* 401/掉登录后的回跳地址（guards.ts / http.ts 写入；仅接受站内路径）。
 * 注意：W3c 翻新登录页时请保留该回跳逻辑（登录与 QQ 登录成功后都要用 redirect ?? '/console'）。 */
const redirect = sanitizeRedirect(route.query.redirect)

/* 大陆手机号：1 开头、第二位 3-9、共 11 位（与 App 登录页 LoginScreen.kt 同一规则） */
const PHONE_RE = /^1[3-9]\d{9}$/

function switchMode(m: 'login' | 'register') {
  mode.value = m
  formErr.value = ''
}

async function submit() {
  formErr.value = ''
  if (!phone.value.trim() || !pwd.value) { formErr.value = '手机号或密码不能为空'; return }
  if (!PHONE_RE.test(phone.value.trim())) { formErr.value = '请输入正确的 11 位手机号'; return }
  if (mode.value === 'register' && pwd.value.length < 6) { formErr.value = '密码长度至少 6 位'; return }
  busy.value = true
  try {
    if (mode.value === 'login') await store.login(phone.value.trim(), pwd.value)
    /* 修硬伤：注册分支此前复制了登录逻辑（调 store.login），register API 从未被调用。
     * 现改为真正调 register —— 服务端 201 返回同结构 token，注册成功即自动登录进控制台。 */
    else await store.register(phone.value.trim(), pwd.value)
    toast.push(mode.value === 'login' ? '登录成功' : '注册成功')
    router.replace(redirect ?? '/console')
  } catch (e: any) {
    // 服务端业务文案（如「该手机号已注册」）优先于泛化的 HTTP 错误描述
    toast.push(e?.data?.message || e?.message || (mode.value === 'login' ? '登录失败' : '注册失败'), 'err')
  } finally { busy.value = false }
}

async function qqLogin() {
  if (!qqCode.value) { toast.push('请输入 QQ 登录码', 'err'); return }
  busy.value = true
  try { await store.loginByQq(qqCode.value); toast.push('QQ登录成功'); router.replace(redirect ?? '/console') }
  catch (e: any) {
    toast.push(e?.data?.message || e?.message || 'QQ 登录失败', 'err')
  } finally { busy.value = false }
}
</script>

<template>
  <div class="login-page">
    <!-- 晨雾光斑：固定层（z-index:-1）铺满视口，页面容器不铺底色，光斑从登录卡后透出 -->
    <div class="aurora" aria-hidden="true"><i class="a1"></i><i class="a2"></i><i class="a3"></i></div>

    <Card class="login-card">
      <header class="brand-row">
        <img class="brand-avatar" :src="xiaopanAvatar" alt="记一笔 · 小盘" />
        <div>
          <h1>RinklNote</h1>
          <p class="sub">记账 · 资产 · AI 洞察</p>
        </div>
      </header>

      <div class="toggle" role="tablist" aria-label="登录或注册">
        <button type="button" role="tab" :aria-selected="mode === 'login'" :class="{ on: mode === 'login' }" @click="switchMode('login')">登录</button>
        <button type="button" role="tab" :aria-selected="mode === 'register'" :class="{ on: mode === 'register' }" @click="switchMode('register')">注册</button>
      </div>

      <form @submit.prevent="submit">
        <input v-model="phone" type="text" inputmode="numeric" placeholder="手机号" autocomplete="username" />
        <input v-model="pwd" type="password" placeholder="密码" autocomplete="current-password" />
        <p v-if="formErr" class="form-err" role="alert">{{ formErr }}</p>
        <Btn type="submit" block :loading="busy">{{ mode === 'login' ? '登录' : '注册' }}</Btn>
      </form>

      <hr />

      <div class="qq-area">
        <input v-model="qqCode" type="text" placeholder="QQ 登录码（向机器人发「登录」获取）" @keyup.enter="qqLogin" />
        <Btn variant="ghost" block :disabled="busy" @click="qqLogin">QQ 登录</Btn>
      </div>

      <button type="button" class="theme-btn" @click="theme.toggle()">{{ theme.theme === 'dark' ? '🌙 暗色' : '☀️ 亮色' }}</button>
    </Card>
  </div>
</template>

<style scoped>
/* 背景交给 body（var(--bg)）：本页不铺不透明底色，固定层 .aurora（z-index:-1）才透得出来 */
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 16px; }
.login-card { max-width: 380px; width: 100%; padding: 28px 24px; border-radius: 18px; display: flex; flex-direction: column; gap: 14px; }

.brand-row { display: flex; align-items: center; gap: 12px; }
.brand-avatar { width: 46px; height: 46px; border-radius: 12px; object-fit: cover; box-shadow: var(--shadow-sm); }
.brand-row h1 { font-size: 24px; margin: 0; letter-spacing: -0.01em; }
.brand-row .sub { color: var(--muted); margin: 2px 0 0; font-size: 13px; }

/* 登录/注册 tab：激活态主蓝（--primary），色与底 220ms 过渡（--dur-expand） */
.toggle { display: flex; gap: 4px; padding: 4px; border-radius: 12px; background: var(--surface-2); }
.toggle button {
  flex: 1; padding: 9px; border-radius: 9px; border: none; background: transparent;
  color: var(--muted); font-size: 14px; cursor: pointer; font-family: inherit;
  transition: background var(--dur-expand) var(--ease), color var(--dur-expand) var(--ease);
}
.toggle button.on { background: var(--primary); color: var(--on-primary); font-weight: 600; }

form { display: flex; flex-direction: column; gap: 12px; }
input {
  padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border);
  font-size: 16px; font-family: inherit; background: var(--card); color: var(--text);
  transition: border-color var(--dur-expand) var(--ease);
}
/* 输入 focus 态走全局 :focus-visible（主蓝焦点环），此处不重复定义 */
.form-err { margin: 0; color: var(--expense); font-size: 13px; }

hr { border: none; border-top: 1px solid var(--border-light); margin: 0; }
.qq-area { display: flex; flex-direction: column; gap: 12px; }

.theme-btn { align-self: flex-start; background: none; border: none; padding: 0; color: var(--muted); cursor: pointer; font-size: 13px; }
.theme-btn:hover { color: var(--primary); }

/* 移动端（<768px）：单列居中，卡片贴边留 16px 呼吸 */
@media (max-width: 767px) {
  .login-page { padding: 16px; }
  .login-card { max-width: 420px; }
}
</style>
