<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '../stores/auth'
import InkFieldBackground from '../components/InkFieldBackground.vue'
import LandingHero from './landing/LandingHero.vue'
import LandingFooter from './landing/LandingFooter.vue'
import BookkeepingDemo from './landing/BookkeepingDemo.vue'
import AssetsDemo from './landing/AssetsDemo.vue'
import AiDemo from './landing/AiDemo.vue'

/* 修硬伤（Landing CTA）：顶栏按钮此前写死「进入控制台」且链到 /login，
 * 已登录用户也被带去登录页。现按 auth store 是否有 token 切换：
 * 已登录 →「进入控制台」（/console）；未登录 →「登录 / 注册」（/login）。
 * 背景（Task 3.6）：静态 .aurora 晨雾光斑已替换为墨晕热力场（InkFieldBackground，
 * fixed z-index:-1 铺底，无指针交互）；登录页仍用 .aurora，全局样式保留。 */
const auth = useAuthStore()
const loggedIn = computed(() => !!auth.token)
</script>

<template>
  <div class="landing">
    <!-- 墨晕热力场：热流驱动墨在宣纸底上缓慢晕开（组件内部 Teleport 到 body） -->
    <InkFieldBackground />

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
.landing-header { position: sticky; top: 0; z-index: 50; display: flex; justify-content: space-between; align-items: center; padding: 18px clamp(20px, 5vw, 64px); backdrop-filter: blur(16px); background: color-mix(in srgb, var(--bg) 84%, transparent); border-bottom: 1px solid var(--border); }
.brand { font-weight: 800; font-size: 22px; letter-spacing: -0.04em; }
.feature-bg { border-top: 1px solid var(--border); }
.feature { max-width: 1080px; margin: 0 auto; padding: 92px 24px; }
.feature + .feature { border-top: 1px solid var(--border-light); }
.feature.alt { max-width: none; padding-left: max(24px, calc((100% - 1080px) / 2 + 24px)); padding-right: max(24px, calc((100% - 1080px) / 2 + 24px)); background: color-mix(in srgb, var(--primary-soft) 38%, transparent); }
@media (max-width: 640px) { .feature { padding: 44px 16px; } }

</style>
