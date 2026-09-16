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
import xiaopanAvatar from '../assets/xiaopan-avatar.png'

/* 设计方向「清晨账本」：摊开的账本双页 —— 左页是品牌蓝叙事面板（账页横线纹理 +
 * 大字标语 + 真实演示分录），右页是表单。颜色只走 theme.css 令牌：
 * --primary 品牌面 / --on-primary 蓝底文字 / --expense 仅用于错误文案（收支色不挪作装饰）。 */

const router = useRouter()
const route = useRoute()
const store = useAuthStore()
const theme = useThemeStore()
const toast = useToast()

const mode = ref<'login' | 'register'>('login')
const phone = ref('')
const pwd = ref('')
const pwdVisible = ref(false) // 密码可见性切换（eye / eye-off）
const qqCode = ref('')
const busy = ref(false)
/* 表单内红字：本地校验错误就地展示；接口错误仍走全局 toast */
const formErr = ref('')

/* 401/掉登录后的回跳地址（guards.ts / http.ts 写入；仅接受站内路径）。
 * 注意：UI 重构后请保留该回跳逻辑（登录与 QQ 登录成功后都要用 redirect ?? '/console'）。 */
const redirect = sanitizeRedirect(route.query.redirect)

/* 大陆手机号：1 开头、第二位 3-9、共 11 位（与 App 登录页 LoginScreen.kt 同一规则） */
const PHONE_RE = /^1[3-9]\d{9}$/

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
    <!-- 晨雾光斑：固定层（z-index:-1）铺满视口，账本双页浮在光斑之上 -->
    <div class="aurora" aria-hidden="true"><i class="a1"></i><i class="a2"></i><i class="a3"></i></div>

    <!-- 主题切换：固定右上角，不占表单版面 -->
    <button type="button" class="theme-btn" :aria-label="theme.theme === 'dark' ? '切换为亮色' : '切换为暗色'" @click="theme.toggle()">
      <Icon :name="theme.theme === 'dark' ? 'sun' : 'moon'" :size="16" />
      <span>{{ theme.theme === 'dark' ? '亮色' : '暗色' }}</span>
    </button>

    <!-- 摊开的账本：左品牌页 + 右表单页 -->
    <div class="shell">
      <!-- ── 左页：品牌叙事（清晨天空蓝）── -->
      <section class="brand-panel">
        <header class="brand-row">
          <img class="brand-avatar" :src="xiaopanAvatar" alt="记一笔 · 小盘" />
          <div class="brand-text">
            <strong>RinklNote</strong>
            <span>记一笔</span>
          </div>
        </header>

        <div class="brand-body">
          <p class="display">每一笔，<br />都值得被看见。</p>
          <p class="lede">快速记账 · 资产净额 · AI 月结复盘，一个控制台全搞定。</p>

          <!-- 账页分录预览：真实演示数据，纯装饰 -->
          <div class="ledger" aria-hidden="true">
            <div class="ledger-title">账本长这样</div>
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

        <form @submit.prevent="submit">
          <label class="field-label" for="login-phone">手机号</label>
          <input id="login-phone" v-model="phone" type="text" inputmode="numeric" placeholder="13 位手机号" autocomplete="username" />

          <label class="field-label" for="login-pwd">密码</label>
          <div class="pwd-field">
            <input id="login-pwd" v-model="pwd" :type="pwdVisible ? 'text' : 'password'" placeholder="至少 6 位" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" />
            <button type="button" class="eye" :aria-label="pwdVisible ? '隐藏密码' : '显示密码'" @click="pwdVisible = !pwdVisible">
              <Icon :name="pwdVisible ? 'eye-off' : 'eye'" :size="16" />
            </button>
          </div>

          <p v-if="formErr" class="form-err" role="alert">{{ formErr }}</p>
          <Btn type="submit" block class="submit" :loading="busy">{{ mode === 'login' ? '登录' : '注册' }}</Btn>
        </form>

        <div class="divider" aria-hidden="true"><span>或</span></div>

        <div class="qq-area">
          <label class="field-label" for="login-qq">QQ 登录码</label>
          <input id="login-qq" v-model="qqCode" type="text" placeholder="向机器人发「登录」获取" @keyup.enter="qqLogin" />
          <Btn variant="ghost" block :disabled="busy" @click="qqLogin">QQ 登录</Btn>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* 页面底色交给 body（var(--bg)）：本页不铺不透明底色，固定层 .aurora（z-index:-1）透出 */
.login-page {
  min-height: 100vh;
  display: flex; align-items: center; justify-content: center;
  padding: 32px 16px;
}

/* 主题切换：右上角常驻 */
.theme-btn {
  position: fixed; top: 14px; right: 14px; z-index: 10;
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 12px; border-radius: 999px; border: 1px solid var(--border);
  background: var(--card); color: var(--muted); cursor: pointer; font-size: 13px; font-family: inherit;
  transition: color var(--dur-expand) var(--ease), border-color var(--dur-expand) var(--ease);
}
.theme-btn:hover { color: var(--primary); border-color: var(--primary); }

/* ── 摊开的账本双页：55/45 不对称分割，软投影一套体系 ── */
.shell {
  width: min(920px, 100%);
  display: grid; grid-template-columns: minmax(0, 11fr) minmax(0, 9fr);
  border-radius: 22px; overflow: hidden;
  box-shadow: 0 1px 2px rgba(0,0,0,.06), 0 24px 64px rgba(0,0,0,.16);
}

/* 左页：品牌蓝满版（--primary 恒定不随暗色反转，清晨天空感两态一致） */
.brand-panel {
  position: relative;
  display: flex; flex-direction: column; justify-content: space-between;
  padding: 28px 30px 24px;
  background-color: var(--primary);
  /* 账页横线纹理：on-primary 低透明度细线，呼应「印刷账本」秩序；
   * 叠一层卡片色高光（右上角破格）让蓝色面板有晨光层次 */
  background-image:
    repeating-linear-gradient(to bottom, transparent 0 35px, color-mix(in srgb, var(--on-primary) 13%, transparent) 35px 36px),
    radial-gradient(120% 90% at 85% -10%, color-mix(in srgb, var(--card) 24%, transparent), transparent 55%);
  color: var(--on-primary);
}
.brand-row { display: flex; align-items: center; gap: 12px; }
.brand-avatar { width: 44px; height: 44px; border-radius: 12px; object-fit: cover; }
.brand-text { display: flex; flex-direction: column; line-height: 1.2; }
.brand-text strong { font-size: 18px; letter-spacing: -0.02em; }
.brand-text span { font-size: 12px; opacity: .75; }

.brand-body { margin: auto 0; padding: 24px 0; }
/* 大字标语：与正文 ≥3 倍字号反差，紧字距 + 收紧行高 */
.display {
  margin: 0 0 12px;
  font-size: clamp(26px, 3.4vw, 40px);
  font-weight: 800; line-height: 1.15; letter-spacing: -0.03em;
}
.lede { margin: 0; font-size: 14px; line-height: 1.6; opacity: .82; max-width: 30ch; }

/* 账页分录预览：等宽数字（tabular-nums），行间横线由面板纹理承担 */
.ledger { margin-top: 26px; display: flex; flex-direction: column; gap: 9px; }
.ledger-title { font-size: 12px; font-weight: 600; opacity: .7; margin-bottom: 2px; }
.ledger-row {
  display: flex; align-items: baseline; gap: 10px;
  font-size: 13px; font-variant-numeric: tabular-nums; font-feature-settings: "tnum";
}
.e-when { opacity: .62; flex-shrink: 0; width: 76px; }
.e-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.e-amt { flex-shrink: 0; font-weight: 700; }

.panel-foot { font-size: 12px; opacity: .62; }

/* 右页：表单（卡片面，随深浅主题走 --card/--text） */
.form-panel {
  background: var(--card);
  padding: 32px 30px 26px;
  display: flex; flex-direction: column;
}
.form-title { margin: 0; font-size: 24px; font-weight: 800; letter-spacing: -0.02em; color: var(--text); }
.form-sub { margin: 6px 0 20px; font-size: 13px; color: var(--muted); }

/* 滑块式 tab：滑块（--primary）随 mode 平移，激活字色 on-primary */
.toggle {
  position: relative; display: flex; gap: 0;
  padding: 4px; border-radius: 12px; background: var(--surface-2);
  margin-bottom: 18px;
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

form { display: flex; flex-direction: column; }
.field-label { font-size: 12px; font-weight: 600; color: var(--muted); margin: 0 0 6px; }
input {
  padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border);
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

.submit { margin-top: 4px; padding: 13px 16px; font-size: 15px; }

/* 分隔线 + 居中「或」：编辑排版式的细规则线 */
.divider { display: flex; align-items: center; gap: 12px; margin: 20px 0 16px; color: var(--muted); font-size: 12px; }
.divider::before, .divider::after { content: ''; flex: 1; height: 1px; background: var(--border-light); }

.qq-area { display: flex; flex-direction: column; }
.qq-area .field-label { margin-top: 0; }

/* ── 进场 stagger：账本元素依次错落揭示（theme.css 全局 reduced-motion 规则会冻结） ── */
@media (prefers-reduced-motion: no-preference) {
  .display, .lede, .ledger, .panel-foot,
  .form-title, .toggle, form, .divider, .qq-area {
    animation: rise .55s cubic-bezier(.22, 1, .36, 1) both;
  }
  .lede { animation-delay: .06s; }
  .ledger { animation-delay: .12s; }
  .panel-foot { animation-delay: .18s; }
  .form-title { animation-delay: .05s; }
  .toggle { animation-delay: .1s; }
  form { animation-delay: .15s; }
  .divider { animation-delay: .2s; }
  .qq-area { animation-delay: .25s; }
  @keyframes rise { from { opacity: 0; transform: translateY(14px); } }
}

/* ── 移动端（<900px）：账本竖排，品牌页压成顶部横幅 ── */
@media (max-width: 899px) {
  .login-page { padding: 20px 14px; align-items: flex-start; }
  .shell { grid-template-columns: 1fr; }
  .brand-panel { padding: 22px 22px 20px; gap: 18px; }
  .ledger, .panel-foot { display: none; }
  .brand-body { padding: 4px 0 0; }
  .display { font-size: clamp(24px, 7vw, 32px); }
  .lede { max-width: none; }
  .form-panel { padding: 24px 22px 22px; }
}
</style>
