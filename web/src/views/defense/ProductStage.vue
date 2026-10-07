<script setup lang="ts">
import Icon from './DefenseIcon.vue'
</script>
<template>
  <div class="product-stage" aria-label="记一笔产品界面概念展示，所有金额均为演示数据">
    <div class="orbit orbit-one"></div><div class="orbit orbit-two"></div><div class="orbit orbit-three"></div>
    <span class="stage-spark spark-one">✳</span><span class="stage-spark spark-two">✧</span>
    <div class="phone-mock phone-back">
      <div class="phone-top"><span>9:41</span><span>▰</span></div>
      <div class="mock-heading">收支统计 <Icon name="chart" :size="16" /></div>
      <span class="mock-muted">2026 年 9 月 · 演示</span>
      <div class="donut"><div><small>本月支出</small><b>2,680<span>.00</span></b></div></div>
      <div class="mock-legend"><span>● 餐饮</span><span>● 购物</span><span>● 交通</span></div>
      <div class="mock-bars"><i v-for="(h, i) in [32, 52, 43, 76, 58, 92, 64]" :key="i" :style="{ height: `${h}%` }"></i></div>
      <div class="mock-muted spaced"><span>周一</span><span>周日</span></div>
    </div>
    <div class="phone-mock phone-front">
      <div class="phone-top"><span>9:41</span><div class="phone-island"></div><span>▰</span></div>
      <div class="mock-heading">记一笔 <span class="mock-avatar">R</span></div>
      <div class="month-row"><span>2026 年 9 月⌄</span><Icon name="grid" :size="15" /></div>
      <div class="balance-card"><span>本月支出</span><strong><small>¥</small> 2,680<small>.00</small></strong><div><span>收入 ¥ 6,000.00</span><span>结余 ¥ 3,320.00</span></div></div>
      <div class="budget-line"><Icon name="leaf" :size="14" /><span>预算还剩 46%，保持好节奏</span></div>
      <div class="day-label">今天 <span>9 月 18 日，星期五</span></div>
      <div v-for="bill in [{ title: '一顿好好吃的午餐', type: '餐饮 · 午餐', amount: '28.00', icon: 'coffee' }, { title: '给自己一杯咖啡', type: '餐饮 · 饮品', amount: '18.00', icon: 'coffee' }, { title: '地铁去上班', type: '出行 · 公共交通', amount: '4.00', icon: 'wallet' }]" :key="bill.title" class="mock-bill"><span class="bill-icon"><Icon :name="bill.icon" :size="16" /></span><div><b>{{ bill.title }}</b><small>{{ bill.type }}</small></div><strong>−{{ bill.amount }}</strong></div>
      <div class="mock-add"><Icon name="plus" :size="15" /> 记一笔</div>
      <div class="mock-bottom"><span><Icon name="target" :size="16" />计划</span><span class="active"><Icon name="book" :size="16" />记账</span><span><Icon name="wallet" :size="16" />资产</span><span><Icon name="grid" :size="16" />我的</span></div>
    </div>
    <div class="floating-tag tag-sync"><span class="tag-icon"><Icon name="sync" :size="17" /></span><div><b>先记录，再同步</b><small>Android 本地优先 · 断网也能记</small></div></div>
    <div class="floating-tag tag-ai"><span class="tag-icon"><Icon name="chat" :size="18" /></span><div><b>QQ：午饭花了二十八块</b><small>不打开 App，也能记一笔</small></div><span class="mini-check">✓</span></div>
    <div class="stage-caption">产品界面概念展示 · 示例数据</div>
  </div>
</template>
