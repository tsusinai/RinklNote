<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '../stores/auth'
import InkFieldBackground from '../components/InkFieldBackground.vue'
import LandingHero from './landing/LandingHero.vue'
import LandingFooter from './landing/LandingFooter.vue'
import BookkeepingDemo from './landing/BookkeepingDemo.vue'
import AssetsDemo from './landing/AssetsDemo.vue'
import AiDemo from './landing/AiDemo.vue'

const auth = useAuthStore()
const loggedIn = computed(() => !!auth.token)
</script>

<template>
  <main class="landing">
    <InkFieldBackground />
    <div class="landing-grid" aria-hidden="true"></div>

    <header class="landing-header">
      <router-link class="landing-brand" to="/" aria-label="RinklNote 首页">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 28 28" role="img"><path d="M4 6.5A2.5 2.5 0 0 1 6.5 4h15A2.5 2.5 0 0 1 24 6.5v6H10.5A2.5 2.5 0 0 0 8 15v7H6.5A2.5 2.5 0 0 1 4 19.5Z" /><path d="M10.5 15H24v5.5a2.5 2.5 0 0 1-2.5 2.5H8v-5.5a2.5 2.5 0 0 1 2.5-2.5Z" /></svg>
        </span>
        <span><strong>RinklNote</strong><small>个人财务工作台</small></span>
      </router-link>
      <nav class="landing-nav" aria-label="页面导航">
        <a href="#feature-bookkeeping">记账</a>
        <a href="#feature-assets">资产</a>
        <a href="#feature-ai">洞察</a>
      </nav>
      <div class="header-actions">
        <router-link class="header-text-link" to="/defense">项目演示 <span aria-hidden="true">↗</span></router-link>
        <router-link class="btn btn-primary pressable" :to="loggedIn ? '/console' : '/login'">
          {{ loggedIn ? '进入工作台' : '登录 / 注册' }}
        </router-link>
      </div>
    </header>

    <LandingHero />

    <div class="feature-bg">
      <section id="feature-bookkeeping" class="feature feature-ledger" v-reveal="0">
        <BookkeepingDemo />
      </section>
      <section id="feature-assets" class="feature feature-assets" v-reveal="120">
        <AssetsDemo />
      </section>
      <section id="feature-ai" class="feature feature-insight" v-reveal="240">
        <AiDemo />
      </section>
    </div>

    <LandingFooter />
  </main>
</template>

<style scoped>
.landing { position: relative; min-height: 100dvh; isolation: isolate; overflow: clip; background: color-mix(in srgb, var(--bg) 82%, transparent); }
.landing-grid { position: absolute; inset: 0 0 auto; height: 860px; pointer-events: none; z-index: -1; opacity: .46; background-image: linear-gradient(to right, color-mix(in srgb, var(--primary-ink) 10%, transparent) 1px, transparent 1px), linear-gradient(to bottom, color-mix(in srgb, var(--primary-ink) 10%, transparent) 1px, transparent 1px); background-size: clamp(28px, 5vw, 56px) clamp(28px, 5vw, 56px); mask-image: linear-gradient(to bottom, black 0%, transparent 88%); }
.landing-header { position: sticky; top: 0; z-index: 50; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 24px; min-height: 72px; padding: 12px clamp(16px, 5vw, 72px); background: color-mix(in srgb, var(--bg) 78%, transparent); backdrop-filter: blur(18px); border-bottom: 1px solid color-mix(in srgb, var(--border) 78%, transparent); }
.landing-brand { display: inline-flex; align-items: center; gap: 10px; width: fit-content; letter-spacing: -.02em; }
.landing-brand strong { display: block; font-size: 18px; line-height: 1.05; font-weight: 800; }
.landing-brand small { display: block; margin-top: 3px; color: var(--muted); font: 11px/1.1 "Noto Sans SC", sans-serif; letter-spacing: .08em; }
.brand-mark { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; background: var(--primary); color: var(--on-primary); }
.brand-mark svg { width: 24px; height: 24px; fill: currentColor; }
.landing-nav { display: flex; align-items: center; gap: clamp(16px, 3vw, 34px); font-size: 13px; color: var(--muted); }
.landing-nav a { position: relative; padding: 8px 0; transition: color var(--dur-expand) var(--ease); }
.landing-nav a::after { content: ''; position: absolute; left: 0; right: 100%; bottom: 0; height: 2px; background: var(--primary); transition: right var(--dur-expand) var(--ease); }
.landing-nav a:hover { color: var(--text); }
.landing-nav a:hover::after { right: 0; }
.header-actions { display: flex; justify-content: flex-end; align-items: center; gap: 18px; }
.header-text-link { color: var(--muted); font-size: 13px; transition: color var(--dur-expand) var(--ease); }
.header-text-link:hover { color: var(--primary-ink); }
.header-text-link span { color: var(--primary-ink); }
.header-actions .btn { white-space: nowrap; padding-inline: 16px; }
.feature-bg { position: relative; border-top: 1px solid var(--border); }
.feature { position: relative; max-width: 1180px; margin: 0 auto; padding: clamp(72px, 10vw, 132px) clamp(20px, 5vw, 64px); }
.feature + .feature { border-top: 1px solid var(--border-light); }
.feature::before { content: ''; position: absolute; top: 0; bottom: 0; left: max(20px, calc((100% - 1180px) / 2)); width: 1px; background: color-mix(in srgb, var(--border) 66%, transparent); opacity: .5; pointer-events: none; }
.feature-assets { max-width: none; background: color-mix(in srgb, var(--primary-soft) 28%, transparent); padding-left: max(20px, calc((100% - 1180px) / 2 + 20px)); padding-right: max(20px, calc((100% - 1180px) / 2 + 20px)); }
.feature-assets::after { content: '02 / 看资产'; position: absolute; top: clamp(26px, 4vw, 48px); right: max(20px, calc((100% - 1180px) / 2 + 20px)); color: var(--muted); font: 11px/1 "Noto Sans SC", sans-serif; letter-spacing: .16em; }
.feature-insight::after { content: '03 / 做复盘'; position: absolute; top: clamp(26px, 4vw, 48px); right: max(20px, calc((100% - 1180px) / 2 + 20px)); color: var(--muted); font: 11px/1 "Noto Sans SC", sans-serif; letter-spacing: .16em; }
@media (max-width: 820px) { .landing-header { grid-template-columns: 1fr auto; } .landing-nav { display: none; } .header-text-link { display: none; } .feature { padding: 64px 20px; } .feature-assets { padding-left: 20px; padding-right: 20px; } .feature::before { left: 20px; } }
@media (max-width: 430px) { .landing-header { min-height: 64px; padding-inline: 14px; } .landing-brand small { display: none; } .landing-brand strong { font-size: 16px; } .brand-mark { width: 30px; height: 30px; border-radius: 8px; } .brand-mark svg { width: 21px; height: 21px; } .header-actions .btn { padding-inline: 12px; font-size: 13px; } }
</style>
