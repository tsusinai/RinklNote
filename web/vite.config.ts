import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

/* PWA（Task 3.1）：manifest + 应用壳 precache + 导航兜底。
 * 只做静态资源离线，**不做**账单数据离线缓存（同步语义复杂，YAGNI）；
 * /api 请求一律在线走（无 runtimeCaching），离线时由页面层自行提示。
 * 图标取 resource/ 官方资产复制/缩放副本（public/ 下，源资产不动）。 */
export default defineConfig({
  resolve: {
    alias: { vue: 'vue/dist/vue.esm-bundler.js' },
  },
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.png', 'apple-touch-icon.png'],
      manifest: {
        name: 'RinklNote 记一笔',
        short_name: '记一笔',
        description: '个人记账：自然语言记账与 AI 洞察，手机 / QQ / 飞书 / 企微多端同步。',
        lang: 'zh-CN',
        display: 'standalone',
        start_url: '/',
        scope: '/',
        theme_color: '#F7F7F9',
        background_color: '#F7F7F9',
        icons: [
          { src: 'pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512.png', sizes: '512x512', type: 'image/png' },
        ],
      },
      workbox: {
        // 应用壳：html/js/css/图标全量 precache；navigateFallback 兜底 SPA 路由
        globPatterns: ['**/*.{js,css,html,png,svg,ico,woff2}'],
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//, /^\/uploads\//],
        maximumFileSizeToCacheInBytes: 3 * 1024 * 1024, // pwa-512 图标约 0.7MB
      },
    }),
  ],
  server: {
    port: 5173,
    proxy: {
      // Control console talks to the Ktor API on the same relative path.
      // Dev: Vite proxies /api → Ktor :8080. Prod: same-origin reverse proxy.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
  },
  test: {
    environment: 'jsdom',
    globals: true,
    fakeTimers: {
      toFake: [
        'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval',
        'setImmediate', 'clearImmediate', 'Date',
        'requestAnimationFrame', 'cancelAnimationFrame', 'performance',
      ],
    },
  },
})
