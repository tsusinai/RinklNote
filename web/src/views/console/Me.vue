<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { auth } from '../../api/auth'
import { useToast } from '../../composables/useToast'
import { useDataStore } from '../../stores/data'

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

onMounted(async () => {
  if (authStore.token) { await authStore.refresh(); me.value = authStore.user }
})

async function bindQq() { setBusy(); await auth.bindQq(qqNumber.value); toast.push('已绑定'); await refreshMe(); resetBusy() }
async function unbindQq() { if (confirm('解绑QQ号？')) { await auth.unbindQq(); toast.push('已解绑'); await refreshMe() } }
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
function setBusy() { busy.value = true }
function resetBusy() { busy.value = false }
function logout() {
  if (confirm('退出将清除本地缓存数据')) {
    authStore.logout()
    localStorage.removeItem('rkl_token')
    localStorage.removeItem('rkl_theme')
    // 清空控制台缓存
    data.bills = []; data.cats = []; data.accts = []; data.keywords = []; data.lastSync = 0
    router.push('/login')
  }
}
</script>

<template>
  <div class="page">
    <h2>我的</h2>
    <template v-if="!me">
      <div class="card empty">登录后管理账号、修改密码与绑定QQ</div>
    </template>
    <template v-else>
      <div class="card">
        <div class="card-title">账号信息</div>
        <div class="row"><span class="k">手机号</span><span class="v">{{ me.phone || 'QQ账号' }}</span></div>
        <div class="row"><span class="k">QQ号</span><span class="v">{{ me.qqNumber || '未绑定' }}</span></div>
        <div class="row"><span class="k">QQ机器人</span><span class="v">{{ me.qqOpenid ? '已绑定 …' + me.qqOpenid.slice(-6) : '未绑定' }}</span></div>
        <div class="row"><span class="k">注册时间</span><span class="v">{{ (me.createdAt || '').slice(0, 10) }}</span></div>
      </div>

      <div class="card">
        <div class="card-title">QQ号绑定</div>
        <div v-if="me.qqNumber">
          <div class="row"><span class="k">已绑定 QQ号</span><span class="v">{{ me.qqNumber }}</span></div>
          <button class="btn ghost" @click="unbindQq">解绑QQ号</button>
        </div>
        <div v-else>
          <p class="hint">绑定QQ号后可通过QQ机器人快捷记账</p>
          <input class="sel" placeholder="QQ号" v-model="qqNumber" />
          <button class="btn primary" :disabled="busy" @click="bindQq">绑定QQ</button>
        </div>
      </div>

      <div class="card">
        <div class="card-title">修改密码</div>
        <input class="sel" type="password" placeholder="原密码" v-model="oldPwd" />
        <input class="sel" type="password" placeholder="新密码(至少6位)" v-model="newPwd" />
        <div v-if="pwdMsg" :class="['msg', /原密码错误/.test(pwdMsg) ? 'err' : 'ok']">{{ pwdMsg }}</div>
        <button class="btn primary" :disabled="busy" @click="changePwd">修改密码</button>
      </div>

      <div class="card">
        <div class="card-title">设置</div>
        <router-link class="row link" to="/console/settings"><span class="k">设置</span><span class="v arrow">进入 ›</span></router-link>
      </div>

      <div class="card">
        <button class="btn danger block" @click="logout">退出登录</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page { max-width: 760px; }
.card { background: var(--card); border-radius: var(--radius); padding: 18px; margin-bottom: 14px; box-shadow: var(--shadow-sm); }
.card-title { font-weight: 600; margin-bottom: 12px; }
.row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border-light); }
.row:last-child { border-bottom: none; }
.k { color: var(--muted); }
.v { font-weight: 500; }
.row.link { cursor: pointer; }
.arrow { color: var(--primary); }
.hint { color: var(--muted); font-size: 13px; margin: 0 0 8px; }
.sel { width: 100%; padding: 12px 14px; border-radius: 12px; border: 1px solid var(--border); background: var(--card); color: var(--text); font-size: 16px; margin-bottom: 10px; }
.btn { padding: 11px 16px; border-radius: 10px; border: none; font-size: 14px; cursor: pointer; }
.btn.primary { background: var(--primary); color: #0b2b44; font-weight: 600; }
.btn.ghost { background: var(--card); color: var(--text); border: 1px solid var(--border); }
.btn.danger { background: var(--expense); color: #fff; }
.block { width: 100%; }
.msg { margin: 6px 0; font-size: 14px; }
.msg.ok { color: var(--income); }
.msg.err { color: var(--expense); }
.empty { text-align: center; color: var(--muted); padding: 32px; }
</style>
