<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  SUPPORTED_CURRENCIES, fetchRatesVsCny, convertFromCnyMinor, type RatesVsCny, type CurrencyDef,
} from '../../utils/rates'
import { parseMoneyToMinor, minorToDecimal } from '../../utils/money'
import Card from '../../components/ui/Card.vue'
import Btn from '../../components/ui/Btn.vue'
import Skeleton from '../../components/ui/Skeleton.vue'
import EmptyState from '../../components/ui/EmptyState.vue'

/* 多币种（Task 4.2 Web 侧）：真实汇率（服务端 GET /api/rates）+ 失败回落演示表。
 * 记账金额仍为本位币 CNY 整数分，本页换算仅用于展示，不动整数分存储约定。 */
const rates = ref<RatesVsCny | null>(null)
const loading = ref(true)
// 换算样例金额（元字符串，解析为分后逐币种换算）
const amount = ref('100')

const amountMinor = computed(() => parseMoneyToMinor(amount.value))

/** 非本位币展示顺序（CNY 自己不换算） */
const foreign: CurrencyDef[] = SUPPORTED_CURRENCIES.filter((c) => c.code !== 'CNY')

function rateOf(code: string): number {
  return rates.value?.rates[code] ?? 0
}

/** 更新时间展示：服务端 ISO-8601 → 本地时间；演示表无时间 */
function updatedAtLabel(): string {
  const iso = rates.value?.updatedAt
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : ` · 更新于 ${d.toLocaleString()}`
}

async function reload() {
  loading.value = true
  try { rates.value = await fetchRatesVsCny() } finally { loading.value = false }
}
onMounted(reload)
</script>

<template>
  <div class="page">
    <h2>多币种</h2>

    <Card>
      <!-- 汇率来源与状态 -->
      <div v-if="loading" class="sk">
        <Skeleton height="20px" width="40%" />
        <Skeleton height="44px" />
        <Skeleton v-for="i in 4" :key="i" height="56px" />
      </div>

      <template v-else-if="rates">
        <div class="src-row">
          <span :class="['src-badge', rates.source]">{{ rates.source === 'server' ? '实时汇率' : '演示汇率（离线回落）' }}</span>
          <span class="src-meta">本位币 人民币（CNY）{{ updatedAtLabel() }}</span>
          <button class="reload-btn" type="button" @click="reload">刷新</button>
        </div>

        <!-- 换算样例输入 -->
        <div class="section-label">输入人民币金额，查看各币种换算（仅展示，不改变记账金额）</div>
        <input class="sel amount-input amount" type="text" inputmode="decimal" placeholder="0.00" v-model="amount" />
        <div v-if="amount !== '' && amountMinor === null" class="msg err">金额格式非法（最多 2 位小数）</div>

        <!-- 换算清单 -->
        <template v-if="amountMinor !== null && amountMinor > 0">
          <div v-for="c in foreign" :key="c.code" class="cur-row">
            <span class="cur-symbol">{{ c.symbol }}</span>
            <span class="cur-name">
              <b>{{ c.code }}</b>
              <i>{{ c.name }}</i>
            </span>
            <span class="cur-conv amount">{{ c.symbol }} {{ minorToDecimal(convertFromCnyMinor(amountMinor, rateOf(c.code))) }}</span>
          </div>
        </template>

        <!-- 汇率牌价 -->
        <div class="section-label">牌价（每 1 单位外币兑人民币）</div>
        <div v-for="c in foreign" :key="'rate-' + c.code" class="cur-row rate">
          <span class="cur-symbol">{{ c.symbol }}</span>
          <span class="cur-name">
            <b>{{ c.code }}</b>
            <i>{{ c.name }}</i>
          </span>
          <span class="cur-rate amount">¥{{ rateOf(c.code).toFixed(4) }}</span>
        </div>
      </template>

      <EmptyState v-else icon="wallet" text="汇率加载失败" hint="点击刷新重试" />
      <Btn v-if="!loading && !rates" class="block" @click="reload">重新加载</Btn>
    </Card>
  </div>
</template>

<style scoped>
.src-row { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; flex-wrap: wrap; }
.src-badge { font-size: 12px; font-weight: 700; padding: 4px 12px; border-radius: 999px; }
.src-badge.server { background: var(--tag-web-bg); color: var(--tag-web-fg); }
.src-badge.demo { background: var(--tag-app-bg); color: var(--tag-app-fg); }
.src-meta { font-size: 12px; color: var(--muted); flex: 1; }
.reload-btn {
  padding: 6px 14px; border-radius: 999px; border: 1px solid var(--border); background: none;
  color: var(--primary); font-size: 13px; cursor: pointer; font-family: inherit;
  transition: background var(--dur-expand) var(--ease), border-color var(--dur-expand) var(--ease);
}
.reload-btn:hover { background: var(--primary-soft); border-color: var(--primary); }
.section-label { font-size: 12px; color: var(--muted); margin: 14px 0 8px; }
.amount-input { font-size: 20px; font-weight: 700; }
.msg { margin: 8px 0; font-size: 13px; }
.msg.err { color: var(--expense); }
.cur-row {
  display: flex; align-items: center; gap: 12px; padding: 11px 2px;
  border-bottom: 1px solid var(--border-light); font-size: 14px;
}
.cur-row:last-of-type { border-bottom: none; }
.cur-symbol {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; border-radius: 50%; flex: none;
  background: var(--primary-soft); color: var(--primary); font-weight: 700; font-size: 14px;
}
.cur-name { display: flex; flex-direction: column; flex: 1; min-width: 0; }
.cur-name b { font-weight: 600; }
.cur-name i { font-style: normal; font-size: 12px; color: var(--muted); }
.cur-conv { font-weight: 700; }
.cur-rate { color: var(--muted); font-size: 13px; }
.sk { display: flex; flex-direction: column; gap: 10px; }
</style>
