import { createRouter, createWebHistory } from 'vue-router'
import { installGuards } from './guards'

const routes = [
  { path: '/', name: 'landing', component: () => import('../views/Landing.vue') },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/console', name: 'console', component: () => import('../views/console/ConsoleLayout.vue') },
]

const router = createRouter({ history: createWebHistory(), routes })
installGuards(router)
export default router
