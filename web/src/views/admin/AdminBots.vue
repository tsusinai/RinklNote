<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getAdminBotStatus, type AdminBotStatus } from '../../api/admin'
import Card from '../../components/ui/Card.vue'
import EmptyState from '../../components/ui/EmptyState.vue'
import Skeleton from '../../components/ui/Skeleton.vue'

/* 机器人运维：状态卡（只读）+ 配置入口指引（配置写操作在用户控制台「设置 → 多通道机器人」，
 * 保存成功后服务端 restartGateway 热重连——本期管理端全只读，无写路径）。 */
const status = ref<AdminBotStatus | null>(null)
const loading = ref(true)
const failed = ref(false)

async function load() {
  loading.value = true
  failed.value = false
  try {
    status.value = await getAdminBotStatus()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)

function fmtExpiry(sec: number | null): string {
  if (!sec) return '—'
  const d = new Date(sec * 1000)
  if (isNaN(d.getTime())) return String(sec)
  const remain = Math.round((sec * 1000 - Date.now()) / 60000)
  return `${d.toLocaleString('zh-CN', { hour12: false })}（剩 ${remain} 分钟）`
}
</script>

<template>
  <div>
    <template v-if="loading">
      <Skeleton height="120px" style="margin-bottom: 12px" />
      <Skeleton height="90px" />
    </template>
    <EmptyState v-else-if="failed || !status" icon="refresh" text="加载失败" hint="无法获取机器人状态，请稍后重试">
      <button class="retry" @click="load">重试</button>
    </EmptyState>
    <template v-else>
      <Card title="QQ 机器人状态">
        <div class="st-rows">
          <div class="st-row">
            <span>配置状态</span>
            <strong :class="status.configured ? 'ok' : 'off'">{{ status.configured ? '已配置' : '未配置' }}</strong>
          </div>
          <div class="st-row"><span>App ID（掩码）</span><strong>{{ status.maskedAppId ?? '—' }}</strong></div>
          <div class="st-row">
            <span>access_token 有效期</span>
            <strong>{{ status.configured ? fmtExpiry(status.tokenExpiresAt) : '—' }}</strong>
          </div>
          <div class="st-row">
            <span>WebSocket 网关</span>
            <strong :class="status.gatewayOnline ? 'ok' : 'off'">
              {{ status.gatewayOnline ? '在线' : status.gatewayStarted ? '已启动 · 未连接' : '未启动' }}
            </strong>
          </div>
        </div>
      </Card>
      <Card title="配置与重连">
        <p class="hint">
          配置的查看与修改在用户控制台「设置 → 多通道机器人」（管理员可写，非管理员只读）。
          保存配置后服务端会自动重启 WebSocket 网关完成热重连，无需手动重启进程。
        </p>
      </Card>
    </template>
  </div>
</template>

<style scoped>
.st-rows { display: flex; flex-direction: column; }
.st-row { display: flex; justify-content: space-between; padding: 10px 2px; font-size: 13px; }
.st-row + .st-row { border-top: 1px solid var(--divider, #eef1f4); }
.ok { color: var(--income, #04a433); }
.off { color: var(--expense, #ca3032); }
.hint { font-size: 13px; line-height: 1.7; color: var(--text-secondary, #6b7a87); margin: 0; }
.retry { margin-top: 12px; color: var(--primary, #2e7bd6); background: none; border: none; cursor: pointer; font-size: 13px; }
</style>
