<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getAdminBotStatus, getAdminOverview, type AdminBotStatus, type AdminOverview as Overview } from '../../api/admin'
import Card from '../../components/ui/Card.vue'
import EmptyState from '../../components/ui/EmptyState.vue'
import Skeleton from '../../components/ui/Skeleton.vue'
import StatCard from '../../components/ui/StatCard.vue'

/* 运维大盘：聚合计数 + 系统信息。机器人离线 → 顶部「静态」红条告警（管理端零常驻动效，不用呼吸）。 */
const data = ref<Overview | null>(null)
const bot = ref<AdminBotStatus | null>(null)
const loading = ref(true)
const failed = ref(false)

async function load() {
  loading.value = true
  failed.value = false
  try {
    const [ov, bs] = await Promise.all([getAdminOverview(), getAdminBotStatus().catch(() => null)])
    data.value = ov
    bot.value = bs
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="ov">
    <template v-if="loading">
      <Skeleton height="64px" style="margin-bottom: 12px" />
      <div class="stat-grid">
        <Skeleton v-for="i in 4" :key="i" height="86px" />
      </div>
      <Skeleton height="180px" style="margin-top: 12px" />
    </template>
    <EmptyState v-else-if="failed || !data" icon="refresh" text="加载失败" hint="无法获取运维数据，请稍后重试">
      <button class="retry" @click="load">重试</button>
    </EmptyState>
    <template v-else>
      <!-- 机器人离线静态红条：已配置但 WS 网关不在线才告警（未配置属于「尚未启用」不算离线） -->
      <div v-if="bot && bot.configured && !bot.gatewayOnline" class="offbar">
        ⚠ QQ 机器人 WebSocket 网关离线——推送不可用，请到「机器人运维」检查配置或保存配置触发重连
      </div>
      <div class="stat-grid">
        <StatCard label="用户总数" :value="String(data.totalUsers)" />
        <StatCard label="账单总数" :value="String(data.totalBills)" />
        <StatCard label="今日活跃" :value="String(data.todayActiveUsers)" />
        <StatCard label="QQ 绑定" :value="String(data.qqBoundUsers)" />
      </div>
      <Card title="系统信息">
        <div class="sys-rows">
          <div class="sys-row"><span>数据库类型</span><strong>{{ data.dbType }}</strong></div>
          <div class="sys-row">
            <span>LLM（DeepSeek）</span>
            <strong :class="data.llmConfigured ? 'ok' : 'off'">{{ data.llmConfigured ? '已配置' : '未配置' }}</strong>
          </div>
          <div class="sys-row">
            <span>语音转写（ASR）</span>
            <strong :class="data.asrConfigured ? 'ok' : 'off'">{{ data.asrConfigured ? '已配置' : '未配置' }}</strong>
          </div>
        </div>
      </Card>
      <p class="privacy-note">统计口径：仅聚合计数，不含任何账单明细或备注（隐私红线）。</p>
    </template>
  </div>
</template>

<style scoped>
.stat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; margin-bottom: 12px; }
.offbar {
  background: var(--expense-soft, #fdeaea); color: var(--expense, #ca3032);
  border: 1px solid var(--expense, #ca3032); border-radius: 10px;
  font-size: 13px; padding: 10px 14px; margin-bottom: 12px;
}
.sys-rows { display: flex; flex-direction: column; }
.sys-row { display: flex; justify-content: space-between; padding: 10px 2px; font-size: 13px; }
.sys-row + .sys-row { border-top: 1px solid var(--divider, #eef1f4); }
.ok { color: var(--income, #04a433); }
.off { color: var(--expense, #ca3032); }
.privacy-note { font-size: 12px; color: var(--text-tertiary, #9aa7b2); margin-top: 12px; }
.retry { margin-top: 12px; color: var(--primary, #2e7bd6); background: none; border: none; cursor: pointer; font-size: 13px; }
</style>
