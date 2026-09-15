<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { auth } from '../../api/auth'
import { useToast } from '../../composables/useToast'
import { useDataStore } from '../../stores/data'
import ConfirmDialog from '../../components/ui/ConfirmDialog.vue'
import EmptyState from '../../components/ui/EmptyState.vue'
import Skeleton from '../../components/ui/Skeleton.vue'
import Card from '../../components/ui/Card.vue'
import Btn from '../../components/ui/Btn.vue'

const authStore = useAuthStore()
const data = useDataStore()
const router = useRouter()
const toast = useToast()

const me = ref(authStore.user)
const qqNumber = ref('')
const oldPwd = ref('')
const newPwd = ref('')
const busy = ref(false)
const pwdMsg = ref('')
const initing = ref(false)

/* 确认弹窗状态：解绑 QQ / 退出登录（替换原 window.confirm） */
const pendingUnbind = ref(false)
const unbinding = ref(false)
const pendingLogout = ref(false)

onMounted(async () => {
  if (authStore.token) {
    initing.value = true
    try { await authStore.refresh(); me.value = authStore.user } finally { initing.value = false }
  }
})

/* ── 身份卡口径 ── */
// 手机号掩码：保留前 3 后 4，其余打码；非 11 位按原样返回
const maskedPhone = computed(() => {
  const p = me.value?.phone || ''
  return /^\d{11}$/.test(p) ? `${p.slice(0, 3)}****${p.slice(7)}` : p
})
// 来源标签：有手机号 → 手机号注册，否则视为 QQ 来源
const sourceLabel = computed(() => (me.value?.phone ? '手机号' : 'QQ'))
// 昵称展示：优先手机号掩码，QQ 来源无手机号时给占位昵称
const displayName = computed(() => maskedPhone.value || 'QQ 用户')
const avatarChar = computed(() => displayName.value.slice(0, 1).toUpperCase())

async function bindQq() {
  if (!qqNumber.value.trim()) { toast.push('请输入QQ号', 'err'); return }
  busy.value = true
  try { await auth.bindQq(qqNumber.value.trim()); toast.push('已绑定'); qqNumber.value = ''; await refreshMe() }
  catch (e: any) { toast.push(e?.message || '绑定失败', 'err') }
  finally { busy.value = false }
}
async function confirmUnbind() {
  unbinding.value = true
  try { await auth.unbindQq(); toast.push('已解绑'); pendingUnbind.value = false; await refreshMe() }
  catch (e: any) { toast.push(e?.message || '解绑失败', 'err') }
  finally { unbinding.value = false }
}
async function changePwd() {
  if (newPwd.value.length < 6) { pwdMsg.value = '新密码至少6位'; return }
  busy.value = true
  try {
    await auth.changePassword(oldPwd.value, newPwd.value)
    pwdMsg.value = '密码修改成功'
    oldPwd.value = ''; newPwd.value = ''; await refreshMe()
  } catch (e: any) { pwdMsg.value = e?.message || '修改失败' }
  finally { busy.value = false }
}
async function refreshMe() { await authStore.refresh(); me.value = authStore.user }
// 退出登录：统一走 W2 底部入口卡，确认弹窗后清缓存跳登录
function logout() { pendingLogout.value = true }
function doLogout() {
  authStore.logout()
  localStorage.removeItem('rkl_token')
  localStorage.removeItem('rkl_theme')
  // 清空控制台缓存
  data.bills = []; data.cats = []; data.accts = []; data.keywords = []; data.lastSync = 0
  router.push('/login')
}
</script>

<template>
  <div class="page">
    <h2>我的</h2>
    <template v-if="initing">
      <Card><Skeleton height="56px" width="70%" /><Skeleton height="14px" width="40%" /></Card>
    </template>
    <template v-else-if="!me">
      <EmptyState icon="user" text="登录后管理账号" hint="登录后可修改密码与绑定QQ" />
    </template>
    <template v-else>
      <!-- 身份卡：头像占位 + 昵称/掩码 + 来源标签 -->
      <div v-reveal class="card identity">
        <div class="avatar" aria-hidden="true">{{ avatarChar }}</div>
        <div class="id-meta">
          <div class="id-name">{{ displayName }}</div>
          <div class="id-sub">
            <span :class="['tag', me.phone ? 'tag-app' : 'tag-qq']">{{ sourceLabel }}</span>
            <span v-if="me.createdAt" class="id-joined">注册于 {{ me.createdAt.slice(0, 10) }}</span>
          </div>
        </div>
      </div>

      <div v-reveal="60" class="card">
        <div class="card-title">QQ号绑定</div>
        <div v-if="me.qqNumber">
          <div class="row"><span class="k">已绑定 QQ号</span><span class="v">{{ me.qqNumber }}</span></div>
          <div class="row"><span class="k">QQ机器人</span><span class="v">{{ me.qqOpenid ? '已绑定 …' + me.qqOpenid.slice(-6) : '未绑定' }}</span></div>
          <Btn variant="ghost" @click="pendingUnbind = true">解绑QQ号</Btn>
        </div>
        <div v-else>
          <p class="hint">绑定QQ号后可通过QQ机器人快捷记账</p>
          <input class="sel" placeholder="QQ号" v-model="qqNumber" />
          <Btn :disabled="busy" @click="bindQq">绑定QQ</Btn>
        </div>
      </div>

      <div v-reveal="120" class="card">
        <div class="card-title">修改密码</div>
        <input class="sel" type="password" placeholder="原密码" v-model="oldPwd" />
        <input class="sel" type="password" placeholder="新密码(至少6位)" v-model="newPwd" />
        <div v-if="pwdMsg" :class="['msg', /原密码错误/.test(pwdMsg) ? 'err' : 'ok']">{{ pwdMsg }}</div>
        <Btn :disabled="busy" @click="changePwd">修改密码</Btn>
      </div>
    </template>

    <!-- 结构入口：设置 / 退出行（移动端无侧栏，统一从这里进；样式从简，视觉精修在 W3。
         注意：W3b 改造身份卡/视觉时请保留本块结构，勿并回上方 v-else） -->
    <div class="card">
      <router-link class="row link" to="/console/settings"><span class="k">设置</span><span class="v arrow">进入 ›</span></router-link>
      <button class="row link row-btn" @click="logout"><span class="k text-expense">退出登录</span><span class="v arrow">退出 ›</span></button>
    </div>

    <!-- 解绑 QQ 二次确认 -->
    <ConfirmDialog
      :open="pendingUnbind"
      title="解绑QQ号"
      message="确定解绑当前QQ号？解绑后将无法通过QQ机器人快捷记账。"
      confirm-text="解绑"
      danger
      :loading="unbinding"
      @close="pendingUnbind = false"
      @confirm="confirmUnbind"
    />
    <!-- 退出登录二次确认 -->
    <ConfirmDialog
      :open="pendingLogout"
      title="退出登录"
      message="退出将清除本地缓存数据，确定退出？"
      confirm-text="退出"
      danger
      @close="pendingLogout = false"
      @confirm="pendingLogout = false; doLogout()"
    />
  </div>
</template>

<style scoped>
.page { max-width: 760px; }
/* 卡片纵向间距（视觉样式走全局 .card 公共类，页面只管排） */
.card { margin-bottom: 14px; }

/* 身份卡：头像占位圆形 + 昵称 + 来源标签 */
.identity { display: flex; align-items: center; gap: 14px; }
.avatar {
  width: 56px; height: 56px; border-radius: 50%; flex: none;
  display: grid; place-items: center;
  background: var(--primary-soft); color: var(--primary);
  font-size: 22px; font-weight: 700;
}
.id-meta { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.id-name { font-size: 17px; font-weight: 700; }
.id-sub { display: flex; align-items: center; gap: 10px; }
.id-joined { color: var(--muted); font-size: 12px; }
.tag { padding: 2px 8px; border-radius: 6px; font-size: 12px; }

.row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border-light); }
.row:last-of-type { border-bottom: none; }
.k { color: var(--muted); }
.v { font-weight: 500; }
.row.link { cursor: pointer; }
.arrow { color: var(--primary); }
/* 结构行按钮（退出行）：对齐 .row 的排版，去掉按钮默认样式（W2 原样保留） */
.row-btn { width: 100%; background: none; border: none; font-family: inherit; font-size: 14px; cursor: pointer; padding: 8px 0; text-align: left; }
.hint { color: var(--muted); font-size: 13px; margin: 0 0 8px; }
.sel { width: 100%; padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 16px; margin-bottom: 10px; }
.msg { margin: 6px 0; font-size: 14px; }
.msg.ok { color: var(--income); }
.msg.err { color: var(--expense); }
</style>
