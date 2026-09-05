<script setup lang="ts">
import { demoAccounts, demoNetAssets } from '../../mock/demo-data'
import { formatMoney } from '../../utils/format'

const assets = demoAccounts.filter((a) => a.kind === 'asset')
const debts = demoAccounts.filter((a) => a.kind === 'liability')
</script>

<template>
  <div class="feature-grid">
    <div class="demo-phone" v-reveal>
      <div class="phone-card">
        <div class="phone-title"><span>资产管理</span><span class="pill">净资 ¥{{ formatMoney(demoNetAssets) }}</span></div>
        <div class="net-card">
          <span class="net-label">总资产</span>
          <span class="net-value amount">¥{{ formatMoney(demoAccounts.filter((a) => a.kind === 'asset').reduce((s, a) => s + a.balance, 0)) }}</span>
        </div>
        <div class="acct-list">
          <div v-for="a in assets" :key="a.id" class="acct-row">
            <span class="avatar" :style="{ background: a.iconColor }">{{ a.name[0] }}</span>
            <span class="acct-name">{{ a.name }}</span>
            <span class="acct-bal amount">{{ formatMoney(a.balance) }}</span>
          </div>
        </div>
        <div class="liab-title">负债</div>
        <div class="acct-list">
          <div v-for="a in debts" :key="a.id" class="acct-row">
            <span class="avatar liab" :style="{ background: a.iconColor }">{{ a.name[0] }}</span>
            <span class="acct-name">{{ a.name }}</span>
            <span class="acct-bal liab amount">-{{ formatMoney(a.balance) }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="feature-copy" v-reveal>
      <span class="eyebrow">资产 · 收支平衡</span>
      <h2>明确知道你有多少，欠多少</h2>
      <p class="lead">账户期初值 + 每一笔账单自动派生最新余额；资产与负债分开，净资清晰可见。</p>
      <ul class="points">
        <li>银行卡 / 微信 / 信用卡多账户统一管理</li>
        <li>收入支出自动滚入账户余额</li>
        <li>资产 - 负债 = 真实净资</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.feature-grid { display: grid; grid-template-columns: 1.1fr 1fr; gap: clamp(24px, 5vw, 64px); align-items: center; }
.feature-copy .eyebrow { font-size: 13px; color: var(--expense); font-weight: 600; letter-spacing: .04em; }
.feature-copy h2 { font-size: clamp(26px, 4vw, 42px); line-height: 1.15; margin: 12px 0 16px; font-weight: 800; letter-spacing: -0.02em; }
.feature-copy .lead { color: var(--muted); line-height: 1.7; margin: 0 0 20px; font-size: 16px; }
.points { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.points li { position: relative; padding-left: 24px; color: var(--text); font-size: 15px; }
.points li::before { content: '✓'; position: absolute; left: 0; color: var(--primary); font-weight: 700; }
.demo-phone { display: flex; justify-content: center; }
.phone-card { width: min(100%, 340px); background: var(--card); border-radius: 24px; padding: 18px 16px; box-shadow: var(--shadow); border: 1px solid var(--border-light); }
.phone-title { display: flex; justify-content: space-between; align-items: center; font-weight: 700; margin-bottom: 12px; }
.pill { padding: 2px 10px; border-radius: 8px; font-size: 12px; font-weight: 600; background: var(--primary-soft); color: var(--primary); }
.net-card { background: var(--primary); color: #0b2b44; border-radius: 14px; padding: 14px 16px; margin-bottom: 16px; display: flex; justify-content: space-between; align-items: baseline; }
.net-label { font-weight: 600; opacity: .85; }
.net-value { font-size: 24px; font-weight: 800; }
.acct-list { display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px; }
.acct-row { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: center; padding: 10px; border: 1px solid var(--border-light); border-radius: 12px; }
.avatar { width: 34px; height: 34px; border-radius: 50%; color: #fff; display: grid; place-items: center; font-weight: 700; font-size: 15px; }
.avatar.liab { color: #fff; }
.acct-name { font-weight: 600; font-size: 14px; }
.acct-bal { font-weight: 700; }
.acct-bal.liab { color: var(--expense); }
.liab-title { font-size: 12px; color: var(--muted); margin-bottom: 6px; }
@media (max-width: 820px) { .feature-grid { grid-template-columns: 1fr; } }
</style>
