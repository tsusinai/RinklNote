<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useToast } from '../composables/useToast'
import { useThemeStore } from '../stores/theme'
import { sanitizeRedirect } from '../router/guards'
import Btn from '../components/ui/Btn.vue'
import Icon from '../components/ui/Icon.vue'
import { formatSigned } from '../utils/money'
import { demoDays } from '../mock/demo-data'

/* 设计方向「编辑部式财务工作台」：深墨色品牌页 + 米白表单页。
 * 背景只保留低频、低对比度的光带与网格动效，避免二次元插画和过度装饰。 */

const router = useRouter()
const route = useRoute()
const store = useAuthStore()
const theme = useThemeStore()
const toast = useToast()

const mode = ref<'login' | 'register'>('login')
/* 登录身份（2026-09-17 优化登录方式）：手机号 | 邮箱 同位切换，与 App 登录页同构。
 * 邮箱模式下第一个输入行变为邮箱，提交时 phone 传空串、email 非空（服务端据此选身份）。 */
const identity = ref<'phone' | 'email'>('phone')
const phone = ref('')
const email = ref('')
const pwd = ref('')
const pwdVisible = ref(false) // 密码可见性切换（eye / eye-off）
const busy = ref(false)
/* 表单内红字：本地校验错误就地展示；接口错误仍走全局 toast */
const formErr = ref('')

/* 401/掉登录后的回跳地址（guards.ts / http.ts 写入；仅接受站内路径）。
 * 注意：UI 重构后请保留该回跳逻辑（登录成功后都要用 redirect ?? '/console'）。 */
const redirect = sanitizeRedirect(route.query.redirect)

/* 大陆手机号：1 开头、第二位 3-9、共 11 位（与 App 登录页 LoginScreen.kt 同一规则） */
const PHONE_RE = /^1[3-9]\d{9}$/
/* 标准邮箱（仅客户端提示用，服务端有兜底校验；与 App LoginScreen.kt 同一规则） */
const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/

/* 标题/副标题随登录↔注册切换，给足上下文 */
const title = computed(() => (mode.value === 'login' ? '欢迎回来' : '开一本新账'))
const subtitle = computed(() => (mode.value === 'login' ? '登录后继续记账、看资产与 AI 洞察' : '注册即自动登录，30 秒记下第一笔'))

/* 品牌面板的演示分录（纯装饰，aria-hidden）：取自落地页同一份 mock 数据，
 * 金额走 formatSigned 契约（如「-¥25.00」），与三端整数分约定一致 */
const entries = demoDays.map((d) => {
  const b = d.bills[0]
  return {
    when: `${d.label} ${b.time}`,
    name: b.subCategory ? `${b.category} · ${b.subCategory}` : b.category,
    signed: formatSigned(-b.amountMinor),
  }
})

function switchMode(m: 'login' | 'register') {
  mode.value = m
  formErr.value = ''
  pwdVisible.value = false
}

function switchIdentity(v: 'phone' | 'email') {
  identity.value = v
  formErr.value = ''
}

async function submit() {
  formErr.value = ''
  /* 邮箱模式校验邮箱、手机号模式校验手机号（文案与 App 登录页一致） */
  if (identity.value === 'email') {
    if (!email.value.trim() || !pwd.value) { formErr.value = '邮箱或密码不能为空'; return }
    if (!EMAIL_RE.test(email.value.trim())) { formErr.value = '请输入正确的邮箱地址'; return }
  } else {
    if (!phone.value.trim() || !pwd.value) { formErr.value = '手机号或密码不能为空'; return }
    if (!PHONE_RE.test(phone.value.trim())) { formErr.value = '请输入正确的 11 位手机号'; return }
  }
  /* 密码规则（2026-09-17）：新设定须 ≥6 位且同时含大小写字母，与服务端 PasswordPolicy 同构；
   * 仅注册约束，登录只要求非空（存量老密码不受影响）。 */
  if (mode.value === 'register' && !/^(?=.*[a-z])(?=.*[A-Z]).{6,}$/.test(pwd.value)) {
    formErr.value = '密码需至少6位且包含大小写字母'
    return
  }
  busy.value = true
  try {
    if (identity.value === 'email') {
      /* 邮箱身份：phone 传空串、email 非空（api/auth.ts 组装请求体，服务端 AuthRoutes.kt 约定） */
      const mail = email.value.trim()
      if (mode.value === 'login') await store.login('', pwd.value, mail)
      else await store.register('', pwd.value, mail)
    } else if (mode.value === 'login') {
      await store.login(phone.value.trim(), pwd.value)
    } else {
      /* 修硬伤：注册分支此前复制了登录逻辑（调 store.login），register API 从未被调用。
       * 现改为真正调 register —— 服务端 201 返回同结构 token，注册成功即自动登录进控制台。 */
      await store.register(phone.value.trim(), pwd.value)
    }
    toast.push(mode.value === 'login' ? '登录成功' : '注册成功')
    router.replace(redirect ?? '/console')
  } catch (e: any) {
    // 服务端业务文案（如「该手机号已注册」）优先于泛化的 HTTP 错误描述
    toast.push(e?.data?.message || e?.message || (mode.value === 'login' ? '登录失败' : '注册失败'), 'err')
  } finally { busy.value = false }
}
</script>

<template>
  <div class="login-page">
    <!-- 背景动效：低频光带 + 网格纹理，不影响表单阅读 -->
    <div class="aurora" aria-hidden="true"><i class="a1"></i><i class="a2"></i><i class="a3"></i></div>

    <!-- 主题切换：固定右上角，不占表单版面 -->
    <button type="button" class="theme-btn" :aria-label="theme.theme === 'dark' ? '切换为亮色' : '切换为暗色'" @click="theme.toggle()">
      <Icon :name="theme.theme === 'dark' ? 'sun' : 'moon'" :size="16" />
      <span>{{ theme.theme === 'dark' ? '亮色' : '暗色' }}</span>
    </button>

    <!-- 摊开的账本：左品牌页 + 右表单页 -->
    <div class="shell">
      <!-- ── 左页：品牌叙事 ── -->
      <section class="brand-panel">
        <header class="brand-row">
          <svg class="brand-mark" viewBox="0 0 40 40" aria-hidden="true">
            <path d="M8 10h24v7H18v6h14v7H8V10Z" />
            <path d="M8 10v20" class="mark-cut" />
          </svg>
          <div class="brand-text">
            <strong>RinklNote</strong>
            <span>个人财务工作台</span>
          </div>
        </header>

        <div class="brand-body">
          <p class="display">把每一笔，<br />记得更清楚。</p>
          <p class="lede">快速记账、资产净额与月度复盘，所有重要数字都在同一张桌面上。</p>

          <!-- 账页分录预览：真实演示数据，纯装饰 -->
          <div class="ledger" aria-hidden="true">
            <div class="ledger-title">最近记录</div>
            <div v-for="e in entries" :key="e.when" class="ledger-row">
              <span class="e-when">{{ e.when }}</span>
              <span class="e-name">{{ e.name }}</span>
              <span class="e-amt">{{ e.signed }}</span>
            </div>
          </div>
        </div>

        <footer class="panel-foot">本地优先 · 深浅双主题 · App / Web / 机器人三端同步</footer>
      </section>

      <!-- ── 右页：表单 ── -->
      <section class="form-panel">
        <h1 class="form-title">{{ title }}</h1>
        <p class="form-sub">{{ subtitle }}</p>

        <!-- 登录/注册 tab：滑块式切换（.thumb 随 mode 平移） -->
        <div class="toggle" role="tablist" aria-label="登录或注册" :class="mode">
          <span class="thumb" aria-hidden="true"></span>
          <button type="button" role="tab" :aria-selected="mode === 'login'" :class="{ on: mode === 'login' }" @click="switchMode('login')">登录</button>
          <button type="button" role="tab" :aria-selected="mode === 'register'" :class="{ on: mode === 'register' }" @click="switchMode('register')">注册</button>
        </div>

        <!-- 身份同位切换：手机号 | 邮箱（2026-09-17 优化登录方式，与 App 登录页同构） -->
        <div class="id-toggle" role="tablist" aria-label="登录身份">
          <button type="button" role="tab" :aria-selected="identity === 'phone'" :class="{ on: identity === 'phone' }" @click="switchIdentity('phone')">手机号</button>
          <button type="button" role="tab" :aria-selected="identity === 'email'" :class="{ on: identity === 'email' }" @click="switchIdentity('email')">邮箱</button>
        </div>

        <form @submit.prevent="submit">
          <template v-if="identity === 'phone'">
            <label class="field-label" for="login-phone">手机号</label>
            <input id="login-phone" v-model="phone" type="text" inputmode="numeric" placeholder="11 位手机号" autocomplete="username" />
          </template>
          <template v-else>
            <label class="field-label" for="login-email">邮箱</label>
            <input id="login-email" v-model="email" type="email" inputmode="email" placeholder="name@example.com" autocomplete="username" />
          </template>

          <label class="field-label" for="login-pwd">密码</label>
          <div class="pwd-field">
            <input id="login-pwd" v-model="pwd" :type="pwdVisible ? 'text' : 'password'" :placeholder="identity === 'email' ? '至少 6 位，含大小写字母' : '至少 6 位'" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" />
            <button type="button" class="eye" :aria-label="pwdVisible ? '隐藏密码' : '显示密码'" @click="pwdVisible = !pwdVisible">
              <Icon :name="pwdVisible ? 'eye-off' : 'eye'" :size="16" />
            </button>
          </div>

          <p v-if="formErr" class="form-err" role="alert">{{ formErr }}</p>
          <Btn type="submit" block class="submit" :loading="busy">{{ mode === 'login' ? '登录' : '注册' }}</Btn>
        </form>

        <p class="form-hint">忘记密码？暂未开放自助找回，请联系管理员重置。</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative; isolation: isolate; min-height: 100vh;
  display: flex; align-items: center; justify-content: center;
  padding: 48px 20px;
  overflow: hidden;
}
.login-page::before {
  content: ''; position: absolute; inset: 0; pointer-events: none; opacity: .55;
  background-image: linear-gradient(color-mix(in srgb, var(--primary) 10%, transparent) 1px, transparent 1px), linear-gradient(90deg, color-mix(in srgb, var(--primary) 10%, transparent) 1px, transparent 1px);
  background-size: 56px 56px; mask-image: linear-gradient(to bottom, transparent, black 18%, black 82%, transparent);
}
.login-page::after {
  content: ''; position: absolute; z-index: 0; pointer-events: none;
  width: 72vw; height: 18vw; left: -18vw; top: 12%;
  border-radius: 50%; filter: blur(42px); opacity: .22;
  background: linear-gradient(105deg, transparent 8%, color-mix(in srgb, var(--primary) 45%, transparent) 46%, transparent 82%);
  transform: rotate(-12deg); animation: login-sweep 18s cubic-bezier(.4, 0, .2, 1) infinite;
}
.login-page .aurora { position: absolute; inset: -15%; z-index: -1; opacity: .88; }
.login-page .aurora i { filter: blur(76px); }
.login-page .aurora .a1 { animation-duration: 34s; }
.login-page .aurora .a2 { animation-duration: 42s; }
.login-page .aurora .a3 { animation-duration: 48s; }
@keyframes login-sweep {
  0%, 100% { transform: translate3d(-8%, 0, 0) rotate(-12deg); opacity: .12; }
  50% { transform: translate3d(92%, 16%, 0) rotate(-7deg); opacity: .28; }
}

/* 主题切换：右上角常驻 */
.theme-btn {
  position: fixed; top: 14px; right: 14px; z-index: 10;
  display: inline-flex; align-items: center; gap: 6px;
  padding: 9px 12px; border-radius: 9px; border: 1px solid var(--border);
  background: color-mix(in srgb, var(--card) 82%, transparent); color: var(--muted); cursor: pointer; font-size: 13px; font-family: inherit;
  backdrop-filter: blur(12px); transition: color var(--dur-expand) var(--ease), border-color var(--dur-expand) var(--ease), transform var(--dur-press) var(--ease);
}
.theme-btn:hover { color: var(--primary); border-color: var(--primary); }
.theme-btn:active { transform: scale(.97); }

/* ── 摊开的账本双页：55/45 不对称分割，软投影一套体系 ── */
.shell {
  position: relative; z-index: 1; width: min(980px, 100%);
  display: grid; grid-template-columns: minmax(0, 11fr) minmax(0, 9fr);
  border: 1px solid color-mix(in srgb, var(--border) 80%, transparent); border-radius: 16px; overflow: hidden;
  box-shadow: 0 1px 2px rgba(0,0,0,.06), 0 28px 80px rgba(16,34,43,.16);
}

/* 左页：品牌蓝满版（--primary 恒定不随暗色反转，清晨天空感两态一致） */
.brand-panel {
  position: relative;
  display: flex; flex-direction: column; justify-content: space-between;
  padding: 34px 36px 28px;
  background-color: #172b35;
  background-image:
    linear-gradient(135deg, color-mix(in srgb, var(--primary) 18%, transparent), transparent 48%),
    repeating-linear-gradient(to bottom, transparent 0 39px, rgba(163, 207, 226, .1) 39px 40px);
  color: #f4f8f9;
}
.brand-row { display: flex; align-items: center; gap: 12px; }
.brand-mark { width: 42px; height: 42px; flex: none; fill: var(--primary); }
.brand-mark .mark-cut { stroke: #172b35; stroke-width: 2; }
.brand-text { display: flex; flex-direction: column; line-height: 1.2; }
.brand-text strong { font-size: 18px; letter-spacing: -0.02em; }
.brand-text span { margin-top: 4px; font-size: 11px; color: #a9c0c9; letter-spacing: .08em; }

.brand-body { margin: auto 0; padding: 24px 0; }
/* 大字标语：与正文 ≥3 倍字号反差，紧字距 + 收紧行高 */
.display {
  margin: 0 0 12px;
  font-size: clamp(30px, 3.8vw, 48px);
  font-weight: 800; line-height: 1.08; letter-spacing: -0.045em;
}
.lede { margin: 0; font-size: 14px; line-height: 1.75; color: #c4d5da; max-width: 30ch; }

/* 账页分录预览：等宽数字（tabular-nums），行间横线由面板纹理承担 */
.ledger { margin-top: 32px; display: flex; flex-direction: column; gap: 9px; }
.ledger-title { font-size: 11px; font-weight: 700; color: #94b1ba; letter-spacing: .12em; margin-bottom: 4px; }
.ledger-row {
  display: flex; align-items: baseline; gap: 10px;
  font-size: 13px; font-variant-numeric: tabular-nums; font-feature-settings: "tnum";
}
.e-when { opacity: .62; flex-shrink: 0; width: 76px; }
.e-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.e-amt { flex-shrink: 0; font-weight: 700; }

.panel-foot { font-size: 11px; color: #8ea8b1; }

/* 右页：表单（卡片面，随深浅主题走 --card/--text） */
.form-panel {
  background: var(--card);
  padding: 40px 38px 32px;
  display: flex; flex-direction: column;
}
.form-title { margin: 0; font-size: 28px; font-weight: 800; letter-spacing: -0.04em; color: var(--text); }
.form-sub { margin: 8px 0 24px; font-size: 13px; color: var(--muted); line-height: 1.6; }

/* 滑块式 tab：滑块（--primary）随 mode 平移，激活字色 on-primary */
.toggle {
  position: relative; display: flex; gap: 0;
  padding: 4px; border-radius: 9px; background: var(--surface-2);
  margin-bottom: 16px;
}
.toggle .thumb {
  position: absolute; top: 4px; bottom: 4px; left: 4px;
  width: calc(50% - 4px); border-radius: 9px; background: var(--primary);
  transition: transform var(--dur-expand) var(--ease);
}
.toggle.register .thumb { transform: translateX(100%); }
.toggle button {
  position: relative; z-index: 1;
  flex: 1; padding: 9px; border-radius: 9px; border: none; background: transparent;
  color: var(--muted); font-size: 14px; cursor: pointer; font-family: inherit;
  transition: color var(--dur-expand) var(--ease);
}
.toggle button.on { color: var(--on-primary); font-weight: 600; }

/* 身份同位切换（手机号 | 邮箱）：比登录/注册 tab 更轻的次级样式，选中项主蓝描底 */
.id-toggle {
  display: flex; gap: 6px; margin-bottom: 22px;
}
.id-toggle button {
  flex: 1; padding: 9px 0; border-radius: 8px;
  border: 1px solid var(--border); background: transparent;
  color: var(--muted); font-size: 13px; cursor: pointer; font-family: inherit;
  transition: color var(--dur-expand) var(--ease), border-color var(--dur-expand) var(--ease),
    background var(--dur-expand) var(--ease);
}
.id-toggle button.on {
  color: var(--primary); border-color: var(--primary); background: var(--primary-soft);
  font-weight: 600;
}
.id-toggle button + button { margin-left: 0; }

form { display: flex; flex-direction: column; }
.field-label { font-size: 12px; font-weight: 600; color: var(--muted); margin: 0 0 6px; }
input {
  padding: 13px 14px; border-radius: 8px; border: 1px solid var(--border);
  font-size: 16px; font-family: inherit; background: var(--card); color: var(--text);
  transition: border-color var(--dur-expand) var(--ease), box-shadow var(--dur-expand) var(--ease);
  margin-bottom: 14px;
}
/* 聚焦意图：主蓝描边 + 主蓝软光环（键盘可达性由全局 :focus-visible 焦点环兜底） */
input:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); }
input::placeholder { color: var(--muted); opacity: .7; }

/* 密码框 + 可见性切换 */
.pwd-field { position: relative; }
.pwd-field input { width: 100%; padding-right: 44px; }
.eye {
  position: absolute; right: 6px; top: 5px;
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border-radius: 9px;
  border: none; background: none; color: var(--muted); cursor: pointer;
  transition: color var(--dur-fast) var(--ease);
}
.eye:hover { color: var(--primary); }

/* 错误反馈：--expense 只用于错误语义（收支色不挪作装饰） */
.form-err { margin: 0 0 12px; color: var(--expense); font-size: 13px; }

.submit { margin-top: 6px; padding: 13px 16px; font-size: 15px; border-radius: 8px; }

/* 忘记密码占位提示（QQ 登录区移除后的底部收尾文案，弱化呈现） */
.form-hint { margin: 14px 0 0; font-size: 12px; color: var(--muted); opacity: .8; }

/* ── 进场 stagger：账本元素依次错落揭示（theme.css 全局 reduced-motion 规则会冻结） ── */
@media (prefers-reduced-motion: no-preference) {
  .display, .lede, .ledger, .panel-foot,
  .form-title, .toggle, .id-toggle, form, .form-hint {
    animation: rise .55s cubic-bezier(.22, 1, .36, 1) both;
  }
  .lede { animation-delay: .06s; }
  .ledger { animation-delay: .12s; }
  .panel-foot { animation-delay: .18s; }
  .form-title { animation-delay: .05s; }
  .toggle { animation-delay: .1s; }
  .id-toggle { animation-delay: .13s; }
  form { animation-delay: .15s; }
  .form-hint { animation-delay: .2s; }
  @keyframes rise { from { opacity: 0; transform: translateY(14px); } }
}
@media (prefers-reduced-motion: reduce) {
  .login-page::after { animation: none; opacity: .14; }
}

/* ── 移动端（<900px）：账本竖排，品牌页压成顶部横幅 ── */
@media (max-width: 899px) {
  .login-page { padding: 20px 14px 28px; align-items: flex-start; }
  .shell { grid-template-columns: 1fr; }
  .brand-panel { padding: 24px 22px 22px; gap: 18px; }
  .ledger, .panel-foot { display: none; }
  .brand-body { padding: 4px 0 0; }
  .display { font-size: clamp(26px, 8vw, 36px); }
  .lede { max-width: none; }
  .form-panel { padding: 28px 22px 24px; }
}
</style>
