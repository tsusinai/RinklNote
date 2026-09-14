<script setup lang="ts">
import LandingHero from './landing/LandingHero.vue'
import LandingFooter from './landing/LandingFooter.vue'
import BookkeepingDemo from './landing/BookkeepingDemo.vue'
import AssetsDemo from './landing/AssetsDemo.vue'
import AiDemo from './landing/AiDemo.vue'
</script>

<template>
  <div class="landing">
    <header class="landing-header">
      <span class="brand">RinklNote</span>
      <router-link class="btn-cta" to="/login">进入控制台</router-link>
    </header>

    <LandingHero />

    <div class="feature-bg" v-reveal>
      <section id="feature-bookkeeping" class="feature">
        <BookkeepingDemo />
      </section>
      <section id="feature-assets" class="feature alt">
        <AssetsDemo />
      </section>
      <section id="feature-ai" class="feature">
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
.btn-cta { padding: 9px 16px; border-radius: 12px; background: var(--primary); color: var(--on-primary); font-weight: 600; font-size: 14px; }
.feature-bg { background: radial-gradient(1200px 500px at 50% -10%, rgba(126,193,252,.16), transparent 60%); }
.feature { max-width: 1080px; margin: 0 auto; padding: 64px 24px; }
.feature.alt { background: rgba(126,193,252,.05); }
@media (max-width: 640px) { .feature { padding: 44px 16px; } }
</style>
