<script setup lang="ts">
import Icon from './DefenseIcon.vue'
</script>
<template>
  <div class="product-stage" aria-label="记一笔真实 App 产品界面展示">
    <div class="orbit orbit-one"></div><div class="orbit orbit-two"></div><div class="orbit orbit-three"></div>
    <span class="stage-spark spark-one">✳</span><span class="stage-spark spark-two">✧</span>
    <!-- 背景手机：月度详情图表（热力图与分类饼图联动） -->
    <div class="phone-mock phone-back">
      <div class="phone-top"><span>10:08</span><span>▰</span></div>
      <div class="phone-screen-wrap">
        <img src="/screenshots/chart.png" alt="RinklNote 月度详情与热力图" class="phone-real-img" />
      </div>
    </div>
    <!-- 前景手机：记账主页（流水日卡片与月度汇总） -->
    <div class="phone-mock phone-front">
      <div class="phone-top"><span>10:08</span><div class="phone-island"></div><span>▰</span></div>
      <div class="phone-screen-wrap">
        <img src="/screenshots/home.png" alt="RinklNote 记账主页" class="phone-real-img" />
      </div>
    </div>
    <div class="floating-tag tag-sync"><span class="tag-icon"><Icon name="sync" :size="17" /></span><div><b>先记录，再同步</b><small>Android 本地优先 · 离线秒级响应</small></div></div>
    <div class="floating-tag tag-ai"><span class="tag-icon"><Icon name="chat" :size="18" /></span><div><b>一句话智能记账</b><small>QQ 机器人 · 语音 · 支付通知捕获</small></div><span class="mini-check">✓</span></div>
    <div class="stage-caption">Android 模拟器实测运行截屏 · 真实界面</div>
  </div>
</template>
