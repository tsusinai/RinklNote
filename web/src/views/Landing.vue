<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '../stores/auth'
import LandingHero from './landing/LandingHero.vue'
import LandingFooter from './landing/LandingFooter.vue'
import BookkeepingDemo from './landing/BookkeepingDemo.vue'
import AssetsDemo from './landing/AssetsDemo.vue'
import AiDemo from './landing/AiDemo.vue'

/* 修硬伤（Landing CTA）：顶栏按钮此前写死「进入控制台」且链到 /login，
 * 已登录用户也被带去登录页。现按 auth store 是否有 token 切换：
 * 已登录 →「进入控制台」（/console）；未登录 →「登录 / 注册」（/login）。 */
const auth = useAuthStore()
const loggedIn = computed(() => !!auth.token)
</script>

<template>
  <div class="landing">
    <!-- 晨雾光斑：固定层（z-index:-1）铺满视口。Landing 不铺不透明底色，
         滚动过程中 hero 顶部 / 能力区中部 / 页脚底部依次透出三个 blob；
         规格落点「Landing hero、能力区」同页共用这一层（fixed 全局唯一，不重复挂） -->
    <div class="aurora" aria-hidden="true"><i class="a1"></i><i class="a2"></i><i class="a3"></i></div>

    <header class="landing-header">
      <span class="brand">RinklNote</span>
      <router-link class="btn btn-primary pressable" :to="loggedIn ? '/console' : '/login'">
        {{ loggedIn ? '进入控制台' : '登录 / 注册' }}
      </router-link>
    </header>

    <LandingHero />

    <div class="feature-bg">
      <!-- 进场 v-reveal stagger：三个能力区块依次错落揭示（0/120/240ms） -->
      <section id="feature-bookkeeping" class="feature" v-reveal="0">
        <BookkeepingDemo />
      </section>
      <section id="feature-assets" class="feature alt" v-reveal="120">
        <AssetsDemo />
      </section>
      <section id="feature-ai" class="feature" v-reveal="240">
        <AiDemo />
      </section>
    </div>

    <LandingFooter />
  </div>
</template>

<style scoped>
.landing { min-height: 100vh; }
.landing-header { position: sticky; top: 0; z-index: 50; display: flex; justify-content: space-between; align-items: center; padding: 16px 24px; backdrop-filter: blur(8px); background: color-mix(in srgb, var(--bg) 78%, transparent); border-bottom: 1px solid var(--border-light); }
.brand { font-weight: 800; font-size: 18px; letter-spacing: -0.01em; }
/* 顶栏 CTA 走全局 .btn/.btn-primary/.pressable（按钮按 token 切换文案与链路） */
.feature-bg { background: radial-gradient(1200px 500px at 50% -10%, rgba(126,193,252,.16), transparent 60%); }
.feature { max-width: 1080px; margin: 0 auto; padding: 64px 24px; }
.feature.alt { background: rgba(126,193,252,.05); }
@media (max-width: 640px) { .feature { padding: 44px 16px; } }
</style>
