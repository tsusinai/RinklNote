# RinklNote Web 控制台

Vite + Vue 3 + TypeScript + Pinia + Vue Router + ECharts。

公开落地页在 `/`（`src/views/Landing.vue`，分区组件在 `src/views/landing/`），只 import `mock/demo-data.ts` + `utils/*`，不触 api/store——数据源隔离。

```bash
npm install
npm run dev        # http://localhost:5173，/api 代理到 Ktor :8080
npm run typecheck  # vue-tsc --noEmit
npm test           # vitest run
npm run build      # vue-tsc --noEmit && vite build → web/dist
```
