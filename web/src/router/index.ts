import { createRouter, createWebHistory } from 'vue-router'
import { installGuards } from './guards'

const consoleChildren = [
  { path: '', name: 'bookkeeping', component: () => import('../views/console/Bookkeeping.vue') },
  { path: 'bills', name: 'bills', component: () => import('../views/console/Bills.vue') },
  { path: 'charts', name: 'charts', component: () => import('../views/console/Charts.vue') },
  { path: 'assets', name: 'assets', component: () => import('../views/console/Assets.vue') },
  { path: 'me', name: 'me', component: () => import('../views/console/Me.vue') },
  { path: 'settings', name: 'settings', component: () => import('../views/console/Settings.vue') },
]

const routes = [
  { path: '/', name: 'landing', component: () => import('../views/Landing.vue') },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/console', name: 'console', component: () => import('../views/console/ConsoleLayout.vue'), children: consoleChildren },
]

const router = createRouter({ history: createWebHistory(), routes })
installGuards(router)
export default router
