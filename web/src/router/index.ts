import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'landing', component: { template: '<div>落地页（待建）</div>' } },
  { path: '/login', name: 'login', component: { template: '<div>登录（待建）</div>' } },
  { path: '/console', name: 'console', component: { template: '<div>控制台（待建）</div>' } },
]

export default createRouter({
  history: createWebHistory(),
  routes,
})
